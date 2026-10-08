package com.nhatnam.server.common;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * Ném ra khi kho THẬT SỰ không đủ hàng (không phải false conflict).
 *
 * <p>Chứa đầy đủ thông tin để FE render inline error tại field ingredient bị
 * thiếu — không cần reload trang.
 *
 * <p>Handler ở {@code GlobalExceptionHandler} sẽ convert thành HTTP 200 với
 * body {@code ApiResponse.error(OUT_OF_STOCK, detail, message)}.
 */
@Getter
public class InsufficientStockException extends BusinessException {

    private final Long ingredientId;
    private final Long warehouseId;
    private final String ingredientName;
    private final String warehouseName;
    private final String unit;
    private final BigDecimal onHand;      // tồn tổng
    private final BigDecimal held;        // POS đang giữ giỏ
    private final BigDecimal available;   // = onHand - held
    private final BigDecimal needed;      // request cần

    public InsufficientStockException(Long ingredientId, Long warehouseId,
                                      String ingredientName, String warehouseName,
                                      String unit,
                                      BigDecimal onHand, BigDecimal held,
                                      BigDecimal available, BigDecimal needed) {
        super(buildMessage(ingredientName, warehouseName, unit, available, held, needed));
        this.ingredientId = ingredientId;
        this.warehouseId = warehouseId;
        this.ingredientName = ingredientName;
        this.warehouseName = warehouseName;
        this.unit = unit;
        this.onHand = onHand;
        this.held = held;
        this.available = available;
        this.needed = needed;
    }

    /** Constructor rút gọn khi không có thông tin held (chỉ dùng cho các flow không phải POS). */
    public InsufficientStockException(Long ingredientId, Long warehouseId,
                                      String ingredientName, String warehouseName,
                                      String unit,
                                      BigDecimal available, BigDecimal needed) {
        this(ingredientId, warehouseId, ingredientName, warehouseName, unit,
                available, BigDecimal.ZERO, available, needed);
    }

    private static String buildMessage(String ingredientName, String warehouseName,
                                       String unit, BigDecimal available,
                                       BigDecimal held, BigDecimal needed) {
        String u = unit != null ? unit : "";
        String heldPart = "";
        if (held != null && held.compareTo(BigDecimal.ZERO) > 0) {
            heldPart = String.format(" (đang có %s%s trong giỏ POS)", held.toPlainString(), u);
        }
        return String.format(
                "Không đủ tồn kho '%s' tại kho '%s' (còn: %s%s, cần: %s%s)%s",
                ingredientName != null ? ingredientName : "?",
                warehouseName != null ? warehouseName : "?",
                available != null ? available.toPlainString() : "0", u,
                needed != null ? needed.toPlainString() : "0", u,
                heldPart);
    }
}