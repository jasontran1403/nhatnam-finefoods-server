package com.nhatnam.server.dto.request;

import lombok.Data;

/**
 * Request body cho SUPER_SELLER hủy đơn hàng — cho phép hủy ở MỌI trạng thái
 * (kể cả đã thanh toán / đã giao / đã hoàn thành), khác với cancelOrder thông
 * thường (chỉ hủy được đơn chưa hoàn thành).
 * Bắt buộc phải có requestedBy (người yêu cầu hủy) + cancelReason (lý do hủy).
 */
@Data
public class SuperSellerCancelOrderRequest {

    /** ID nhân viên yêu cầu hủy đơn (lấy từ dropdown search nhân viên) */
    private Long requestedById;

    /** Tên nhân viên yêu cầu hủy đơn (hiển thị, dùng cho log) */
    private String requestedBy;

    /** Role của người yêu cầu hủy (nếu nhân viên có nhiều role) */
    private String requestedByRole;

    /** Lý do hủy đơn */
    private String cancelReason;
}
