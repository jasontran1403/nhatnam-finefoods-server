package com.nhatnam.server.utils;

import java.util.Set;

/**
 * ĐƠN VỊ TÍNH NÀO CHO PHÉP NHẬP SỐ LẺ.
 *
 * <p>Hệ thống không có cột boolean riêng cho việc này; quy ước sẵn có là suy từ ĐƠN VỊ
 * TÍNH — cùng danh sách mà màn hình tạo mẻ sản xuất đang dùng
 * ({@code FactoryCreateBatchPage.DECIMAL_UNITS}). Gom về đây để hai nơi không trôi lệch:
 * chỉ cần một chỗ quên thêm "mét" là màn hình này cho nhập 1.5 còn màn hình kia chặn.
 *
 * <p>"bó", "cái", "thùng"… cố ý KHÔNG nằm trong danh sách: đó là đơn vị đếm nguyên,
 * không có nửa bó.
 */
public final class UnitDecimalRule {

    private UnitDecimalRule() {}

    /** Số chữ số tối đa sau dấu thập phân. */
    public static final int MAX_SCALE = 3;

    private static final Set<String> DECIMAL_UNITS = Set.of(
            "kg", "lít", "lit", "l", "liter", "litre", "mét", "met", "m");

    public static boolean allowsDecimal(String unit) {
        if (unit == null || unit.isBlank()) return false;
        return DECIMAL_UNITS.contains(unit.toLowerCase().trim());
    }
}
