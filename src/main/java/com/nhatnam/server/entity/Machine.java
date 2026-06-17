package com.nhatnam.server.entity;

import jakarta.persistence.*;
import com.nhatnam.server.entity.ProductionFactory;
import lombok.*;
import java.math.BigDecimal;

/**
 * Máy móc / dây chuyền sản xuất.
 */
@Entity
@Table(name = "machine")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Machine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Công suất tối đa giờ/tháng */
    @Column(name = "capacity_hours_per_month", nullable = false, precision = 8, scale = 2)
    private BigDecimal capacityHoursPerMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MachineStatus status = MachineStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Ngày mua / đưa vào sử dụng (epoch ms) */
    @Column(name = "purchase_date")
    private Long purchaseDate;

    /** Chi phí mua máy */
    @Column(name = "purchase_cost", precision = 15, scale = 2)
    private BigDecimal purchaseCost;

    /** Nhà cung cấp / nhà sản xuất máy */
    @Column(name = "manufacturer", length = 200)
    private String manufacturer;

    /** Số serial máy */
    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    /** Xưởng sản xuất mà máy thuộc về */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id")
    private ProductionFactory factory;

    @Column(name = "factory_name", length = 200)
    private String factoryName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum MachineStatus { ACTIVE, INACTIVE, UNDER_MAINTENANCE }
}
