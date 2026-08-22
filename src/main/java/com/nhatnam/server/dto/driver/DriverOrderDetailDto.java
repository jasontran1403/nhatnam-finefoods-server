package com.nhatnam.server.dto.driver;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Một đơn hàng tài xế đã giao — dùng cho popup "Xem chi tiết". */
@Data
@Builder
public class DriverOrderDetailDto {
    private Long orderId;
    private String orderCode;
    private String customerName;
    /** Địa chỉ giao — thứ OWNER/ADMIN cần thấy nhất khi đối chiếu quãng đường. */
    private String deliveryAddress;
    private String status;
    private BigDecimal finalAmount;
    /** Mốc thời gian dùng để xếp đơn vào khoảng ngày (giao hàng, fallback ngày tạo). */
    private Long deliveredAt;
    /** Ngày dạng yyyy-MM-dd của deliveredAt, để nhóm theo ngày ở giao diện. */
    private String deliveryDate;
    /** Số chuyến tài xế này chạy cho đơn (lấy từ deliveryInfo.trips, mặc định 1). */
    private Integer trips;
}