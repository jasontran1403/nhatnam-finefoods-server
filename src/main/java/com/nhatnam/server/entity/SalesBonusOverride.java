package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * OVERRIDE THƯỞNG DOANH THU CỦA 1 NHÂN VIÊN SALES — per month.
 *
 * <p>Khi OWNER bấm "Tính thưởng" → hệ thống tạo row với giá trị default
 * (stepUnitPrice từ sheet × multiplier; remainderBonus = 0).
 * Modal review cho phép OWNER override 2 field này per-seller trước khi Public.
 *
 * <p>Lưu per (user, month, year) — tháng sau tính lại không auto-điền từ tháng cũ,
 * mỗi tháng độc lập (theo yêu cầu 10/2026).
 */
@Data @Builder
@NoArgsConstructor @AllArgsConstructor
@Entity
@Table(name = "sales_bonus_override",
        uniqueConstraints = @UniqueConstraint(name = "uk_sales_override_user_month",
                columnNames = {"user_id", "month", "year"}))
public class SalesBonusOverride {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    /** Doanh thu seller (snapshot — ex-VAT, đã trừ discount). */
    @Column(name = "revenue", nullable = false)
    @Builder.Default
    private Long revenue = 0L;

    /** Số bước full (= floor(revenue / stepAmount)). Snapshot để UI đọc nhanh. */
    @Column(name = "steps", nullable = false)
    @Builder.Default
    private Integer steps = 0;

    /** Phần dư (= revenue - steps × stepAmount). Snapshot. */
    @Column(name = "remainder", nullable = false)
    @Builder.Default
    private Long remainder = 0L;

    /**
     * Đơn giá/bước (có thể OWNER override).
     * Default = sheet.salesBonusStepUnitPrice × sheet.salesBonusMultiplier.
     */
    @Column(name = "step_unit_price", nullable = false)
    @Builder.Default
    private Long stepUnitPrice = 0L;

    /** Thưởng cho phần dư (default 0, có thể OWNER override, chỉ số nguyên). */
    @Column(name = "remainder_bonus", nullable = false)
    @Builder.Default
    private Long remainderBonus = 0L;

    /** Tổng thưởng = steps × stepUnitPrice + remainderBonus. Precompute cho query nhanh. */
    @Column(name = "total_bonus", nullable = false)
    @Builder.Default
    private Long totalBonus = 0L;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /** OWNER đã sửa so với default chưa (để FE hiện badge "đã override"). */
    @Column(name = "owner_edited", nullable = false)
    @Builder.Default
    private Boolean ownerEdited = false;

    // ─── (10/2026) Target cá nhân + achieved percent snapshot ──────────────
    /** Target doanh thu cá nhân (snapshot tại thời điểm publish). null = không áp dụng (support staff). */
    @Column(name = "personal_target")
    private Long personalTarget;

    /** % đạt (= revenue/personalTarget*100). null = không áp dụng. */
    @Column(name = "achieved_percent")
    private Double achievedPercent;

    /** Multiplier áp dụng cho seller này (= tier.pricePercent/100). Snapshot. */
    @Column(name = "applied_multiplier")
    private Double appliedMultiplier;

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        recomputeTotal();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = System.currentTimeMillis();
        recomputeTotal();
    }

    private void recomputeTotal() {
        long s = stepUnitPrice != null ? stepUnitPrice : 0L;
        int  n = steps != null ? steps : 0;
        long r = remainderBonus != null ? remainderBonus : 0L;
        totalBonus = (long) n * s + r;
    }
}