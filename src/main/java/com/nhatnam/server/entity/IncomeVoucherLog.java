package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * NHẬT KÝ PHIẾU THU — mỗi lần tạo mới / chỉnh sửa phiếu thu ghi một dòng.
 *
 * <p>Cùng tinh thần với {@code OrderLog}: lưu vết ai làm gì lúc nào, để về sau
 * đối chiếu khi số tiền hoặc danh sách đơn của phiếu thay đổi.
 *
 * <p>{@link #actorRole} lấy từ role ĐANG ACTIVE trong JWT (không phải role chính),
 * vì một người có thể kiêm nhiều vai và thao tác dưới vai cụ thể.
 */
@Entity
@Table(name = "income_voucher_log",
        indexes = @Index(name = "idx_ivlog_voucher", columnList = "voucher_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeVoucherLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private IncomeVoucher voucher;

    /** CREATE = tạo mới, UPDATE = chỉnh sửa. */
    @Column(name = "action", length = 30, nullable = false)
    private String action;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    /** Role đang active lúc thao tác (ACCOUNTANT, OWNER…). */
    @Column(name = "actor_role", length = 40)
    private String actorRole;

    /** Nội dung mô tả thay đổi — xem các template ở IncomeVoucherServiceImpl. */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
