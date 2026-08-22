package com.nhatnam.server.repository;

import com.nhatnam.server.entity.EmployeeRequest;
import com.nhatnam.server.enumtype.EmployeeRequestStatus;
import com.nhatnam.server.enumtype.PayrollDepartment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Truy vấn đơn nhân viên.
 *
 * <p><b>Nguyên tắc xuyên suốt:</b> mọi truy vấn phục vụ TÍNH LƯƠNG đều lọc theo
 * KHOẢNG NGÀY HIỆU LỰC chồng lấn với kỳ lương, KHÔNG lọc theo {@code createdAt}.
 * Điều kiện chồng lấn chuẩn là {@code fromDate <= periodEnd AND toDate >= periodStart}
 * — nhờ vậy đơn tạo ngày 29/5 xin nghỉ 3/6–4/6 vẫn rơi đúng vào kỳ tháng 6, và
 * đơn vắt qua hai tháng được tính cho cả hai kỳ (mỗi kỳ chỉ nhận phần ngày của mình).
 */
@Repository
public interface EmployeeRequestRepository extends JpaRepository<EmployeeRequest, Long> {

    // ══════════════════════════════════════════════════════════════════════════
    // PHỤC VỤ TÍNH LƯƠNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Đơn ĐÃ DUYỆT (mọi nhánh duyệt) của MỘT BỘ PHẬN chồng lấn kỳ lương.
     * Dùng khi import bảng chấm công để dựng khung giờ chuẩn cho từng nhân viên.
     */
    @Query("""
           SELECT r FROM EmployeeRequest r
             JOIN FETCH r.user u
            WHERE r.department = :dept
              AND r.status IN :statuses
              AND r.fromDate <= :periodEnd
              AND r.toDate   >= :periodStart
            ORDER BY r.fromDate ASC, r.id ASC
           """)
    List<EmployeeRequest> findEffectiveForPeriod(@Param("dept") PayrollDepartment dept,
                                                 @Param("periodStart") LocalDate periodStart,
                                                 @Param("periodEnd") LocalDate periodEnd,
                                                 @Param("statuses") List<EmployeeRequestStatus> statuses);

    /** Đơn đã duyệt của MỘT NHÂN VIÊN chồng lấn kỳ — dùng khi xem lại phiếu lương cá nhân. */
    @Query("""
           SELECT r FROM EmployeeRequest r
            WHERE r.user.id = :userId
              AND r.status IN :statuses
              AND r.fromDate <= :periodEnd
              AND r.toDate   >= :periodStart
            ORDER BY r.fromDate ASC, r.id ASC
           """)
    List<EmployeeRequest> findEffectiveForUserPeriod(@Param("userId") Long userId,
                                                     @Param("periodStart") LocalDate periodStart,
                                                     @Param("periodEnd") LocalDate periodEnd,
                                                     @Param("statuses") List<EmployeeRequestStatus> statuses);

    // ══════════════════════════════════════════════════════════════════════════
    // PANEL DUYỆT CỦA OWNER
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Danh sách đơn cho panel "Phiếu nghỉ" — lọc mềm theo bộ phận / trạng thái /
     * nhân viên. {@code userId} phục vụ nút "Duyệt nghỉ/OT" của từng dòng nhân
     * viên trên trang Nhân viên: cùng một truy vấn, chỉ hẹp lại về một người.
     * Truyền {@code null} vào tham số nào thì bỏ qua điều kiện đó, tránh phải
     * viết bốn biến thể query gần giống nhau.
     */
    @Query("""
           SELECT r FROM EmployeeRequest r
            WHERE (:dept   IS NULL OR r.department = :dept)
              AND (:status IS NULL OR r.status     = :status)
              AND (:userId IS NULL OR r.user.id    = :userId)
              AND (:from   IS NULL OR r.toDate    >= :from)
              AND (:to     IS NULL OR r.fromDate  <= :to)
            ORDER BY
              CASE WHEN r.status = com.nhatnam.server.enumtype.EmployeeRequestStatus.PENDING
                   THEN 0 ELSE 1 END ASC,
              r.fromDate DESC, r.id DESC
           """)
    Page<EmployeeRequest> search(@Param("dept") PayrollDepartment dept,
                                 @Param("status") EmployeeRequestStatus status,
                                 @Param("userId") Long userId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 Pageable pageable);

    /** Số đơn đang chờ duyệt — hiện badge đỏ trên tab "Phiếu nghỉ". */
    long countByStatus(EmployeeRequestStatus status);

    long countByDepartmentAndStatus(PayrollDepartment department, EmployeeRequestStatus status);

    // ══════════════════════════════════════════════════════════════════════════
    // TRANG CỦA NHÂN VIÊN
    // ══════════════════════════════════════════════════════════════════════════

    Page<EmployeeRequest> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * PHIẾU NGHỈ PHÉP ĐÃ DUYỆT của 1 nhân viên trong 1 NĂM — vừa là lịch sử nghỉ
     * phép, vừa là căn cứ tính số ngày đã dùng.
     *
     * <p>Gom theo {@code fromDate}: phiếu vắt qua giao thừa (28/12 → 03/01) được
     * tính trọn vào năm BẮT ĐẦU nghỉ. Chia đôi theo từng ngày thì số dư của cả hai
     * năm đều lẻ và không ai đối chiếu nổi.
     *
     * <p>Chỉ lấy trạng thái CÓ HIỆU LỰC — phiếu chờ duyệt hoặc bị từ chối không
     * được trừ quỹ.
     */
    @Query("""
            SELECT r FROM EmployeeRequest r
            WHERE r.user.id = :userId
              AND r.type = com.nhatnam.server.enumtype.EmployeeRequestType.LEAVE
              AND r.status IN (com.nhatnam.server.enumtype.EmployeeRequestStatus.APPROVED_PAID,
                               com.nhatnam.server.enumtype.EmployeeRequestStatus.APPROVED_UNPAID,
                               com.nhatnam.server.enumtype.EmployeeRequestStatus.APPROVED_DEDUCTED)
              AND YEAR(r.fromDate) = :year
            ORDER BY r.fromDate DESC
            """)
    List<EmployeeRequest> findApprovedLeavesOfYear(@Param("userId") Long userId,
                                                   @Param("year") int year);

    /**
     * Đơn CÙNG LOẠI còn hiệu lực đè lên khoảng ngày đang xin — dùng để chặn tạo
     * trùng. Đơn bị từ chối không tính là trùng vì nhân viên có quyền nộp lại.
     */
    @Query("""
           SELECT r FROM EmployeeRequest r
            WHERE r.user.id = :userId
              AND r.type    = :type
              AND r.status <> com.nhatnam.server.enumtype.EmployeeRequestStatus.REJECTED
              AND r.fromDate <= :to
              AND r.toDate   >= :from
           """)
    List<EmployeeRequest> findOverlapping(@Param("userId") Long userId,
                                          @Param("type") com.nhatnam.server.enumtype.EmployeeRequestType type,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);
}