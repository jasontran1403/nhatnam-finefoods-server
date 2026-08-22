package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.PayrollDepartment;
import jakarta.persistence.*;
import lombok.*;

/**
 * BẢNG CHẤM CÔNG THÁNG — do OWNER upload file Excel lên server.
 *
 * <p><b>Mỗi THÁNG × BỘ PHẬN có 1 bảng</b> (unique month + year + department).
 * Trước đây mỗi tháng chỉ có 1 bảng dùng chung cho xưởng; nay mỗi bộ phận
 * (Xưởng sản xuất / Kinh doanh / Kho / Kế toán) có file riêng nên khoá unique
 * được mở rộng thêm cột {@code department}.
 *
 * <p>Bản ghi này quản lý CẢ 3 LOẠI FILE của tháng cho bộ phận đó, tất cả lưu
 * trong {@code <storage>/attendance/MM_YYYY/<DEPARTMENT>/}:
 * <ol>
 *   <li><b>Bảng chấm công</b> — xuất từ máy chấm công ({@code filePath})</li>
 *   <li><b>Lịch nghỉ / đi trễ / về sớm</b> ({@code exceptionFilePath})</li>
 *   <li><b>Đơn xin nghỉ của cá nhân</b> ({@code leaveFilePath})</li>
 * </ol>
 *
 * <h3>Trạng thái "Hoàn tất"</h3>
 * Nhân viên CHỈ nhìn thấy phiếu lương khi OWNER đã bấm <b>Hoàn tất</b>
 * ({@code finalized = true}) cho tháng + bộ phận của mình. Chưa hoàn tất thì
 * trang Quản lý lương hiển thị "Đang xử lý lương".
 *
 * <p>Hoàn tất rồi vẫn được XOÁ file và tải file khác lên — mỗi lần đổi file,
 * {@code finalized} tự động bị gỡ để OWNER kiểm tra lại rồi bấm Hoàn tất lần
 * nữa; lúc đó lương được tính theo file mới nhất.
 */
@Entity
@Table(
        name = "attendance_sheet",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attendance_sheet_month_year_dept",
                columnNames = {"month", "year", "department"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSheet {

    /** UPLOADED = đã upload nhưng chưa parse xong; PROCESSED = đã có dữ liệu ngày công. */
    public enum SheetStatus { UPLOADED, PROCESSED, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tháng chấm công (1-12) */
    @Column(name = "month", nullable = false)
    private Integer month;

    /** Năm chấm công */
    @Column(name = "year", nullable = false)
    private Integer year;

    /**
     * BỘ PHẬN của bảng chấm công này.
     * Dữ liệu cũ (trước khi tách bộ phận) được migration set = {@code FACTORY}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "department", nullable = false, length = 20)
    @Builder.Default
    private PayrollDepartment department = PayrollDepartment.FACTORY;

    // ══════════════════════════════════════════════════════════════════════
    // FILE 1 — BẢNG CHẤM CÔNG (xuất từ máy chấm công)
    // ══════════════════════════════════════════════════════════════════════

    /** Tên file gốc do owner upload */
    @Column(name = "file_name", length = 300)
    private String fileName;

    /** Đường dẫn file đã lưu trên server */
    @Column(name = "file_path", length = 500)
    private String filePath;

    // ══════════════════════════════════════════════════════════════════════
    // FILE 2 — LỊCH NGHỈ / ĐI TRỄ / VỀ SỚM
    // ══════════════════════════════════════════════════════════════════════

    @Column(name = "exception_file_name", length = 300)
    private String exceptionFileName;

    @Column(name = "exception_file_path", length = 500)
    private String exceptionFilePath;

    @Column(name = "exception_uploaded_at")
    private Long exceptionUploadedAt;

    /** Số dòng ngoại lệ đọc được từ file */
    @Column(name = "exception_rows")
    @Builder.Default
    private Integer exceptionRows = 0;

    // ══════════════════════════════════════════════════════════════════════
    // FILE 3 — ĐƠN XIN ĐI TRỄ / VỀ SỚM / NGHỈ PHÉP CỦA CÁ NHÂN
    // ══════════════════════════════════════════════════════════════════════

    @Column(name = "leave_file_name", length = 300)
    private String leaveFileName;

    @Column(name = "leave_file_path", length = 500)
    private String leaveFilePath;

    @Column(name = "leave_uploaded_at")
    private Long leaveUploadedAt;

    /** Số đơn xin nghỉ đọc được từ file */
    @Column(name = "leave_rows")
    @Builder.Default
    private Integer leaveRows = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SheetStatus status = SheetStatus.UPLOADED;

    /** Số dòng nhân viên parse được từ file */
    @Column(name = "parsed_rows")
    @Builder.Default
    private Integer parsedRows = 0;

    /** Ghi chú / thông báo lỗi khi parse */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Số ngày công CHUẨN của tháng (dùng để quy đổi lương theo ngày công). */
    @Column(name = "standard_days")
    private Double standardDays;

    // ══════════════════════════════════════════════════════════════════════
    // CẤU HÌNH LƯƠNG TÀI XẾ (chỉ dùng cho department = DRIVER)
    // ══════════════════════════════════════════════════════════════════════
    /**
     * GIÁ XĂNG (đồng / km) OWNER nhập vào ngày tính lương.
     * Chỉ áp dụng cho tài xế xe máy (xe tải hưởng lương cứng, không tính theo km).
     */
    @Column(name = "driver_gas_price")
    private Long driverGasPrice;

    /**
     * ĐƠN GIÁ THƯỞNG XE MÁY (đồng / lượt giao).
     * Thưởng xe máy = tổng số LƯỢT giao xe máy trong tháng × đơn giá này.
     * (Cột gốc là {@code driver_bonus_unit_price}; giữ tên cột để tương thích dữ liệu cũ.)
     */
    @Column(name = "driver_bonus_unit_price")
    private Long driverBonusUnitPrice;

    /**
     * ĐƠN GIÁ THƯỞNG XE TẢI (đồng / lượt giao).
     * Xe tải có lương cứng riêng (không tính tiền xăng) nhưng vẫn có thưởng theo lượt
     * và OWNER thường trả mức khác với xe máy → tách riêng ô nhập.
     */
    @Column(name = "driver_truck_bonus_unit_price")
    private Long driverTruckBonusUnitPrice;

    // ══════════════════════════════════════════════════════════════════════
    // HOÀN TẤT XỬ LÝ LƯƠNG
    // ══════════════════════════════════════════════════════════════════════

    /**
     * OWNER đã bấm "Hoàn tất" cho tháng + bộ phận này chưa.
     * FALSE → nhân viên thấy "Đang xử lý lương".
     */
    @Column(name = "finalized", nullable = false)
    @Builder.Default
    private boolean finalized = false;

    @Column(name = "finalized_at")
    private Long finalizedAt;

    @Column(name = "finalized_by_name", length = 200)
    private String finalizedByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "uploaded_by_name", length = 200)
    private String uploadedByName;

    @Column(name = "uploaded_at")
    private Long uploadedAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /** Gỡ trạng thái hoàn tất — gọi mỗi khi file của tháng bị thay đổi. */
    public void unfinalize() {
        this.finalized = false;
        this.finalizedAt = null;
        this.finalizedByName = null;
    }

    @PrePersist
    void onCreate() {
        long now = System.currentTimeMillis();
        if (uploadedAt == null) uploadedAt = now;
        if (department == null) department = PayrollDepartment.FACTORY;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = System.currentTimeMillis(); }
}