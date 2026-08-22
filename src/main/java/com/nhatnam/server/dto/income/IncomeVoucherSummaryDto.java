package com.nhatnam.server.dto.income;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * TỔNG HỢP phiếu thu theo ĐÚNG BỘ LỌC đang áp dụng (từ khoá + khoảng ngày).
 *
 * <p>Trước đây FE cộng {@code totalAmount} của các phiếu TRÊN TRANG HIỆN TẠI → số tổng
 * bị lệch (chỉ đúng khi kết quả vừa vặn 1 trang). DTO này được tính bằng SUM/COUNT
 * trên TOÀN BỘ kết quả khớp bộ lọc, độc lập hoàn toàn với phân trang.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeVoucherSummaryDto {

    /** Tổng số tiền của TẤT CẢ phiếu thu khớp bộ lọc (không chỉ trang hiện tại) */
    private BigDecimal totalAmount;

    /** Tổng SỐ phiếu thu khớp bộ lọc */
    private long totalCount;
}
