package com.nhatnam.server.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body cho SUPER_SELLER sửa đơn hàng.
 * Cho phép sửa mọi trạng thái trừ CANCELLED.
 * Bắt buộc phải có requestedBy + editReason.
 */
@Data
public class SuperSellerUpdateOrderRequest {

    // ── Bắt buộc — audit trail ────────────────────────────────────────────
    /** Tên nhân viên yêu cầu sửa đơn */
    private String requestedBy;

    /** Lý do sửa đơn */
    private String editReason;

    // ── Thông tin người nhận ──────────────────────────────────────────────
    private String orderedByName;
    private String receiverName;
    private String deliveryAddress;
    private Long   deliveryDatetime;

    // ── Thông tin đơn hàng ────────────────────────────────────────────────
    private String     paymentMethod;
    private String     notes;

    // ── Giảm giá & phụ phí ───────────────────────────────────────────────
    private BigDecimal discountAmount;
    private Integer    discountRate;
    private BigDecimal surcharge;
    private List<CreateOrderRequest.SurchargeItem> surchargeItems;

    // ── Hiển thị giá ─────────────────────────────────────────────────────
    private Boolean showPrices;
    private Boolean hideAllPrices;

    // ── Khách hàng ────────────────────────────────────────────────────────
    private Long customerId;

    // ── Items ─────────────────────────────────────────────────────────────
    private List<CreateOrderRequest.OrderItemRequest> items;

    // ── Audit ─────────────────────────────────────────────────────────────────
    /** Role của người yêu cầu sửa (FE gửi sau khi user chọn) */
    private String requestedByRole;

    // ── Người giao hàng ───────────────────────────────────────────────────────
    /** Array [{name, type, trips}] — null = không thay đổi */
    private List<java.util.Map<String, Object>> deliveryInfo;
}