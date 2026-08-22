package com.nhatnam.server.dto.pricing;

/**
 * DTOs cho trang "Tính giá" (SUPER_ACCOUNTANT). Gộp chung 1 file cho gọn.
 */
public final class PricingDtos {

    private PricingDtos() {}

    /** Tùy chọn nguyên liệu cho dropdown tìm kiếm. */
    public record IngredientOption(Long id, String name, String unit, String itemCode) {}

    /** Nhãn chi phí chung dùng lại. */
    public record CostLabel(Long id, String name) {}

    /** Body tạo nhãn chi phí. */
    public record CreateCostLabelRequest(String name) {}
}