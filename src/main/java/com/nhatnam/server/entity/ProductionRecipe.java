package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Biến thể sản xuất (công thức định lượng) cho một thành phẩm (FactoryProduct).
 * Do FACTORY_WORKER tạo/sửa — mỗi FactoryProduct có thể có nhiều biến thể,
 * mỗi biến thể có 1 định lượng thành phẩm chuẩn cố định (standardOutputQty)
 * cùng nguyên liệu (items) và các bước xử lý (steps) riêng.
 *
 * Khi lập phương án cho lệnh sản xuất, nhân viên chọn 1 biến thể phù hợp;
 * nguyên liệu và số mẻ sẽ được tính lại theo sản lượng yêu cầu của lệnh
 * (xem ProductionBatchPlanningService).
 *
 * Ví dụ: "Xúc xích biến thể 1" → chuẩn 30kg xúc xích
 *         từ: 30kg thịt nạc vai, 5 túi gia vị, 1kg đường, 2.5kg muối
 *         qua 5 bước: Rửa thịt, Xay thịt, Nhồi ruột, Luộc xúc xích, Đóng gói.
 */
@Entity
@Table(name = "production_recipe",
        uniqueConstraints = @UniqueConstraint(name = "uk_recipe_product_name",
                columnNames = {"factory_product_id", "name"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductionRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_product_id", nullable = false)
    private FactoryProduct factoryProduct;

    /** Tên công thức (có thể có nhiều công thức cho 1 thành phẩm) */
    @Column(nullable = false, length = 200)
    private String name;

    /** Sản lượng chuẩn đầu ra (VD: 30) */
    @Column(name = "standard_output_qty", nullable = false, precision = 10, scale = 3)
    private BigDecimal standardOutputQty;

    /** Đơn vị đầu ra (lấy từ FactoryProduct nhưng lưu snapshot) */
    @Column(name = "output_unit", nullable = false, length = 50)
    private String outputUnit;

    /**
     * Định lượng đóng gói chuẩn (VD: 0.5 kg/túi). Dùng để ƯỚC TÍNH số gói dự kiến
     * khi lập kế hoạch/xem báo cáo — KHÔNG dùng để tính hao hụt thực tế (hao hụt
     * luôn lấy từ số liệu cân thật khi kế toán kho xác nhận nhận hàng).
     * Nullable — nếu không cấu hình, các trang liên quan chỉ ẩn phần ước tính số gói.
     */
    @Column(name = "packaging_qty", precision = 10, scale = 3)
    private BigDecimal packagingQty;

    /** Đơn vị đóng gói (VD: "túi", "hộp") */
    @Column(name = "packaging_unit", length = 50)
    private String packagingUnit;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Builder.Default
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionRecipeItem> items = new ArrayList<>();

    /** Các bước xử lý của biến thể này (thứ tự theo sortOrder) */
    @Builder.Default
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionRecipeStep> steps = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
