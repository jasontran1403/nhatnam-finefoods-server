package com.nhatnam.server.restcontroller;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.WarehouseReceipt.CostStatus;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.WarehouseReceiptCostService;
import com.nhatnam.server.service.WarehouseReceiptCostService.ConfirmCostRequest;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Endpoints cho SUPER_ACCOUNTANT (và ADMIN/OWNER):
 * - Xem danh sách phiếu nhập kho chờ nhập giá vốn
 * - Xem trước (preview) giá vốn tạm tính sau khi phân bổ thuế/phí
 * - Nhập giá vốn và xác nhận → cập nhật giá vốn cho các lô đã tạo lúc nhập kho
 *
 * LƯU Ý: tồn kho ĐÃ được cộng ngay lúc nhân viên kho tạo phiếu nhập
 * (lô tạo trước với giá vốn = 0). Bước này chỉ CẬP NHẬT GIÁ VỐN, không cộng tồn nữa.
 *
 * Toàn bộ ghi DB nằm trong {@link WarehouseReceiptCostService} (có @Transactional)
 * để mọi lỗi nghiệp vụ đều rollback sạch — không còn tình trạng cập nhật dở dang.
 */
@RestController
@RequestMapping("/api/accountant/warehouse-receipts")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','ADMIN','OWNER')")
public class AccountantWarehouseController {

    private final WarehouseReceiptRepository receiptRepository;
    private final WarehouseReceiptCostService costService;

    /** Danh sách phiếu nhập chờ nhập giá vốn */
    @GetMapping("/pending-cost")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPendingCost() {
        try {
            List<WarehouseReceipt> list = receiptRepository
                    .findByReceiptTypeAndCostStatusOrderByCreatedAtDesc(
                            ReceiptType.IMPORT, CostStatus.PENDING_COST);
            List<Map<String, Object>> result = list.stream()
                    .map(this::mapReceiptBasic)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("listPendingCost error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Chi tiết phiếu nhập (kèm items để nhập giá vốn) */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDetail(@PathVariable Long id) {
        return receiptRepository.findById(id)
                .map(r -> ResponseEntity.ok(ApiResponse.success(mapReceiptDetail(r), "OK")))
                .orElse(ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy")));
    }

    /**
     * PREVIEW — tính thử giá vốn tạm tính (đã phân bổ thuế/phí), KHÔNG ghi DB.
     * FE gọi khi bấm nút "Xem trước giá vốn".
     */
    @PostMapping("/{id}/preview-cost")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewCost(
            @PathVariable Long id, @RequestBody ConfirmCostRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(costService.previewCost(id, req), "OK"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("previewCost error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Xác nhận giá vốn — cập nhật giá vốn của các lô đã tạo lúc nhập kho */
    @PostMapping("/{id}/confirm-cost")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmCost(
            @PathVariable Long id,
            @RequestBody ConfirmCostRequest req,
            Authentication auth) {
        try {
            Long actorId = (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
            Map<String, Object> result = costService.confirmCost(id, req, actorId);
            return ResponseEntity.ok(ApiResponse.success(result, "Đã xác nhận giá vốn"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (OptimisticLockingFailureException
                 | OptimisticLockException e) {
            // BUG FIX KB6: race — phiếu đã bị người khác sửa/confirm giữa lúc user
            // đang thao tác. Trả message rõ ràng để FE hiện toast + tự reload.
            log.warn("confirmCost race lock receiptId={}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                    "Phiếu đã được người khác chỉnh sửa hoặc xác nhận cùng lúc. "
                            + "Vui lòng tải lại trang và thử lại."));
        } catch (Exception e) {
            log.error("confirmCost error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> mapReceiptBasic(WarehouseReceipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            r.getId());
        m.put("receiptCode",   r.getReceiptCode());
        m.put("warehouseId",   r.getWarehouse().getId());
        m.put("warehouseName", r.getWarehouse().getName());
        m.put("referenceCode", r.getReferenceCode());
        m.put("note",          r.getNote());
        m.put("costStatus",    r.getCostStatus() != null ? r.getCostStatus().name() : null);
        m.put("createdByName", r.getCreatedByName());
        m.put("createdAt",     r.getCreatedAt());
        m.put("itemCount",     r.getItems().size());
        m.put("version",       r.getVersion());   // ← THÊM cho optimistic lock (KB6)
        return m;
    }

    private Map<String, Object> mapReceiptDetail(WarehouseReceipt r) {
        Map<String, Object> m = mapReceiptBasic(r);
        List<Map<String, Object>> items = r.getItems().stream().map(item -> {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id",             item.getId());
            i.put("ingredientId",   item.getIngredientId());
            i.put("ingredientName", item.resolvedIngredientName());
            i.put("unit",           item.resolvedIngredientUnit());
            i.put("imageUrl",       item.resolvedIngredientImageUrl());
            i.put("quantity",       item.getQuantity());
            i.put("expiryDate",     item.getExpiryDate());
            i.put("unitPrice",      item.getUnitPrice());     // null khi chưa nhập
            i.put("allocatedFee",   item.getAllocatedFee());
            i.put("costPrice",      item.getCostPrice());     // giá vốn cuối cùng
            return i;
        }).collect(Collectors.toList());
        m.put("items", items);

        m.put("costEntries", r.getCostEntries().stream()
                .sorted(Comparator.comparingInt(WarehouseReceiptCostEntry::getSortOrder))
                .map(ce -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", ce.getId());
                    c.put("label", ce.getLabel());
                    c.put("amount", ce.getAmount());
                    c.put("itemIds", costService.deserializeIds(ce.getAppliesToItemIds()));
                    return c;
                }).collect(Collectors.toList()));
        return m;
    }
}
