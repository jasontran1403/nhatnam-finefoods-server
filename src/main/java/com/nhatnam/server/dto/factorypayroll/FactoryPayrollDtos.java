package com.nhatnam.server.dto.factorypayroll;

import com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Tất cả DTO của module "Quản lý lương" (mọi bộ phận). */
public class FactoryPayrollDtos {

    // ─── Tháng có thể chọn ────────────────────────────────────────────────────

    /**
     * 1 mục trong dropdown chọn tháng. FE chỉ được chọn các THÁNG ĐÃ QUA —
     * tháng hiện tại chưa hết sẽ không có trong danh sách này.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PeriodOptionDto {
        private Integer month;
        private Integer year;
        /** VD: "Tháng 6/2026" */
        private String label;
        /** Đã có bảng chấm công cho tháng này chưa (theo bộ phận đang xét) */
        private Boolean attendanceReady;
        /** OWNER đã bấm "Hoàn tất" cho tháng + bộ phận này chưa */
        private Boolean finalized;
    }

    // ─── Breakdown ngày công ──────────────────────────────────────────────────

    /** 1 lượt vào/ra trong ngày. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PunchSessionDto {
        /** Giờ vào dạng "HH:mm" (null nếu quên chấm) */
        private String in;
        /** Giờ ra dạng "HH:mm" (null nếu quên chấm) */
        private String out;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceDayDto {
        /** Ngày trong tháng (1-31) */
        private Integer day;
        /** Thứ trong tuần: 2..7 = T2..T7, 8 = CN */
        private Integer weekday;
        /** Nhãn thứ đúng như trong file: Hai, Ba, Tư, Năm, Sáu, Bảy, CN */
        private String weekdayLabel;
        /** Số công của ngày (0 / 0.5 / 1 / >1 nếu tăng ca quy đổi) */
        private Double value;
        /** WORK | HALF | OFF | LEAVE | HOLIDAY | UNPAID | MISSING | EXCEPTION */
        private String type;

        // ── Chi tiết chấm công — hiển thị khi bấm vào ô ngày trên UI ──
        private String checkIn;
        private String checkOut;
        private List<PunchSessionDto> sessions;
        private Integer lateMinutes;
        private Integer earlyMinutes;
        private Integer workedMinutes;
        private Integer requiredMinutes;
        private String exception;
        private String windowStart;
        private String windowEnd;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceSummaryDto {
        private Double standardDays;
        private Double actualDays;
        private Double leaveDays;
        private Double unpaidDays;
        private Double overtimeHours;
        private Integer presentDays;
        private String employeeCode;
        private Integer lateCount;
        private Integer lateMinutes;
        private Integer earlyCount;
        private Integer earlyMinutes;
        private String shiftStart;
        private String shiftEnd;
        private List<AttendanceDayDto> days;
    }

    // ─── TÀI XẾ — số km chạy theo ngày ────────────────────────────────────────

    /** 1 đơn hàng được phân công cho tài xế trong ngày. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverOrderDto {
        private Long orderId;
        private String orderCode;
        /** DELIVERING | PENDING_PAYMENT | COMPLETED … */
        private String status;
        private String statusLabel;
        private String customerName;
        private String deliveryAddress;
        private String warehouseName;
        /** Số chuyến của tài xế này trên đơn */
        private Integer trips;
        /** Số lượt bằng xe máy trên đơn (chi tiết theo loại xe). */
        private Integer tripsMotorbike;
        /** Số lượt bằng xe tải trên đơn. */
        private Integer tripsTruck;
        /** Số km ƯỚC TÍNH của riêng đơn này */
        private Double km;

        /** Số tiền cần thu (đã làm tròn hàng đơn vị). */
        private BigDecimal finalAmount;
        /** Số tiền đã thanh toán (đã làm tròn hàng đơn vị). */
        private BigDecimal paidAmount;
        /** UNPAID | PARTIAL | PAID */
        private String paymentStatus;
        private String paymentStatusLabel;

        /** Thời gian đặt đơn (epoch milli). */
        private Long placedAt;
        /** Thời gian kho bắt đầu giao (epoch milli — fallback: placedAt). */
        private Long deliveredAt;
    }

    /** Tổng hợp 1 ngày của tài xế — thay cho ô ngày công của các bộ phận khác. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverDayDto {
        private Integer day;
        /** 2..7 = T2..T7, 8 = CN */
        private Integer weekday;
        private String weekdayLabel;
        /** Số đơn được phân công trong ngày */
        private Integer orderCount;
        /** Tổng số chuyến */
        private Integer trips;
        /** Tổng km ước tính trong ngày */
        private Double totalKm;
        /**
         * Nguồn số liệu km:
         * {@code ODOMETER} = chốt odo đầu/cuối ca (số thật);
         * {@code ESTIMATED} = ước tính từ số chuyến của các đơn được phân công.
         */
        private String kmSource;
        /**
         * MÀU Ô LỊCH:
         * {@code GREEN}  = có chạy (km > 0);
         * {@code GRAY}   = không chạy;
         * {@code YELLOW} = có điểm danh nhưng ODO start = end (km = 0).
         */
        private String dayColor;
        /** Chi tiết điểm danh ODO trong ngày (mỗi loại xe 1 dòng). */
        private List<DriverOdoDetailDto> odo;
        private List<DriverOrderDto> orders;
    }

    /** Chi tiết một cặp điểm danh ODO (đầu/cuối ca) của 1 loại xe trong ngày. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverOdoDetailDto {
        /** MOTORBIKE | TRUCK */
        private String vehicleType;
        private Integer odoStart;
        private Integer odoEnd;
        /** Số km của cặp này (end - start), 0 nếu bằng nhau */
        private Integer km;
        /** Người chấm công vào ca */
        private String startRecordedBy;
        /** Người chấm công kết ca */
        private String endRecordedBy;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverMonthDto {
        private Integer month;
        private Integer year;
        private Long driverId;
        private String driverName;
        private String vehicleType;
        /** Tổng km cả tháng */
        private Double totalKm;
        /** Tổng số đơn đã/đang giao trong tháng */
        private Integer totalOrders;
        /** Tổng số LƯỢT giao trong tháng (1 đơn giao nhiều lượt tính nhiều) */
        private Integer totalTrips;
        /** Số đơn có phần lượt XE MÁY. */
        private Integer totalOrdersMotorbike;
        /** Số đơn có phần lượt XE TẢI. */
        private Integer totalOrdersTruck;
        /** Tổng lượt XE MÁY trong tháng. */
        private Integer totalTripsMotorbike;
        /** Tổng lượt XE TẢI trong tháng. */
        private Integer totalTripsTruck;
        /** Số ngày có chạy */
        private Integer activeDays;
        /** Số km ước tính cho MỖI CHUYẾN khi không có dữ liệu odo */
        private Double kmPerTrip;
        private List<DriverDayDto> days;

        // ── LƯƠNG (điền khi OWNER đã nhập giá xăng + đơn giá thưởng) ──────────
        /** Giá xăng (đồng/km) áp dụng cho tháng — null nếu chưa nhập */
        private Long gasPrice;
        /** Đơn giá thưởng (đồng/lượt) — null nếu chưa nhập */
        private Long bonusUnitPrice;
        /** Tiền xăng = tổng km × giá xăng */
        private Long fuelPay;
        /** Thưởng = tổng lượt × đơn giá thưởng */
        private Long bonusPay;
        /** Tổng lương = tiền xăng + thưởng */
        private Long totalSalary;
        /** OWNER đã bấm Hoàn tất cho tháng này chưa */
        private Boolean finalized;
    }

    // ─── Breakdown thưởng KPI (CHỈ bộ phận Xưởng sản xuất) ────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class KpiBonusDto {
        private BigDecimal totalOutputKg;
        private BigDecimal totalOutputTon;
        private Long ratePerTon;
        private Long bonusPool;
        private Long carryOverIn;
        private Long carryOverOut;
        private Long securityTotal;
        private Double totalWeight;

        /** Quỹ dư đầu vào, tách theo THÁNG PHÁT SINH (cũ nhất trước). */
        private List<CarryOverEntryDto> carryOverInDetail;

        // ── Phần của CHÍNH nhân viên đang xem ──
        private String myRoleLabel;
        private Double myWeight;
        private Boolean myFixedRole;
        private Long myRawAmount;
        private Long myAmount;
    }

    /** Một khoản quỹ dư chưa chia hết, kèm tháng phát sinh. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CarryOverEntryDto {
        private Integer month;
        private Integer year;
        private Long amount;
        /** "T6/2026" — sẵn cho FE khỏi tự ghép. */
        private String label;
    }

    // ─── Phiếu lương tháng của nhân viên ──────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MyPayslipDto {
        /**
         * PROCESSING = OWNER chưa bấm "Hoàn tất" cho tháng + bộ phận → FE hiện
         *              "Đang xử lý lương".
         * READY      = đã hoàn tất, hiển thị đầy đủ phiếu lương.
         * NO_SALARY  = đã hoàn tất nhưng nhân viên chưa có hồ sơ lương.
         * NO_DEPARTMENT = nhân viên không thuộc bộ phận tính lương nào.
         */
        private String status;

        private Integer month;
        private Integer year;
        private String periodLabel;

        // ── Bộ phận tính lương ──
        /** FACTORY | ACCOUNTING | SALES | WAREHOUSE | DRIVER */
        private String payrollDepartment;
        private String payrollDepartmentLabel;
        /** Role NHẬN LƯƠNG (role chính thức, đã loại role kiêm nhiệm) */
        private String payrollRole;
        /** Bộ phận này có bảng "Thưởng KPI sản xuất" không */
        private Boolean hasKpiBonus;
        /** Bộ phận này có bảng chấm công không (tài xế = false) */
        private Boolean attendanceBased;

        // ── Thông tin nhân viên ──
        private Long userId;
        private String userFullName;
        private String department;
        private String division;
        private String position;
        private String roleLabel;

        // ── Lương (tóm tắt theo ngày công — giữ cho tương thích cũ) ──
        private Long baseSalary;
        private Long salaryByAttendance;
        private Long allowance;
        private Long fixedBonus;
        private Long kpiBonus;
        private Long totalPay;

        /**
         * CHI TIẾT LƯƠNG đầy đủ — ĐÚNG bộ số liệu OWNER nhìn thấy ở
         * {@code /owner/employees → Chi tiết lương}. FE chỉ render 2 khối
         * "Các khoản cấu thành" và "Của nhân viên".
         */
        private SalaryBreakdownDto salaryDetail;

        private AttendanceSummaryDto attendance;
        private KpiBonusDto kpi;

        /** Chỉ có với bộ phận Tài xế */
        private DriverMonthDto driver;

        /** Thời điểm owner upload bảng chấm công */
        private Long attendanceUploadedAt;
        /** Thời điểm owner bấm "Hoàn tất" */
        private Long finalizedAt;
    }

    // ─── Quản trị: bảng chấm công đã upload ───────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceSheetDto {
        private Long id;
        private Integer month;
        private Integer year;
        private String periodLabel;

        /** Bộ phận của bảng này */
        private String department;
        private String departmentLabel;
        /** Số nhân viên đang thuộc bộ phận */
        private Integer employeeCount;

        private String fileName;
        private String status;
        private Integer parsedRows;
        private Double standardDays;
        private String note;
        private String uploadedByName;
        private Long uploadedAt;

        // ── Trạng thái 3 loại file của tháng ──
        private Boolean hasAttendanceFile;

        private Boolean hasExceptionFile;
        private String  exceptionFileName;
        private Long    exceptionUploadedAt;
        private Integer exceptionRows;

        private Boolean hasLeaveFile;
        private String  leaveFileName;
        private Long    leaveUploadedAt;
        private Integer leaveRows;

        // ── Hoàn tất xử lý lương ──
        private Boolean finalized;
        private Long    finalizedAt;
        private String  finalizedByName;
        /** Đủ điều kiện bấm "Hoàn tất" chưa (đã có bảng chấm công, hoặc bộ phận Tài xế) */
        private Boolean canFinalize;
    }

    /** 1 dòng trong báo cáo khớp nhân viên sau khi import. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MatchRowDto {
        private Long userId;
        private String fullName;
        /** Role nhận lương của nhân viên */
        private String roleLabel;
        private String employeeCode;
        private String sourceName;
        private Integer presentDays;
        private Double actualDays;
        /** Cách khớp: CODE (mã chấm công) | EXACT_NAME (họ tên) */
        private String matchedBy;
    }

    /** Kết quả import file chấm công / lịch nghỉ / đơn xin nghỉ. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceImportResultDto {
        private Long sheetId;
        private Integer month;
        private Integer year;
        private String department;
        private String departmentLabel;

        /** Tổng số block nhân viên đọc được trong file */
        private Integer blocksInFile;
        /** Tổng số nhân viên đang thuộc bộ phận này trong hệ thống */
        private Integer departmentEmployees;

        /** Số nhân viên khớp được với file */
        private Integer matched;
        /** Số nhân viên KHÔNG tìm thấy trong file */
        private Integer skipped;

        private List<MatchRowDto> matchedRows;
        /** Chi tiết các nhân viên THIẾU trong file */
        private List<MatchRowDto> unmatchedRows;
        /** Các block trong file không khớp nhân viên nào của bộ phận */
        private List<String> unusedBlocks;

        private List<String> errors;
    }

    // ─── Bảng tổng hợp lương của cả bộ phận (OWNER xem sau khi Hoàn tất) ──────

    /** 1 dòng nhân viên trong 2 bảng "Phiếu lương" & "Chi tiết ngày công". */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DepartmentPayrollRowDto {
        private Long userId;
        private String userFullName;
        private String roleLabel;
        /** Tên enum của ROLE HƯỞNG LƯƠNG (VD SUPER_FACTORY_WORKER) — FE dùng để
         *  nhận diện vị trí mà không phải so chuỗi nhãn tiếng Việt. */
        private String payrollRole;
        private String department;
        private String division;

        // ── Phiếu lương ──
        /** PENDING | APPROVED | REJECTED | NO_SALARY */
        private String salaryStatus;
        private Long baseSalary;
        private Long allowance;
        private Long bonus;
        private Long grossSalary;
        private Long employeeInsuranceTotal;
        private Long personalIncomeTax;
        private Long netSalary;
        /** Thưởng KPI sản xuất — chỉ bộ phận Xưởng */
        private Long kpiBonus;

        /**
         * LƯƠNG CƠ BẢN KHI ĐỦ CÔNG — mức đã nhập trong hồ sơ, CHƯA chia theo
         * chấm công. Đặt cạnh {@code baseSalary} (đã chia) để bảng lương hiện
         * được cả hai cột: nhìn là biết tháng đó bị trừ bao nhiêu vì thiếu công.
         */
        private Long standardBaseSalary;

        /**
         * BREAKDOWN LƯƠNG ĐẦY ĐỦ — để mở modal "Chi tiết lương" ngay từ bảng,
         * không phải gọi thêm API cho từng nhân viên.
         */
        private SalaryBreakdownDto salaryDetail;

        // ── Thâm niên (số tiền đã nằm trong `allowance`) ──
        /** Số năm thâm niên TRÒN tại kỳ này. */
        private Integer seniorityYears;
        /** % phụ cấp thâm niên (0, hoặc 2..10). */
        private Integer seniorityPercent;
        /** Tiền phụ cấp thâm niên. */
        private Long seniorityAllowance;

        // ── Chi tiết ngày công ──
        private Double standardDays;
        private Double actualDays;
        private Integer presentDays;
        private Integer lateCount;
        private Integer lateMinutes;
        private Integer earlyCount;
        private Integer earlyMinutes;
        private String employeeCode;
        /** Có mặt trong file chấm công của tháng không */
        private Boolean hasAttendance;

        /**
         * KHÔNG THEO DÕI CHẤM CÔNG (bảo vệ xưởng — hưởng khoán trọn tháng).
         *
         * <p>Khác hẳn {@code hasAttendance = false} vốn nghĩa là "đáng lẽ phải có
         * mà chưa thấy trong file". FE dùng cờ này để hiện "—" thay vì 0 công:
         * số 0 ở cột ngày công trông y như nhân viên nghỉ cả tháng.
         */
        private Boolean attendanceExempt;

        // ── Tài xế ──
        private Double totalKm;
        private Integer totalOrders;
    }

    /** Một nhân viên trong bảng "Chi tiết bộ phận". */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DepartmentMemberDto {
        private Long userId;
        private String fullName;

        /** Chức vụ trong hồ sơ nhân sự — chữ do HR chọn ở trang Nhân sự. */
        private String position;

        /**
         * Chức danh suy từ ROLE HƯỞNG LƯƠNG.
         *
         * <p>Giữ cả hai vì chúng có thể khác nhau và sự khác nhau đó là thông tin
         * hữu ích: hồ sơ ghi "Nhân viên kinh doanh" nhưng role hưởng lương lại là
         * Thủ kho thì người này đang bị tính lương sai bộ phận.
         */
        private String roleLabel;

        private String division;

        /** Không theo dõi chấm công (bảo vệ xưởng hưởng khoán). */
        private Boolean attendanceExempt;

        /** Đã có hồ sơ lương được nhập hay chưa. */
        private Boolean hasSalary;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DepartmentMembersDto {
        private String department;
        private String departmentLabel;
        private Integer total;
        private List<DepartmentMemberDto> members;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DepartmentPayrollDto {
        private Integer month;
        private Integer year;
        private String periodLabel;
        private String department;
        private String departmentLabel;
        private Boolean finalized;
        private Boolean attendanceBased;
        private Boolean hasKpiBonus;

        private Integer employeeCount;
        private Long totalNetSalary;
        private Long totalGrossSalary;
        private Long totalKpiBonus;

        // ══════════════════════════════════════════════════════════════════════
        // TỔNG QUAN THƯỞNG KPI — chỉ có ở bộ phận Xưởng
        // ══════════════════════════════════════════════════════════════════════
        //
        // Ba trạng thái FE cần phân biệt, đừng suy ra từ việc số liệu có null hay
        // không (quỹ thưởng bằng 0 là hợp lệ, không phải "chưa tính"):
        //   kpiComputed = true   → hiện đủ sản lượng, đơn giá, quỹ thưởng, quỹ dư,
        //                          tổng net, tổng KPI
        //   kpiComputed = false  → CHƯA tính KPI, chỉ hiện tổng net
        //   hasKpiBonus = false  → bộ phận không có KPI, cũng chỉ hiện tổng net

        /** Đã tính thưởng KPI cho kỳ này chưa. */
        private Boolean kpiComputed;

        /** Tổng sản lượng hoàn thành trong tháng (kg). */
        private java.math.BigDecimal kpiTotalOutputKg;

        /** Quy đổi ra tấn. */
        private java.math.BigDecimal kpiTotalOutputTon;

        /** Đơn giá thưởng trên 1 tấn (VNĐ). */
        private Long kpiRatePerTon;

        /** Quỹ thưởng của cả phòng trong tháng. */
        private Long kpiBonusPool;

        /** Tổng tiền dư mang sang TỪ các đợt trước. */
        private Long kpiCarryOverIn;

        /** Tiền dư còn lại chuyển SANG tháng sau. */
        private Long kpiCarryOverOut;

        /** Chi tiết nguồn gốc quỹ dư đầu vào — "T6/2026: 113.000đ". */
        private List<CarryOverEntryDto> kpiCarryOverInDetail;

        private Long kpiComputedAt;

        private List<DepartmentPayrollRowDto> rows;
    }

    // ─── LƯƠNG TÀI XẾ — cấu hình + bảng lương cho OWNER ───────────────────────

    /**
     * Một dòng lương tài xế trong bảng OWNER xem — tách theo LOẠI XE (xe máy có
     * tiền xăng + thưởng, xe tải chỉ có thưởng theo lượt; lương cứng xe tải
     * quản lý ở phần HR khác).
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverSalaryRowDto {
        private Long userId;
        private Long driverId;
        private String driverName;
        /** MOTORBIKE | TRUCK | BOTH — loại xe khai báo của tài xế. */
        private String vehicleType;

        /** Phần lương XE MÁY (null nếu tài xế chỉ chạy xe tải). */
        private VehicleSubtotalDto motorbike;
        /** Phần lương XE TẢI (null nếu tài xế chỉ chạy xe máy). */
        private VehicleSubtotalDto truck;

        /** Tổng lương của tài xế = motorbike.totalSalary + truck.totalSalary. */
        private Long grandTotalSalary;
    }

    /** Chi tiết lương của MỘT loại xe (dùng cho cả xe máy và xe tải). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class VehicleSubtotalDto {
        /** Tổng km (chỉ có ý nghĩa với xe máy — xe tải không tính tiền xăng). */
        private Double totalKm;
        private Integer totalOrders;
        private Integer totalTrips;
        /** Tiền xăng — chỉ xe máy có (null với xe tải). */
        private Long fuelPay;
        /** Thưởng = totalTrips × đơn giá thưởng của loại xe tương ứng. */
        private Long bonusPay;
        /** Tổng lương của loại xe này (fuelPay + bonusPay). */
        private Long totalSalary;
    }

    /**
     * Cấu hình + bảng lương tài xế của 1 tháng — OWNER nhập giá xăng, đơn giá
     * thưởng (tách xe máy / xe tải) rồi bấm Hoàn tất.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverPayrollConfigDto {
        private Integer month;
        private Integer year;
        /** Giá xăng (đồng/km) — chỉ áp dụng cho xe máy. */
        private Long gasPrice;
        /** Đơn giá thưởng XE MÁY (đồng/lượt). */
        private Long bonusUnitPrice;
        /** Đơn giá thưởng XE TẢI (đồng/lượt). */
        private Long truckBonusUnitPrice;
        private Boolean finalized;
        private Long finalizedAt;
        private String finalizedByName;
        /** Tổng lương chi cho toàn bộ tài xế. */
        private Long grandTotalSalary;
        private List<DriverSalaryRowDto> rows;
    }
}