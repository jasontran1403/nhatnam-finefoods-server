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
        /** (10/2026) OWNER đã Public THƯỞNG DT (KT/KD/Driver) cho tháng này chưa. */
        private Boolean bonusReady;
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

        // ── Ngày phép còn lại sau kỳ (tính theo phút) ────────────────────────
        /** Phút phép đã trừ do trễ/sớm trong kỳ này. */
        private Integer leaveMinutesUsed;
        /**
         * Phút phép còn lại sau kỳ.
         * Hiển thị UI: quy ra bước 0.5 ngày + phút dư.
         * Báo cáo: chia 480 lấy 3 số thập phân.
         */
        private Integer leaveBalanceMinutesAfter;

        /**
         * Chuỗi hiển thị — "X.5 ngày Y phút".
         * Ví dụ: 1901 phút → "3.5 ngày 221 phút".
         */
        public String getLeaveBalanceDisplay() {
            if (leaveBalanceMinutesAfter == null || leaveBalanceMinutesAfter <= 0) return "0 ngày";
            int halfDays   = leaveBalanceMinutesAfter / 240;
            int remMinutes = leaveBalanceMinutesAfter % 240;
            String daysStr = (halfDays % 2 == 0)
                    ? String.valueOf(halfDays / 2)
                    : (halfDays / 2) + ".5";
            return remMinutes == 0
                    ? daysStr + " ngày"
                    : daysStr + " ngày " + remMinutes + " phút";
        }

        /** Số ngày thập phân cho báo cáo (3 chữ số). Ví dụ: 1901 phút → 3.960. */
        public Double getLeaveBalanceDaysReport() {
            if (leaveBalanceMinutesAfter == null) return null;
            return Math.round(leaveBalanceMinutesAfter / 480.0 * 1000.0) / 1000.0;
        }
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
         * PROCESSING = OWNER chưa bấm "Hoàn tất Lương" cho tháng + bộ phận → FE hiện
         *              "Đang xử lý lương".
         * READY      = đã hoàn tất lương, hiển thị đầy đủ phiếu lương.
         * NO_SALARY  = đã hoàn tất nhưng nhân viên chưa có hồ sơ lương.
         * NO_DEPARTMENT = nhân viên không thuộc bộ phận tính lương nào.
         */
        private String status;

        /**
         * Trạng thái KPI / Bonus — tách riêng khỏi {@code status}:
         * <ul>
         *   <li>{@code PENDING}  — OWNER chưa bấm "Hoàn tất KPI", FE hiển thị "Đang tính thưởng".</li>
         *   <li>{@code READY}    — đã hoàn tất KPI, hiển thị số thưởng.</li>
         *   <li>{@code NONE}     — bộ phận không có KPI/bonus.</li>
         * </ul>
         */
        private String kpiStatus;

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

        /**
         * (10/2026) Chi tiết thưởng doanh thu — chỉ có cho KT/KD khi đã Public.
         * KT: dùng pool phòng + bonusAmount cá nhân.
         * KD: dùng revenue cá nhân + hold cá nhân + bonusAmount cá nhân.
         */
        /** KD: Tổng doanh thu tháng cá nhân (= Σ final − vat của đơn tạo trong tháng, EX-VAT, loại CANCELLED). */
        private Long officeBonusPersonalRevenue;
        /** KD: Doanh thu đang hold cá nhân (EX-VAT). */
        private Long officeBonusPersonalHold;
        /** KD: Doanh thu CHIA HOA HỒNG cá nhân (= Σ thực thu từ payment_transaction, EX-VAT). */
        private Long officeBonusPersonalChiaHoaHong;
        /** KT: Tổng pool phòng (= dept collected + waived EX-VAT). */
        private Long officeBonusPoolTotal;

        // ─── (10/2026) Cross-dept support bonus ──────────────────────────
        /**
         * Thưởng HỖ TRỢ BÁN HÀNG — cho NV ko thuộc SALES nhưng có tạo đơn
         * trong tháng (VD NV kho, trưởng xưởng kiêm bán). Lấy từ office_bonus_item
         * ở dept SALES. Null hoặc 0 → không hiển thị card.
         */
        private Long salesSupportBonus;
        /** Doanh thu chia hoa hồng cá nhân (snapshot) để hiển thị trên card. */
        private Long salesSupportRevenue;

        // (10/2026) Cho SELLER chính: target cá nhân + % đạt (hiển thị trên phiếu lương)
        /** Target doanh thu cá nhân (snapshot tại tháng này). null = non-SELLER. */
        private Long salesPersonalTarget;
        /** % đạt target = revenue / personalTarget × 100. */
        private Double salesAchievedPercent;

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
        /** Đủ điều kiện bấm "Hoàn tất Lương" chưa (đã có bảng chấm công, hoặc bộ phận không cần file) */
        private Boolean canFinalize;

        // ── Hoàn tất KPI / Thưởng (tách riêng khỏi lương) ──
        /** OWNER đã bấm "Hoàn tất KPI" chưa — false = KPI đang pending với nhân viên. */
        private Boolean kpiFinalized;
        private Long    kpiFinalizedAt;
        private String  kpiFinalizedByName;
        /** Bộ phận này có KPI / bonus để tính không (hiện đang áp dụng cho tất cả bộ phận). */
        private Boolean hasKpiBonus;

        // ── Hoàn tất Thưởng doanh thu (chỉ SALES và ACCOUNTING, bước 3) ──
        /**
         * OWNER đã bấm "Hoàn tất Thưởng" chưa.
         * Chỉ có ý nghĩa với SALES và ACCOUNTING.
         * false = thưởng đang pending (nhân viên thấy "Đang tính thưởng").
         * (10/2026) Giờ tương đương với state == PUBLISHED — nhân viên chỉ thấy
         * phiếu khi field này = true.
         */
        private Boolean bonusFinalized;
        private Long    bonusFinalizedAt;
        private String  bonusFinalizedByName;

        /**
         * (10/2026) State chi tiết của thưởng — 3 giá trị:
         *   NONE       = chưa tính
         *   CALCULATED = đã tính nhưng CHƯA public (nhân viên chưa thấy)
         *   PUBLISHED  = đã tính và đã PUBLIC (nhân viên thấy phiếu)
         * UI hiển thị 3 state khác nhau; bonusFinalized vẫn giữ để tương thích.
         */
        private String  bonusCalcStatus;
        private Long    bonusCalculatedAt;
        private String  bonusCalculatedByName;
        private Long    bonusPublishedAt;
        private String  bonusPublishedByName;
        /**
         * Bộ phận này có tính thưởng doanh thu không.
         * true  = SALES hoặc ACCOUNTING.
         * false = FACTORY, WAREHOUSE, MANAGEMENT, DRIVER (không dùng bước 3).
         */
        private Boolean hasSalesBonus;
    }

    // ── Kết quả tính thưởng doanh thu (SALES / ACCOUNTING) ────────────────────

    /** Tóm tắt kết quả tính thưởng doanh thu cho cả bộ phận. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OfficeBonusSummaryDto {
        private Integer month;
        private Integer year;
        private String  department;
        private String  departmentLabel;
        /** Tổng doanh thu (tiền THỰC THU) của bộ phận trong tháng. */
        private java.math.BigDecimal totalRevenue;
        /** Tổng quỹ thưởng được chia. */
        private Long totalBonusPool;
        /** Số phiếu thanh toán được đếm. */
        private Integer transactionCount;
        /** Số tiền còn dư KHÔNG chia (SALES: doanh thu không đủ mốc; ACCOUNTING: pool×(100−Σratio)/100). */
        private Long undistributedAmount;
        /** SALES: list mốc đã dùng khi tính. NULL khi ACCOUNTING / legacy. */
        private java.util.List<SalesTierDto> salesTiers;
        /** ACCOUNTING: % hoa hồng của phòng khi tính. NULL khi SALES / legacy. */
        private java.math.BigDecimal accountingPercent;
        /** ACCOUNTING: tỉ lệ từng người đã dùng khi tính. NULL khi SALES. */
        private java.util.List<UserRatioDto> accountingRatios;
        private Long    computedAt;
        private String  computedByName;
        /** Chi tiết từng nhân viên. */
        private java.util.List<OfficeBonusItemDto> items;
    }

    /** Dòng thưởng của 1 nhân viên. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OfficeBonusItemDto {
        private Long   userId;
        private String userFullName;
        private String roleLabel;
        /** Doanh thu tính thưởng cho người này (VNĐ). */
        private java.math.BigDecimal revenue;
        /** Số tiền thưởng (đã làm tròn LÊN 5.000). */
        private Long bonusAmount;
        /** Số tiền thưởng CHƯA làm tròn (raw) — chỉ để đối chiếu. */
        private Long rawBonusAmount;
        /** ACCOUNTING: tỉ lệ % đã dùng cho user này. NULL khi SALES. */
        private java.math.BigDecimal ratioPercent;
        /** % KPI áp dụng (snapshot). */
        private Double kpiPercent;
        private Integer transactionCount;
    }

    // ── FLOW MỚI 2026: Request body cho POST /finalize-bonus ─────────────────

    /** 1 mốc thưởng SALES (gửi từ FE khi tính). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SalesTierDto {
        /** Ngưỡng doanh thu (VNĐ) cần đạt để tính 1 lần mốc này. */
        private java.math.BigDecimal threshold;
        /** Tiền thưởng khi đạt 1 lần ngưỡng (VNĐ). */
        private Long unitPrice;
    }

    /** 1 tỉ lệ của 1 kế toán viên (gửi từ FE khi tính). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserRatioDto {
        private Long userId;
        /** % (0..100). */
        private java.math.BigDecimal ratio;
    }

    /**
     * Body cho POST /finalize-bonus (flow mới).
     * - SALES: dùng {@link #salesTiers}
     * - ACCOUNTING: dùng {@link #accountingPercent} + {@link #accountingRatios}
     * - LEGACY: nếu các field trên trống và {@link #unitPrice} có giá trị thì
     *   fallback về công thức cũ (giữ tương thích).
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinalizeBonusRequest {
        private java.util.List<SalesTierDto> salesTiers;
        private java.math.BigDecimal accountingPercent;
        private java.util.List<UserRatioDto> accountingRatios;
        /** Legacy đơn giá / 100tr — vẫn chấp nhận để FE cũ còn chạy. */
        private Long unitPrice;

        // ─── (10/2026) Flow mới SALES v2: target + 1 mốc duy nhất ──────────
        /** Doanh thu target của phòng (OWNER nhập). */
        private Long salesTarget;
        /** Mỗi bước doanh thu — VD 100.000.000. */
        private Long salesStepAmount;
        /** Đơn giá/bước full (chưa nhân multiplier) — VD 400.000. */
        private Long salesStepUnitPrice;
    }

    // ══════════════════════════════════════════════════════════════════════
    // SALES BONUS v2 — preview & publish-with-overrides (10/2026)
    // ══════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SalesBonusPreviewDto {
        private Integer month;
        private Integer year;
        /** Config đang dùng */
        private Long target;
        private Long stepAmount;
        private Long stepUnitPrice;
        /** 1.0 (đạt) hoặc 0.5 (dưới) — legacy dept-wide, giữ cho tương thích */
        private Double multiplier;
        /** Tổng doanh thu phòng (ex-VAT, đã trừ discount). */
        private Long deptRevenue;
        /** Hiển thị: deptRevenue >= target ? */
        private Boolean targetAchieved;
        /** (10/2026) Ladder tiers áp dụng per-seller theo % đạt target cá nhân. */
        private java.util.List<SalesBonusTierDto> tiers;
        private java.util.List<SalesBonusRowDto> rows;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SalesBonusRowDto {
        private Long userId;
        private String fullName;
        private String roleLabel;
        private Long revenue;
        private Integer steps;
        private Long remainder;
        /** Đơn giá / bước (sau khi nhân multiplier). Có thể OWNER sửa. */
        private Long stepUnitPrice;
        /** Thưởng cho phần dư (default 0). Có thể OWNER sửa. */
        private Long remainderBonus;
        /** = steps × stepUnitPrice + remainderBonus */
        private Long totalBonus;
        /** OWNER đã sửa so với default chưa */
        private Boolean ownerEdited;

        // (10/2026) Target cá nhân + achieved
        /** TRUE nếu user là SELLER / SUPER_SELLER (có áp dụng target cá nhân). */
        private Boolean isSalesSeller;
        /** Target cá nhân (nếu isSalesSeller). */
        private Long personalTarget;
        /** % đạt = revenue / personalTarget × 100. null nếu không áp dụng. */
        private Double achievedPercent;
        /** Multiplier được áp dụng (từ ladder). */
        private Double appliedMultiplier;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SalesBonusTierDto {
        /** Ngưỡng % hoàn thành target (0-200+). */
        private Integer thresholdPercent;
        /** % đơn giá (0-100+). */
        private Integer pricePercent;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PublishSalesBonusRequest {
        private java.util.List<SalesBonusOverrideDto> overrides;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BONUS TREND — biến động thưởng 12 tháng gần nhất per dept (10/2026)
    // ══════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BonusTrendDto {
        private String department;             // SALES | ACCOUNTING | DRIVER | FACTORY | WAREHOUSE
        private String departmentLabel;
        /** 12 tháng, sắp xếp mới nhất → cũ nhất */
        private java.util.List<BonusTrendMonthDto> months;
        /** Union các NV từng có thưởng trong 12 tháng (kể cả đã nghỉ/khoá). */
        private java.util.List<BonusTrendUserDto> users;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BonusTrendMonthDto {
        private Integer month;
        private Integer year;
        /** Dept-specific key→value (mixed types: Long / Double / String).
         *  SALES: deptRevenue(Long), target(Long), stepUnitPrice(Long), stepAmount(Long), totalPool(Long)
         *  ACCOUNTING: deptRevenue(Long), commissionPercent(Double), totalPool(Long)
         *  DRIVER: gasPrice(Long), bikeUnitPrice(Long), truckUnitPrice(Long), totalPool(Long)
         *  FACTORY: totalOutputKg(Long), ratePerTon(Long), bonusPool(Long)
         */
        private java.util.Map<String, Object> stats;
        /** TRUE nếu tháng này đã Public (có data). */
        private Boolean published;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BonusTrendUserDto {
        private Long userId;
        private String fullName;
        private String roleLabel;
        private Boolean deleted;
        private Boolean locked;
        /** TRUE nếu NV ko thuộc dept đang xem (hỗ trợ bán hàng / giao hàng / …). */
        private Boolean support;
        /** Nhãn hỗ trợ: "hỗ trợ bán hàng" / "hỗ trợ giao hàng". Null nếu không support. */
        private String supportLabel;
        /** Thứ hạng chức vụ trong dept (nhỏ hơn = cao hơn). Dùng để sort. */
        private Integer roleRank;
        /** TRUE nếu NV nhận "thưởng cố định" (VD Bảo vệ Q9 ở xưởng SX). */
        private Boolean fixedAmount;
        /** key = "YYYY-MM" (VD "2026-09"), value = tiền thưởng. Null = không có. */
        private java.util.Map<String, Long> amountsByMonth;
        /** key = "YYYY-MM", value = % tỷ lệ (chỉ ACCOUNTING). Null nếu không có. */
        private java.util.Map<String, Double> ratioByMonth;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BONUS SUMMARY — tổng hợp thưởng 12 tháng theo phòng → chức vụ (10/2026)
    // ══════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BonusSummaryDto {
        /** 12 tháng cùng khung với BonusTrend (newest-1 → oldest). */
        private java.util.List<BonusTrendMonthDto> months;
        /** Nhóm theo phòng ban, thứ tự KD → SX → KT → Kho → TX. */
        private java.util.List<BonusSummaryGroupDto> groups;
        /** Grand total toàn công ty mỗi tháng (= Σ group totals). */
        private java.util.Map<String, Long> grandTotalsByMonth;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BonusSummaryGroupDto {
        private String department;         // SALES / FACTORY / ...
        private String departmentLabel;
        /** NV trong phòng (sort theo role rank asc). Mỗi NV = sum thưởng từ MỌI nguồn. */
        private java.util.List<BonusTrendUserDto> users;
        /** Σ của tất cả NV trong phòng mỗi tháng. */
        private java.util.Map<String, Long> totalsByMonth;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AttendanceBonusRowDto {
        private Long userId;
        private String fullName;
        private String roleLabel;
        private String department;
        private String departmentLabel;
        private Boolean deleted;
        private Boolean locked;
        /** Amount tháng hiện tại (0 nếu chưa có). */
        private Long amount;
        /** Amount tháng gần nhất trước đó (dùng làm placeholder). */
        private Long previousAmount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SalesBonusOverrideDto {
        private Long userId;
        /** Đơn giá/bước đã sửa (null → giữ default). */
        private Long stepUnitPrice;
        /** Thưởng dư đã sửa (null → giữ default 0). */
        private Long remainderBonus;
        /** Target cá nhân mới (nullable) — chỉ áp dụng cho SELLER. */
        private Long personalTarget;
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
         * LƯƠNG THỰC NHẬN hiển thị trên bảng preview — KHỚP với "Lương thực
         * nhận" ở file export tổng hợp VÀ "Số tiền" ở file chuyển khoản NH.
         *
         * <p>Công thức (tương đương bank export):
         * <pre>
         *   netReceived = roundUpToThousand(baseSalary + Σ(visible allowances))
         * </pre>
         *
         * <p>Visible allowances = tất cả allowance HIỂN THỊ trong file lương
         * (bỏ các khoản tự sinh từ hỗ trợ giao hàng, bỏ "Phụ cấp xăng xe"
         * nếu là tài xế). Đây là số OWNER đọc trên bảng, số máy ngân hàng
         * chuyển, và số nhân viên nhận.
         */
        private Long netReceived;

        /**
         * LƯƠNG CƠ BẢN KHI ĐỦ CÔNG — mức đã nhập trong hồ sơ, CHƯA chia theo
         * chấm công. Đặt cạnh {@code baseSalary} (đã chia) để bảng lương hiện
         * được cả hai cột: nhìn là biết tháng đó bị trừ bao nhiêu vì thiếu công.
         */
        private Long standardBaseSalary;

        /**
         * (10/2026) TRUE nếu nhân viên đang nghỉ thai sản theo chế độ. FE dùng
         * cờ này để MERGE từ cột "Lương theo chấm công" tới cột "Thực nhận"
         * thành 1 dòng chữ "Đang nghỉ thai sản theo chế độ" (giấu toàn bộ
         * số liệu công/phụ cấp/thưởng của tháng đó, kể cả khi vẫn có khoản
         * nhập tay). Luồng chi lương / bank export vẫn bỏ qua người này.
         */
        private Boolean maternityLeave;

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
        /** OWNER đã bấm "Hoàn tất KPI/Thưởng" chưa — false = KPI đang pending. */
        private Boolean kpiFinalized;
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

        // ── Thông tin bổ sung cho nhân viên NGOÀI bộ phận tài xế ──
        /** Bộ phận thực tế của nhân viên (VD "Xưởng sản xuất") */
        private String department;
        /** Chức vụ (VD "Trợ lý xưởng") */
        private String position;
        /** TRUE nếu nhân viên này KHÔNG thuộc bộ phận Tài xế */
        private Boolean nonDriver;

        /**
         * (10/2026) TRUE nếu đây là tài xế xe tải (position = "Tài xế xe tải").
         * Khi true, các field motorbike / truck sẽ null (vì không tính theo
         * lượt), chỉ dùng {@link #fixedTruckBonus}.
         */
        private Boolean truckDriver;

        /**
         * (10/2026) Thưởng cố định tháng cho tài xế xe tải (đồng). OWNER nhập
         * trong modal "Tính thưởng". grandTotalSalary = fixedTruckBonus (không
         * làm tròn 5.000 vì OWNER chủ động nhập số chẵn).
         */
        private Long fixedTruckBonus;
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

        /**
         * Nhân viên NGOÀI bộ phận Tài xế nhưng có hoạt động giao hàng (ODO / đơn).
         * Phụ cấp xăng xe được tính và lưu vào phiếu lương của bộ phận gốc.
         */
        private List<DriverSalaryRowDto> nonDriverRows;

        /**
         * (10/2026) Tài xế xe tải — nhận thưởng CỐ ĐỊNH tháng (position =
         * "Tài xế xe tải"). Tách khỏi rows (vốn là tài xế xe máy) để UI render
         * thành section riêng + export PDF/Excel chia 3 nhóm (xe máy / xe tải /
         * hỗ trợ). Mỗi row có fixedTruckBonus, các field tính theo lượt null.
         */
        private List<DriverSalaryRowDto> truckDriverRows;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PREVIEW HOA HỒNG — hiển thị trước khi tính (11/2026)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Preview dữ liệu Thưởng/Hoa hồng cho tab SALES hoặc ACCOUNTING.
     * Trả về CẢ khi chưa tính (bonusAmount = null) và sau khi đã tính.
     *
     * <ul>
     *   <li>SALES: mỗi row là 1 seller với doanh thu riêng.</li>
     *   <li>ACCOUNTING: mỗi row là 1 kế toán viên; các cột doanh thu bằng nhau
     *       (dùng tổng phòng), bonusAmount thì mỗi người riêng theo trọng số.</li>
     * </ul>
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OfficeBonusPreviewDto {
        private Integer month;
        private Integer year;
        private String  department;        // SALES | ACCOUNTING
        private String  departmentLabel;

        /** Tổng doanh thu TOÀN BỘ ĐƠN tạo trong tháng (bất kể đã thu hay chưa). */
        private java.math.BigDecimal totalMonthOrderRevenue;
        /** Tổng tiền THỰC THU trong tháng — dùng tính hoa hồng. */
        private java.math.BigDecimal totalCollectedRevenue;
        /**
         * Σ (finalAmount − paidAmount) của đơn PENDING_PAYMENT tạo trong tháng.
         * KHÔNG tính đơn COMPLETED còn chênh — xem {@link #totalWaivedRevenue}.
         */
        private java.math.BigDecimal totalHoldRevenue;
        /**
         * Σ (finalAmount − paidAmount) của đơn COMPLETED tạo trong tháng còn chênh —
         * nghĩa là kế toán đã xác nhận "bỏ số lẻ không thu tiếp".
         */
        private java.math.BigDecimal totalWaivedRevenue;

        /** Số phiếu thanh toán trong tháng. */
        private Integer transactionCount;

        /** Đã bấm "Tính hoa hồng" hay chưa — FE dùng để quyết định hiển thị cột bonus hay "—". */
        private boolean commissionCalculated;
        /** Đơn giá hoa hồng OWNER đã nhập cho tháng này (null nếu chưa tính). */
        private Long commissionUnitPrice;
        /** Đơn giá OWNER nhập cho THÁNG GẦN NHẤT TRƯỚC đó (null nếu chưa từng). FE dùng làm placeholder. */
        private Long lastCommissionUnitPrice;

        /** Tổng pool đã tính (null nếu chưa tính). */
        private Long totalBonusPool;
        /** Số tiền DƯ không chia (hiển thị, null khi chưa tính). */
        private Long undistributedAmount;

        // ── Flow MỚI 2026: cấu hình đã dùng khi tính (echo lại cho FE) ──
        /** SALES: list mốc đã dùng khi tính (null nếu chưa tính / ACCOUNTING). */
        private java.util.List<SalesTierDto> salesTiers;
        /** ACCOUNTING: % hoa hồng của phòng khi tính (null nếu chưa tính / SALES). */
        private java.math.BigDecimal accountingPercent;
        /** ACCOUNTING: tỉ lệ từng người khi tính (null nếu chưa tính / SALES). */
        private java.util.List<UserRatioDto> accountingRatios;

        private Long computedAt;
        private String computedByName;

        private java.util.List<OfficeBonusPreviewRowDto> items;
    }

    /** 1 dòng preview cho 1 nhân viên. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OfficeBonusPreviewRowDto {
        private Long   userId;
        private String userFullName;
        private String roleLabel;
        /** Thứ tự sắp xếp theo chức vụ trong bộ phận (0 = cao nhất). */
        private Integer roleSortOrder;
        /** Trọng số chia pool — chỉ có ý nghĩa cho ACCOUNTING (KTT=2, CV=1). */
        private Integer weight;

        /** Doanh thu đơn tạo trong tháng — SALES: của seller này; ACCOUNTING: tổng phòng. */
        private java.math.BigDecimal totalMonthOrderRevenue;
        /** Doanh thu thực thu trong tháng — SALES: riêng seller; ACCOUNTING: tổng phòng. */
        private java.math.BigDecimal totalCollectedRevenue;
        /** Doanh thu PENDING_PAYMENT đang hold — SALES: riêng seller; ACCOUNTING: tổng phòng. */
        private java.math.BigDecimal totalHoldRevenue;
        /** Σ COMPLETED còn chênh — SALES: riêng seller; ACCOUNTING: tổng phòng. */
        private java.math.BigDecimal totalWaivedRevenue;
        private Integer transactionCount;

        /** Số hoa hồng đã tính — null khi chưa bấm "Tính hoa hồng". */
        private Long bonusAmount;
        /** Số hoa hồng CHƯA làm tròn (raw) — null khi chưa tính. */
        private Long rawBonusAmount;
        /** ACCOUNTING: tỉ lệ đã dùng cho user này — null khi SALES / chưa tính. */
        private java.math.BigDecimal ratioPercent;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BACKFILL PAYMENT TRANSACTION (admin tool)
    // ══════════════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PaymentBackfillReportDto {
        private boolean dryRun;
        private long scannedLogs;
        private long createdTransactions;
        private long skippedExisting;      // đã có PT cùng (order, time) → bỏ
        private long skippedUnparseable;   // note không match regex "Thu: ..."
        private long skippedZeroAmount;
        private java.util.List<String> warnings;  // mẫu vài dòng không parse được
    }
}