package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.entity.IngredientExpiry;
import com.nhatnam.server.entity.IngredientStock;
import com.nhatnam.server.entity.LotPricingRequest;
import com.nhatnam.server.entity.LotPricingRequest.Status;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.IngredientExpiryRepository;
import com.nhatnam.server.repository.IngredientStockRepository;
import com.nhatnam.server.repository.LotPricingRequestRepository;
import com.nhatnam.server.repository.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * KẾ TOÁN TRƯỞNG NHẬP GIÁ VỐN cho các LÔ MỚI được tạo từ phiếu điều chỉnh tồn kho.
 *
 * <p>Nhân viên kho tạo lô mới → lô đã có số lượng/HSD thật và giá vốn tạm = 1.
 * Ở đây kế toán trưởng nhập:
 * <ul>
 *   <li>ĐƠN GIÁ (giá 1 đơn vị tính), hoặc</li>
 *   <li>GIÁ TỔNG của cả lô → server chia cho số lượng.</li>
 * </ul>
 * Kết quả giá vốn luôn được <b>làm tròn tới hàng đơn vị</b> (đồng).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LotPricingService {

    private final LotPricingRequestRepository requestRepository;
    private final IngredientExpiryRepository expiryRepository;
    private final IngredientStockRepository stockRepository;
    private final UserRepository userRepository;

    // ── Request DTO ─────────────────────────────────────────────────────────

    @Data
    public static class SetLotPriceRequest {
        /** Giá 1 đơn vị tính. Ưu tiên dùng nếu được gửi lên. */
        private BigDecimal unitPrice;
        /** Giá của cả lô — server chia cho số lượng để ra giá vốn/đvt. */
        private BigDecimal totalPrice;
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPending() {
        return requestRepository.findByStatusOrderByCreatedAtDesc(Status.PENDING)
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPriced() {
        return requestRepository.findTop100ByStatusOrderByPricedAtDesc(Status.PRICED)
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return requestRepository.countByStatus(Status.PENDING);
    }

    // ── Định giá ────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> setPrice(Long requestId, SetLotPriceRequest req, Long actorUserId) {
        LotPricingRequest pr = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy yêu cầu định giá #" + requestId));
        if (pr.getStatus() == Status.PRICED)
            throw new BusinessException("Lô này đã được nhập giá vốn trước đó");

        IngredientExpiry lot = expiryRepository.findById(pr.getIngredientExpiryId())
                .orElseThrow(() -> new BusinessException("Không tìm thấy lô kho tương ứng"));

        // Số lượng dùng để chia giá tổng: lấy số lượng HIỆN TẠI của lô (có thể đã
        // bị xuất bớt sau khi tạo yêu cầu); fallback về số lượng lúc tạo yêu cầu.
        BigDecimal qty = lot.getQuantity() != null && lot.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? lot.getQuantity()
                : pr.getQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Số lượng lô không hợp lệ, không thể tính giá vốn");

        BigDecimal unitCost = resolveUnitCost(req, qty);

        lot.setCostPrice(unitCost);
        lot.setUpdatedAt(System.currentTimeMillis());
        expiryRepository.save(lot);

        // Cập nhật lại GIÁ TRỊ tồn kho của nguyên liệu tại kho đó
        stockRepository.findByIngredientIdAndWarehouseId(pr.getIngredientId(), pr.getWarehouseId())
                .ifPresent(stock -> {
                    stock.setTotalCostValue(recalcTotalCostValue(pr.getWarehouseId(), pr.getIngredientId()));
                    stock.setUpdatedAt(System.currentTimeMillis());
                    stockRepository.save(stock);
                });

        User actor = actorUserId != null ? userRepository.findById(actorUserId).orElse(null) : null;
        pr.setUnitCost(unitCost);
        pr.setTotalCost(unitCost.multiply(qty).setScale(2, RoundingMode.HALF_UP));
        pr.setQuantity(qty);
        pr.setStatus(Status.PRICED);
        pr.setPricedAt(System.currentTimeMillis());
        if (actor != null) {
            pr.setPricedById(actor.getId());
            pr.setPricedByName(resolveName(actor));
        }
        return toMap(requestRepository.save(pr));
    }

    /** Đơn giá ưu tiên; nếu chỉ có giá tổng thì chia cho số lượng. Luôn tròn tới hàng đơn vị. */
    private BigDecimal resolveUnitCost(SetLotPriceRequest req, BigDecimal qty) {
        if (req == null)
            throw new BusinessException("Vui lòng nhập giá vốn");

        if (req.getUnitPrice() != null && req.getUnitPrice().compareTo(BigDecimal.ZERO) > 0)
            return req.getUnitPrice().setScale(0, RoundingMode.HALF_UP);

        if (req.getTotalPrice() != null && req.getTotalPrice().compareTo(BigDecimal.ZERO) > 0)
            return req.getTotalPrice().divide(qty, 0, RoundingMode.HALF_UP);

        throw new BusinessException("Giá vốn phải lớn hơn 0");
    }

    private BigDecimal recalcTotalCostValue(Long warehouseId, Long ingredientId) {
        return expiryRepository.findByWarehouseIdAndIngredientId(warehouseId, ingredientId)
                .stream()
                .map(e -> {
                    BigDecimal q = e.getQuantity() != null ? e.getQuantity() : BigDecimal.ZERO;
                    BigDecimal c = e.getCostPrice() != null ? e.getCostPrice() : BigDecimal.ZERO;
                    return q.multiply(c);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveName(User u) {
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    private Map<String, Object> toMap(LotPricingRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("lotId", r.getIngredientExpiryId());
        m.put("warehouseId", r.getWarehouseId());
        m.put("warehouseName", r.getWarehouseName());
        m.put("ingredientId", r.getIngredientId());
        m.put("ingredientName", r.getIngredientName());
        m.put("unit", r.getIngredientUnit());
        m.put("quantity", r.getQuantity());
        m.put("expiryDate", r.getExpiryDate());
        m.put("receiptCode", r.getReceiptCode());
        m.put("requestedByName", r.getRequestedByName());
        m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        m.put("unitCost", r.getUnitCost());
        m.put("totalCost", r.getTotalCost());
        m.put("pricedByName", r.getPricedByName());
        m.put("createdAt", r.getCreatedAt());
        m.put("pricedAt", r.getPricedAt());
        return m;
    }
}
