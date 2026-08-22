package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Một lần TÍNH LƯƠNG cho 1 tháng. Quy trình:
 * 1. HR/SUPER_ACCOUNTANT bấm "Tạo phiếu lương" → tạo batch DRAFT, export Excel
 *    danh sách nhân viên đã được duyệt lương cơ bản (kèm token chống import nhầm).
 * 2. HR điền tay vào Excel: số ngày công thực tế, số người phụ thuộc, thưởng,
 *    phụ cấp, lương đóng BHXH/BHYT/BHTN (nếu khác lương cơ bản), lương đóng thuế.
 * 3. Import lại file → server tính BHXH/BHYT/BHTN (10.5%) + thuế TNCN lũy tiến
 *    5 bậc (2026) cho từng người → tạo các {@link Payslip} con, batch chuyển
 *    sang PENDING_APPROVAL.
 * 4. Owner duyệt toàn bộ batch 1 lần (APPROVE_ALL) → batch chuyển APPROVED.
 * 5. HR/SUPER_ACCOUNTANT tải file phiếu lương chi tiết (mỗi người 1 sheet/trang)
 *    về để xử lý chi lương.
 */
@Entity
@Table(name = "payroll_batch")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tháng tính lương (1-12) */
    private Integer month;

    /** Năm tính lương */
    private Integer year;

    /**
     * Trạng thái: DRAFT (vừa tạo, đã export, chưa import) |
     * PENDING_APPROVAL (đã import, đã tính lương, chờ Owner duyệt) |
     * APPROVED (Owner đã duyệt toàn bộ) | REJECTED
     */
    @Builder.Default
    private String status = "DRAFT";

    /** Token export (HMAC) — chỉ import được đúng 1 lần vào đúng file vừa export */
    @Column(length = 128)
    private String exportToken;

    /** Số lượng nhân viên tại thời điểm export — để đối chiếu khi import */
    private Integer employeeCount;

    /** Lý do từ chối (khi status = REJECTED) */
    @Column(columnDefinition = "TEXT")
    private String rejectReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    private Long createdAt;
    private Long updatedAt;
    private Long importedAt;
    private Long approvedAt;
}
