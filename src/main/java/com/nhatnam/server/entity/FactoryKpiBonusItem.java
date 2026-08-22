package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.Role;
import jakarta.persistence.*;
import lombok.*;

/**
 * Dòng chia thưởng KPI cho 1 nhân viên xưởng trong 1 {@link FactoryKpiBonus}.
 * Lưu snapshot đầy đủ để phiếu lương tháng cũ không đổi số khi role/lương
 * nhân viên thay đổi về sau.
 */
@Entity
@Table(
        name = "factory_kpi_bonus_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_kpi_bonus_item_bonus_user",
                columnNames = {"kpi_bonus_id", "user_id"}
        ),
        indexes = @Index(name = "idx_kpi_bonus_item_user", columnList = "user_id")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryKpiBonusItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kpi_bonus_id", nullable = false)
    private FactoryKpiBonus kpiBonus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "user_full_name", length = 200)
    private String userFullName;

    /** Role dùng để tính trọng số (snapshot) */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 50)
    private Role role;

    /** Nhãn hiển thị vị trí (VD: "Trợ lý kho") */
    @Column(name = "role_label", length = 100)
    private String roleLabel;

    /** Trọng số áp dụng (1.00 / 1.10 / 1.25). Bảo vệ = 0 vì hưởng mức cố định. */
    @Column(name = "weight")
    @Builder.Default
    private Double weight = 0.0;

    /** TRUE nếu là bảo vệ xưởng — hưởng mức cố định, không chia theo trọng số. */
    @Column(name = "fixed_amount_role", nullable = false)
    @Builder.Default
    private Boolean fixedAmountRole = false;

    /** Số tiền TRƯỚC khi làm tròn (để đối chiếu) */
    @Column(name = "raw_amount")
    @Builder.Default
    private Long rawAmount = 0L;

    /** Số tiền thưởng KPI thực nhận (đã làm tròn xuống hàng trăm nghìn) */
    @Column(name = "amount")
    @Builder.Default
    private Long amount = 0L;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
