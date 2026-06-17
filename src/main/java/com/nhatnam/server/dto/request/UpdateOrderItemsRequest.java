package com.nhatnam.server.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdateOrderItemsRequest {

    // ── Thông tin đơn hàng ────────────────────────────────────────────────
    private String     orderedByName;
    private String     receiverName;
    private String     deliveryAddress;
    private Long       deliveryDatetime;
    private String     paymentMethod;
    private String     notes;

    // ── Khách hàng (tuỳ chọn — nếu null thì giữ nguyên KH cũ) ───────────
    private Long       customerId;

    // ── Bill ──────────────────────────────────────────────────────────────
    private BigDecimal discountAmount;
    private Integer    discountRate;
    private BigDecimal surcharge;
    private Boolean    showPrices;
    private Boolean    hideAllPrices;

    // ── Items ─────────────────────────────────────────────────────────────
    private List<CreateOrderRequest.OrderItemRequest> items;

    private List<CreateOrderRequest.SurchargeItem> surchargeItems;
}