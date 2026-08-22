package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nhãn chi phí chung dùng lại cho trang "Tính giá" (SUPER_ACCOUNTANT).
 * Chỉ lưu label để chọn lại; số tiền KHÔNG lưu (nhập khác nhau mỗi lần tính).
 */
@Entity
@Table(name = "pricing_cost_labels",
        uniqueConstraints = @UniqueConstraint(name = "uk_pricing_cost_label_name", columnNames = "name"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingCostLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Long createdAt;
}