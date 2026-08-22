package com.nhatnam.server.dto.cashflow;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Body modal xác nhận: số tiền mặt + số dư mỗi TK (key = tên ngân hàng). */
@Data
public class ConfirmCashflowRequest {

    /**
     * Tổng tiền mặt kiểm đếm.
     *
     * <p>CHỈ dùng khi {@link #cashDenominations} rỗng/null (luồng cũ — nhập tổng trực tiếp).
     * Nếu có {@code cashDenominations}, server BỎ QUA giá trị này và tự tính lại
     * {@code Σ(mệnh giá × số lượng)} — số tổng do client gửi không đáng tin.
     */
    private BigDecimal cashCounted;

    /**
     * Kiểm đếm tiền mặt theo MỆNH GIÁ (200đ → 500.000đ).
     * FE gửi {@code denomination} + {@code quantity}; {@code amount} bỏ trống.
     * Mệnh giá không nằm trong {@code CashDenominations.ALLOWED} → 400.
     */
    private List<CashDenominationDto> cashDenominations;

    private Map<String, BigDecimal> bankBalances;

    /** Bắt buộc khi có sai lệch. */
    private String reason;
}
