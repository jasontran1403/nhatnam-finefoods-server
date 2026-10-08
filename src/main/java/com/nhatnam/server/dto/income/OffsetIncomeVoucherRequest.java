package com.nhatnam.server.dto.income;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Body cho POST /api/income-vouchers/{id}/offset — "Cấn trừ phần dư" của phiếu
 * nguồn sang 1 đơn khác CÙNG KHÁCH.
 *
 * <ul>
 *   <li>{@code targetOrderCode}: mã đơn muốn cấn trừ sang. BẮT BUỘC phải thuộc
 *       cùng khách hàng với các đơn của phiếu nguồn.</li>
 *   <li>{@code handling}: {@code FULL} = cấn trừ cả phần còn lại của đơn (hoặc
 *       hết phần dư, lấy số nhỏ hơn) — đơn tự chuyển COMPLETED nếu đủ;
 *       {@code PARTIAL} = cấn trừ số tiền {@code amount} do owner nhập.</li>
 *   <li>{@code amount}: chỉ dùng khi {@code handling = PARTIAL}. Phải ≤
 *       {@code min(phần dư còn lại, phần còn lại của đơn)}.</li>
 *   <li>{@code receiptNumber}: Số phiếu thu MỚI (bắt buộc). FE gợi ý bằng
 *       {@code GET /next-receipt-number}, owner có thể sửa.</li>
 * </ul>
 */
@Data
public class OffsetIncomeVoucherRequest {

    @NotBlank(message = "Mã đơn hàng là bắt buộc")
    private String targetOrderCode;

    /** FULL | PARTIAL */
    @NotBlank(message = "Phương án thu là bắt buộc (FULL | PARTIAL)")
    private String handling;

    /** Chỉ bắt buộc với PARTIAL. */
    private BigDecimal amount;

    @NotBlank(message = "Số phiếu thu mới là bắt buộc")
    private String receiptNumber;
}