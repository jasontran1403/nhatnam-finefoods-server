package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.PayrollCalcStatus;
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
     *
     * <p>Sau khi hoàn tất lương, nhân viên thấy ngay <b>lương cơ bản + phụ cấp</b>.
     * KPI/bonus hiển thị riêng — pending cho đến khi OWNER bấm "Hoàn tất KPI".
     */
    @Column(name = "finalized", nullable = false)
    @Builder.Default
    private boolean finalized = false;

    @Column(name = "finalized_at")
    private Long finalizedAt;

    @Column(name = "finalized_by_name", length = 200)
    private String finalizedByName;

    /**
     * KPI/BONUS ĐÃ HOÀN TẤT chưa.
     *
     * <p>Tách biệt hoàn toàn khỏi {@link #finalized}:
     * <ul>
     *   <li>Hoàn tất lương → nhân viên thấy lương cơ bản, KPI hiển thị "Đang tính".</li>
     *   <li>Hoàn tất KPI → nhân viên thấy thêm KPI và bonus.</li>
     * </ul>
     *
     * <p>Với bộ phận Tài xế: đây là cờ xác nhận đã nhập đủ giá xăng + đơn giá
     * thưởng và OWNER đã chốt bonus cho tháng. Trước khi hoàn tất KPI, tài xế
     * chỉ thấy lương cứng; sau khi hoàn tất mới thấy thêm xăng + thưởng đơn hàng.
     *
     * <p>Với bộ phận Xưởng sản xuất: cờ này được set khi OWNER bấm "Hoàn tất KPI"
     * riêng sau khi đã tính xong thưởng KPI sản xuất.
     *
     * <p>Với Kế toán/Kinh doanh: set khi OWNER bấm "Hoàn tất KPI/Bonus" sau khi
     * chạy calcAccountingKpi/calcSalesKpi xong.
     */
    @Column(name = "kpi_finalized", nullable = false)
    @Builder.Default
    private boolean kpiFinalized = false;

    @Column(name = "kpi_finalized_at")
    private Long kpiFinalizedAt;

    @Column(name = "kpi_finalized_by_name", length = 200)
    private String kpiFinalizedByName;

    /**
     * THƯỞNG DOANH THU ĐÃ HOÀN TẤT chưa — chỉ áp dụng cho SALES và ACCOUNTING.
     *
     * <p>Tách biệt hoàn toàn khỏi {@link #kpiFinalized}:
     * <ul>
     *   <li>Hoàn tất KPI  → set kpiPercent (hiện = 100%), nhân viên thấy KPI.</li>
     *   <li>Hoàn tất Thưởng → tính doanh thu thực thu trong tháng → thưởng tiền,
     *       ghi vào {@link OfficeBonusResult}. Nhân viên thấy số tiền thưởng.</li>
     * </ul>
     *
     * <p>Khi mở lại, set về false; OfficeBonusResult KHÔNG bị xóa cho đến khi
     * OWNER bấm lại "Hoàn tất Thưởng" (lúc đó xóa và tính lại từ đầu).
     */
    @Column(name = "bonus_finalized", nullable = false)
    @Builder.Default
    private boolean bonusFinalized = false;

    @Column(name = "bonus_finalized_at")
    private Long bonusFinalizedAt;

    @Column(name = "bonus_finalized_by_name", length = 200)
    private String bonusFinalizedByName;

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — VÒNG ĐỜI MỚI: UPLOADED → CALCULATED → PUBLISHED
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Trạng thái vòng đời tính lương (Phase 2 refactor 10/2026).
     *
     * <p>Hệ thống cũ dùng cờ {@link #finalized} đơn lẻ. Vòng đời mới chia 3 bước
     * rõ ràng — xem {@link PayrollCalcStatus}. Hai field cùng tồn tại để tương
     * thích ngược: khi service mới set {@code calcStatus = PUBLISHED} thì cũng
     * set {@code finalized = true}, nên các query cũ dựa vào {@code finalized}
     * vẫn chạy đúng cho đến khi được loại bỏ ở Phase 3.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "calc_status", nullable = false, length = 20)
    @Builder.Default
    private PayrollCalcStatus calcStatus = PayrollCalcStatus.NONE;

    @Column(name = "calculated_at")
    private Long calculatedAt;

    @Column(name = "calculated_by_name", length = 200)
    private String calculatedByName;

    @Column(name = "published_at")
    private Long publishedAt;

    @Column(name = "published_by_name", length = 200)
    private String publishedByName;

    // ── TỔNG OT CẢ CÔNG TY TRONG THÁNG (dùng cho báo cáo + export) ──────────
    //   Mỗi field là tổng của toàn bộ attendance_entry cùng tháng. Lưu tại
    //   sheet để không phải aggregate lại từ hàng chục attendance_entry khi
    //   hiển thị overview trên FE.

    @Column(name = "ot_total_weekday_minutes")
    @Builder.Default
    private Long otTotalWeekdayMinutes = 0L;

    @Column(name = "ot_total_sunday_minutes")
    @Builder.Default
    private Long otTotalSundayMinutes = 0L;

    @Column(name = "ot_total_holiday_minutes")
    @Builder.Default
    private Long otTotalHolidayMinutes = 0L;

    @Column(name = "ot_total_amount")
    @Builder.Default
    private Long otTotalAmount = 0L;

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 4 — VÒNG ĐỜI KPI & THƯỞNG DOANH THU
    // ══════════════════════════════════════════════════════════════════════
    //
    // Phase 2 chỉ áp vòng đời UPLOADED/CALCULATED/PUBLISHED cho lương chính.
    // Phase 4 áp cùng pattern cho KPI xưởng (có bảng Thưởng KPI sản xuất) và
    // Thưởng DT văn phòng (Kinh doanh + Kế toán). Cả 2 vẫn chạy per-department
    // ở các dịch vụ FactoryKpiService / OfficeBonusService hiện có, nhưng vòng
    // đời được track trên 2 field dưới đây CHUNG CHO CẢ CÔNG TY, giúp FE hiển
    // thị nút Tính KPI/Public KPI theo cùng pattern với Tính lương/Public.
    //
    // Cờ boolean cũ (kpiFinalized, bonusFinalized) vẫn được giữ và sẽ được
    // set bằng helper tương tự markPublished — xem markKpiPublished bên dưới.

    @Enumerated(EnumType.STRING)
    @Column(name = "kpi_calc_status", nullable = false, length = 20)
    @Builder.Default
    private PayrollCalcStatus kpiCalcStatus = PayrollCalcStatus.NONE;

    @Column(name = "kpi_calculated_at")       private Long kpiCalculatedAt;
    @Column(name = "kpi_calculated_by_name")  private String kpiCalculatedByName;
    @Column(name = "kpi_published_at")        private Long kpiPublishedAt;
    @Column(name = "kpi_published_by_name")   private String kpiPublishedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "bonus_calc_status", nullable = false, length = 20)
    @Builder.Default
    private PayrollCalcStatus bonusCalcStatus = PayrollCalcStatus.NONE;

    @Column(name = "bonus_calculated_at")      private Long bonusCalculatedAt;
    @Column(name = "bonus_calculated_by_name") private String bonusCalculatedByName;
    @Column(name = "bonus_published_at")       private Long bonusPublishedAt;
    @Column(name = "bonus_published_by_name")  private String bonusPublishedByName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "uploaded_by_name", length = 200)
    private String uploadedByName;

    @Column(name = "uploaded_at")
    private Long uploadedAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /** Gỡ trạng thái hoàn tất LƯƠNG — gọi mỗi khi file của tháng bị thay đổi. */
    public void unfinalize() {
        this.finalized = false;
        this.finalizedAt = null;
        this.finalizedByName = null;
    }

    /** Gỡ trạng thái hoàn tất KPI/BONUS — gọi khi cần tính lại KPI. */
    public void unfinalizeKpi() {
        this.kpiFinalized = false;
        this.kpiFinalizedAt = null;
        this.kpiFinalizedByName = null;
    }

    /**
     * Gỡ trạng thái hoàn tất Thưởng — chỉ dùng cho SALES/ACCOUNTING.
     * Gọi khi OWNER muốn tính lại thưởng doanh thu.
     * OfficeBonusResult sẽ bị xóa và tính lại khi bấm "Hoàn tất Thưởng" tiếp.
     */
    public void unfinalizeBonus() {
        this.bonusFinalized = false;
        this.bonusFinalizedAt = null;
        this.bonusFinalizedByName = null;
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

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 2 — HELPERS CHO VÒNG ĐỜI MỚI
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Có file chấm công thật sự không? Dùng để chuyển {@code NONE → UPLOADED}
     * khi file đầu tiên được tải lên.
     */
    public boolean hasAttendanceFile() {
        return filePath != null && !filePath.isBlank();
    }

    /**
     * Ghi nhận đã tính lương. Chỉ set các field vòng đời; việc tính thật nằm ở
     * service. Đồng thời set {@link #finalized} = false vì "đã tính" KHÁC "đã
     * public" — nhân viên chưa thấy.
     */
    public void markCalculated(String byName) {
        this.calcStatus = PayrollCalcStatus.CALCULATED;
        this.calculatedAt = System.currentTimeMillis();
        this.calculatedByName = byName;
        this.publishedAt = null;
        this.publishedByName = null;
        this.finalized = false;
        this.finalizedAt = null;
        this.finalizedByName = null;
    }

    /** Public cho nhân viên xem. Set luôn {@code finalized} để code cũ còn dò theo cờ đó vẫn chạy đúng. */
    public void markPublished(String byName) {
        this.calcStatus = PayrollCalcStatus.PUBLISHED;
        this.publishedAt = System.currentTimeMillis();
        this.publishedByName = byName;
        this.finalized = true;
        this.finalizedAt = publishedAt;
        this.finalizedByName = byName;
    }

    /** Unpublic — lùi về trạng thái "đã tính nhưng chưa cho xem". */
    public void markUnpublished() {
        this.calcStatus = PayrollCalcStatus.CALCULATED;
        this.publishedAt = null;
        this.publishedByName = null;
        this.finalized = false;
        this.finalizedAt = null;
        this.finalizedByName = null;
    }

    /** "Mở lại" — xoá kết quả tính lương, cho phép upload adjustment trở lại. */
    public void markReopened() {
        this.calcStatus = hasAttendanceFile() ? PayrollCalcStatus.UPLOADED : PayrollCalcStatus.NONE;
        this.calculatedAt = null;
        this.calculatedByName = null;
        this.publishedAt = null;
        this.publishedByName = null;
        this.finalized = false;
        this.finalizedAt = null;
        this.finalizedByName = null;
        this.otTotalWeekdayMinutes = 0L;
        this.otTotalSundayMinutes = 0L;
        this.otTotalHolidayMinutes = 0L;
        this.otTotalAmount = 0L;
    }

    /**
     * Khi vừa thay/xoá file chấm công — kéo trạng thái về UPLOADED/NONE để
     * không hiển thị "đã tính" trên UI khi dữ liệu nền đã đổi.
     */
    public void onAttendanceFileChanged() {
        if (calcStatus == PayrollCalcStatus.CALCULATED || calcStatus == PayrollCalcStatus.PUBLISHED) {
            markReopened();
        } else {
            this.calcStatus = hasAttendanceFile() ? PayrollCalcStatus.UPLOADED : PayrollCalcStatus.NONE;
        }
    }

    // ── PHASE 4: KPI lifecycle ────────────────────────────────────────────

    public void markKpiCalculated(String byName) {
        this.kpiCalcStatus = PayrollCalcStatus.CALCULATED;
        this.kpiCalculatedAt = System.currentTimeMillis();
        this.kpiCalculatedByName = byName;
        this.kpiPublishedAt = null;
        this.kpiPublishedByName = null;
        this.kpiFinalized = false;
        this.kpiFinalizedAt = null;
        this.kpiFinalizedByName = null;
    }

    public void markKpiPublished(String byName) {
        this.kpiCalcStatus = PayrollCalcStatus.PUBLISHED;
        this.kpiPublishedAt = System.currentTimeMillis();
        this.kpiPublishedByName = byName;
        this.kpiFinalized = true;
        this.kpiFinalizedAt = kpiPublishedAt;
        this.kpiFinalizedByName = byName;
    }

    public void markKpiUnpublished() {
        this.kpiCalcStatus = PayrollCalcStatus.CALCULATED;
        this.kpiPublishedAt = null;
        this.kpiPublishedByName = null;
        this.kpiFinalized = false;
        this.kpiFinalizedAt = null;
        this.kpiFinalizedByName = null;
    }

    public void markKpiReopened() {
        this.kpiCalcStatus = PayrollCalcStatus.NONE;
        this.kpiCalculatedAt = null;
        this.kpiCalculatedByName = null;
        this.kpiPublishedAt = null;
        this.kpiPublishedByName = null;
        this.kpiFinalized = false;
        this.kpiFinalizedAt = null;
        this.kpiFinalizedByName = null;
    }

    // ── PHASE 4: Bonus (DT) lifecycle ─────────────────────────────────────

    public void markBonusCalculated(String byName) {
        this.bonusCalcStatus = PayrollCalcStatus.CALCULATED;
        this.bonusCalculatedAt = System.currentTimeMillis();
        this.bonusCalculatedByName = byName;
        this.bonusPublishedAt = null;
        this.bonusPublishedByName = null;
        this.bonusFinalized = false;
        this.bonusFinalizedAt = null;
        this.bonusFinalizedByName = null;
    }

    public void markBonusPublished(String byName) {
        this.bonusCalcStatus = PayrollCalcStatus.PUBLISHED;
        this.bonusPublishedAt = System.currentTimeMillis();
        this.bonusPublishedByName = byName;
        this.bonusFinalized = true;
        this.bonusFinalizedAt = bonusPublishedAt;
        this.bonusFinalizedByName = byName;
    }

    public void markBonusUnpublished() {
        this.bonusCalcStatus = PayrollCalcStatus.CALCULATED;
        this.bonusPublishedAt = null;
        this.bonusPublishedByName = null;
        this.bonusFinalized = false;
        this.bonusFinalizedAt = null;
        this.bonusFinalizedByName = null;
    }

    public void markBonusReopened() {
        this.bonusCalcStatus = PayrollCalcStatus.NONE;
        this.bonusCalculatedAt = null;
        this.bonusCalculatedByName = null;
        this.bonusPublishedAt = null;
        this.bonusPublishedByName = null;
        this.bonusFinalized = false;
        this.bonusFinalizedAt = null;
        this.bonusFinalizedByName = null;
    }
}