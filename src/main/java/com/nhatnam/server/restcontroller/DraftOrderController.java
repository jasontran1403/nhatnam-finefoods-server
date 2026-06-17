package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.request.SaveDraftRequest;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.DraftOrderResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.DraftOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seller/drafts")
@RequiredArgsConstructor
@Slf4j
public class DraftOrderController {

    private final DraftOrderService draftOrderService;

    /** Lưu đơn nháp mới (DRAFT hoặc SCHEDULED). */
    @PostMapping
    public ResponseEntity<ApiResponse<DraftOrderResponse>> saveDraft(
            @RequestBody SaveDraftRequest req,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            DraftOrderResponse result = draftOrderService.saveDraft(req, user.getId());
            String msg = "SCHEDULED".equals(result.getType()) ? "Đã lưu đơn hẹn giờ" : "Đã lưu đơn nháp";
            return ResponseEntity.ok(ApiResponse.success(result, msg));
        } catch (Exception e) {
            log.error("[DRAFT] saveDraft error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Danh sách đơn nháp của seller (bao gồm cả SCHEDULED). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<DraftOrderResponse>>> getMyDrafts(
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            List<DraftOrderResponse> result = draftOrderService.getMyDrafts(user.getId());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[DRAFT] getMyDrafts error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Chi tiết 1 đơn nháp. */
    @GetMapping("/{draftId}")
    public ResponseEntity<ApiResponse<DraftOrderResponse>> getDraftById(
            @PathVariable Long draftId,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            DraftOrderResponse result = draftOrderService.getDraftById(draftId, user.getId());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[DRAFT] getDraftById error draftId={}", draftId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Xóa đơn nháp. */
    @DeleteMapping("/{draftId}")
    public ResponseEntity<ApiResponse<Void>> deleteDraft(
            @PathVariable Long draftId,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            draftOrderService.deleteDraft(draftId, user.getId());
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa đơn nháp"));
        } catch (Exception e) {
            log.error("[DRAFT] deleteDraft error draftId={}", draftId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Kiểm tra tồn kho trước khi chuyển đơn nháp sang POS. */
    @GetMapping("/{draftId}/stock-check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkStock(
            @PathVariable Long draftId,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            DraftOrderService.StockCheckResult result = draftOrderService.checkStockForDraft(draftId, user.getId());

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("sufficient", result.sufficient());
            data.put("outOfStockItems", result.outOfStockItems().stream().map(item -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("ingredientId",    item.ingredientId());
                m.put("ingredientName",  item.ingredientName());
                m.put("needed",          item.needed());
                m.put("available",       item.available());
                m.put("affectedProducts", item.affectedProducts());
                return m;
            }).toList());

            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            log.error("[DRAFT] checkStock error draftId={}", draftId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /**
     * Tạo phiếu đặt hàng (invoice PDF) từ đơn nháp.
     * - Không hiển thị mã số đơn hàng và prefix năm ở góc phải.
     * - Thời gian giao hàng = scheduledAt (nếu là SCHEDULED), fallback deliveryDatetime.
     * - showPrices / hideAllPrices lấy từ draft đã lưu.
     */
    @GetMapping("/{draftId}/invoice")
    public ResponseEntity<?> generateDraftInvoice(
            @PathVariable Long draftId,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            byte[] pdfBytes = draftOrderService.generateDraftInvoice(draftId, user.getId());

            // Lấy draftCode để đặt tên file
            DraftOrderResponse draft = draftOrderService.getDraftById(draftId, user.getId());
            String filename = "phieu_dat_hang_" + draft.getDraftCode() + ".pdf";

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/pdf")
                    .body(pdfBytes);
        } catch (Exception e) {
            log.error("[DRAFT] generateInvoice error draftId={}", draftId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error(500, "Lỗi khi tạo phiếu: " + e.getMessage()));
        }
    }
}