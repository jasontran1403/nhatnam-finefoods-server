package com.nhatnam.server.dto.cashflow;

import java.util.List;

/**
 * Bộ mệnh giá tiền mặt VNĐ đang lưu hành — dùng cho UI kiểm đếm quỹ tiền mặt
 * ở màn hình Xác nhận dòng tiền (OWNER/ADMIN).
 *
 * <p>Sắp xếp GIẢM DẦN để FE render đúng thứ tự đếm thực tế (tờ to trước).
 */
public final class CashDenominations {

    public static final List<Long> ALLOWED = List.of(
            500_000L, 200_000L, 100_000L, 50_000L, 20_000L, 10_000L,
            5_000L, 2_000L, 1_000L, 500L, 200L
    );

    public static boolean isValid(Long d) {
        return d != null && ALLOWED.contains(d);
    }

    private CashDenominations() {}
}
