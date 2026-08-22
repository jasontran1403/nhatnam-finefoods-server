package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Cấu hình duyệt phiếu chi do OWNER quản lý (1 bản ghi duy nhất, id = 1).
 * <ul>
 *   <li>{@link #thresholdAmount} — ngưỡng số tiền: phiếu có TỔNG &lt; ngưỡng thì
 *       SUPER_ACCOUNTANT mới đủ điều kiện duyệt (mặc định 3.000.000).</li>
 *   <li>{@link #allowedCategories} — JSON array các KEY danh mục (VENDOR_TYPE_LABEL)
 *       mà SUPER_ACCOUNTANT được phép duyệt, VD: ["MATERIAL","ELECTRICITY"].</li>
 * </ul>
 * SUPER_ACCOUNTANT được duyệt ⇔ (tổng &lt; ngưỡng) VÀ (danh mục ∈ allowedCategories).
 */
@Entity
@Table(name = "expense_approval_config")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpenseApprovalConfig {

    @Id
    private Long id;   // luôn = 1

    @Column(name = "threshold_amount", nullable = false, precision = 15, scale = 0)
    @Builder.Default
    private BigDecimal thresholdAmount = new BigDecimal("3000000");

    /** JSON array các key danh mục cho phép, VD ["MATERIAL","ELECTRICITY"] */
    @Column(name = "allowed_categories", columnDefinition = "TEXT")
    private String allowedCategories;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "updated_by_name", length = 200)
    private String updatedByName;
}
