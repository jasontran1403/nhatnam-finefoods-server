package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * NHẬT KÝ PHIẾU CHI — mỗi thao tác thay đổi trạng thái / nội dung phiếu ghi một dòng.
 *
 * <p>Cùng tinh thần với {@code OrderLog} và {@code IncomeVoucherLog}: lưu vết ai làm gì
 * lúc nào, để về sau đối chiếu khi phiếu bị mở lại hoặc số tiền thay đổi.
 *
 * <p>{@link #actorRole} lấy từ vai trò ĐANG ACTIVE trong JWT (không phải vai trò chính
 * trong DB), vì một người có thể kiêm nhiều vai và thao tác dưới một vai cụ thể —
 * đây chính là thông tin cần cho yêu cầu "ghi lại role đã chuyển phiếu về chờ duyệt".
 */
@Entity
@Table(name = "expense_voucher_log",
        indexes = @Index(name = "idx_evlog_voucher", columnList = "voucher_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseVoucherLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private ExpenseVoucher voucher;

    /**
     * Loại thao tác:
     * <ul>
     *   <li>{@code APPROVED} — duyệt phiếu</li>
     *   <li>{@code REJECTED} — từ chối phiếu</li>
     *   <li>{@code REOPENED} — chuyển phiếu đã duyệt/đã từ chối về lại CHỜ DUYỆT</li>
     *   <li>{@code ITEMS_UPDATED} — sửa danh sách khoản chi</li>
     *   <li>{@code REASON_UPDATED} — sửa lý do chi</li>
     * </ul>
     */
    @Column(name = "action", length = 30, nullable = false)
    private String action;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    /** Vai trò đang active lúc thao tác (OWNER, ADMIN, SUPER_ACCOUNTANT…). */
    @Column(name = "actor_role", length = 40)
    private String actorRole;

    /** Trạng thái phiếu TRƯỚC thao tác (null nếu không đổi trạng thái). */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    /** Trạng thái phiếu SAU thao tác (null nếu không đổi trạng thái). */
    @Column(name = "to_status", length = 20)
    private String toStatus;

    /** Mô tả chi tiết thay đổi / lý do. */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}