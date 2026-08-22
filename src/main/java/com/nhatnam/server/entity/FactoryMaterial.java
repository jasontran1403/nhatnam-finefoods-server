package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nguyên vật liệu xưởng sản xuất.
 * Tách biệt hoàn toàn với Ingredient (kho hàng).
 */
@Entity
@Table(name = "factory_material")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FactoryMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * Đơn vị LƯU KHO (đvt lưu kho) — kho lưu và tính tồn theo đơn vị này.
     * (Trước đây field này mang nghĩa "đơn vị tính" chung.)
     */
    @Column(nullable = false, length = 50)
    private String unit;

    /**
     * Đơn vị ĐẶT HÀNG (đvt đặt hàng) — có thể khác đơn vị lưu kho.
     * Ví dụ: đặt theo "Thùng" nhưng lưu kho theo "Chai".
     * Nullable: nếu null thì đặt/lưu cùng đơn vị.
     */
    @Column(name = "order_unit", length = 50)
    private String orderUnit;

    /**
     * Tỷ lệ quy đổi: 1 {orderUnit} = {conversionRatio} {unit}.
     * Khi nhận hàng nếu chọn đơn vị đặt hàng → cộng kho = qtyReceived * conversionRatio.
     */
    @Column(name = "conversion_ratio", precision = 12, scale = 4)
    private java.math.BigDecimal conversionRatio;

    /** Hạn sử dụng tính theo số NGÀY (nguyên, > 0). Dùng để gợi ý HSD lô khi nhận hàng. */
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    /** Số ngày NCC giao (dự kiến) — nguyên, > 0. Dùng tính ngày giao khi xác nhận đặt hàng. */
    @Column(name = "supplier_lead_days")
    private Integer supplierLeadDays;

    /** Điều kiện bảo quản (text). */
    @Column(name = "storage_condition", columnDefinition = "TEXT")
    private String storageCondition;

    /** Danh mục riêng (bắt buộc về mặt nghiệp vụ; nullable để tương thích dữ liệu cũ). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_category_id")
    private FactoryMaterialSubCategory subCategory;

    /**
     * Các xưởng mà nguyên liệu này tồn tại (có thể đặt/lưu kho).
     * Mặc định khi tạo = tất cả xưởng đang có. Xưởng không nằm trong danh sách này
     * thì nguyên liệu không xuất hiện ở xưởng đó.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "factory_material_factories",
            joinColumns = @JoinColumn(name = "material_id"),
            inverseJoinColumns = @JoinColumn(name = "factory_id")
    )
    @Builder.Default
    private java.util.List<ProductionFactory> factories = new java.util.ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Có thể là SẢN PHẨM ĐẦU RA của mix gia vị (Mục 4). Chỉ nguyên liệu isMixable=true
     * mới được chọn làm đầu ra khi trộn. Mặc định false.
     */
    @Builder.Default
    @Column(name = "is_mixable")
    private Boolean isMixable = false;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
