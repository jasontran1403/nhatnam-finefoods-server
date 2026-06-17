package com.nhatnam.server.common;

import lombok.Getter;
import java.math.BigDecimal;

/**
 * Ném ra khi số tiền còn lại của đơn hàng đã thay đổi
 * so với giá trị client đang thấy (race condition).
 */
@Getter
public class StaleOrderDataException extends RuntimeException {

    private final String orderCode;
    private final BigDecimal actualRemainingAmount;
    private final BigDecimal paidAmount;

    public StaleOrderDataException(String orderCode,
                                   BigDecimal actualRemainingAmount,
                                   BigDecimal paidAmount) {
        super("Đơn " + orderCode + " đã thay đổi, vui lòng tải lại dữ liệu mới");
        this.orderCode = orderCode;
        this.actualRemainingAmount = actualRemainingAmount;
        this.paidAmount = paidAmount;
    }
}
