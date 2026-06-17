package com.nhatnam.server.enumtype;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

@Getter
public enum VatRate {
    ZERO(0, "0%"),
    FIVE(5, "5%"),
    EIGHT(8, "8%"),
    TEN(10, "10%");

    private final int percentage;
    private final String displayName;

    VatRate(int percentage, String displayName) {
        this.percentage = percentage;
        this.displayName = displayName;
    }

    /**
     * Lấy tỷ lệ VAT dưới dạng BigDecimal (ví dụ: 0.08 cho 8%)
     */
    public BigDecimal getRate() {
        return BigDecimal.valueOf(percentage).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    /**
     * Lấy hệ số nhân (1 + VAT rate) → dùng khi tính giá cuối cùng
     * Ví dụ: 8% → 1.08
     */
    public BigDecimal getMultiplier() {
        return BigDecimal.ONE.add(getRate());
    }

    /**
     * Lấy phần trăm dưới dạng int (0, 5, 8, 10)
     */
    public int getPercentage() {
        return percentage;
    }

    /**
     * Chuyển từ int percentage (từ request) về enum
     * Nếu không tìm thấy → fallback về ZERO
     */
    public static VatRate fromPercentage(int percentage) {
        return Arrays.stream(values())
                .filter(v -> v.percentage == percentage)
                .findFirst()
                .orElse(ZERO);
    }

    /**
     * Chuyển từ String (ví dụ "8%") về enum
     * Dùng khi cần parse từ UI hoặc JSON
     */
    public static VatRate fromDisplayName(String display) {
        if (display == null) return ZERO;
        String clean = display.replace("%", "").trim();
        try {
            int percent = Integer.parseInt(clean);
            return fromPercentage(percent);
        } catch (NumberFormatException e) {
            return ZERO;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}