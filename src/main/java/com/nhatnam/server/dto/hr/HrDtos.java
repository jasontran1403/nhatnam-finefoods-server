package com.nhatnam.server.dto.hr;

import lombok.*;

import java.util.List;

public class HrDtos {

    // ── Salary ────────────────────────────────────────────────────────────────
    // Lương gồm 3 thành phần cố định hàng tháng: lương trước thuế (GROSS cơ bản),
    // phụ cấp, thưởng. Ngày công thực tế/người phụ thuộc thay đổi theo từng tháng
    // nên không lưu ở đây — nhập lại mỗi lần tính lương (xem PayrollDtos).

    @Data
    public static class SalaryRequest {
        private Long userId;
        /** Lương NET thực nhận/tháng (KHÔNG gồm phụ cấp, thưởng) — GROSS sẽ được tính ngược ra */
        private Long baseSalary;
        /** Mức lương đóng thuế/bảo hiểm — bảo hiểm (NLĐ & DN) tính cố định trên mức này. Trống → = baseSalary. */
        private Long insuranceSalary;
        /** Tổng phụ cấp (tương thích cũ) — nếu có {@code allowances} thì bỏ qua field này. */
        private Long allowance;
        /** Chi tiết từng khoản phụ cấp (nhãn + số tiền + có tính thuế TNCN không). */
        private List<AllowanceItemDto> allowances;
        private Long bonus;
        /** Thưởng có tính vào thu nhập chịu thuế TNCN không. */
        private Boolean bonusTaxable;
        /** Số người phụ thuộc — dùng tính giảm trừ gia cảnh khi suy ngược GROSS */
        private Integer dependents;
        /** Kỳ lương — nếu có, HR sẽ nạp thêm phụ cấp/thưởng import từ MonthlyAdjustment. */
        private Integer month;
        private Integer year;
        /** Nhân viên part-time (bán thời gian) — mỗi ngày đi làm = 0.5 công. */
        private Boolean partTime;
    }

    /** Một khoản phụ cấp: nhãn + số tiền + có tính thuế TNCN không. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllowanceItemDto {
        private String label;
        private Long amount;
        /** true = tính vào thu nhập chịu thuế TNCN. Bảo hiểm KHÔNG phụ thuộc phụ cấp. */
        private boolean taxable;
    }

    /** Nhãn phụ cấp trong danh mục (để chọn lại). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllowanceLabelDto {
        private Long id;
        private String name;
        private boolean system;
    }

    @Data
    public static class AllowanceLabelCreateRequest {
        private String name;
    }

    @Data
    public static class BatchSalaryRequest {
        private List<Long> userIds;
        /** Lương NET thực nhận/tháng (KHÔNG gồm phụ cấp, thưởng) — GROSS sẽ được tính ngược ra */
        private Long baseSalary;
        /** Mức lương đóng thuế/bảo hiểm — áp dụng chung cho cả batch. Trống → = baseSalary. */
        private Long insuranceSalary;
        private Long allowance;
        private Long bonus;
        /** Số người phụ thuộc — áp dụng chung cho tất cả nhân viên trong batch */
        private Integer dependents;
        /** Nhân viên part-time (bán thời gian) — áp dụng chung cho cả batch. */
        private Boolean partTime;
    }

    @Data
    public static class ApproveSalaryRequest {
        // nobody needed; approved by current user
    }

    @Data
    public static class BulkApproveSalaryRequest {
        private List<Long> salaryIds;
    }

    @Data
    public static class RejectSalaryRequest {
        private String rejectReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SalaryDto {
        private Long id;
        private Long userId;
        private String userFullName;
        private String department;
        private String division;
        private String position;
        private Long baseSalary;
        private Long insuranceSalary;
        private Boolean insuranceExempt;
        private Long allowance;
        private List<AllowanceItemDto> allowances;
        private Long bonus;
        private Boolean bonusTaxable;
        private Integer dependents;
        private String status;
        private String rejectReason;
        private Long createdAt;
        private Long updatedAt;
        /** Nhân viên part-time (bán thời gian). */
        private Boolean partTime;
        private String createdByName;
        private String approvedByName;
    }

    /**
     * LƯƠNG HIỆN HÀNH + PHIẾU LƯƠNG MỚI ĐANG CHỜ DUYỆT của một nhân viên.
     *
     * <p>Trang "Nhân viên" của OWNER cần cả hai để dựng màn hình so sánh
     * (card lương cũ bên trái, card lương mới bên phải). Gộp vào một lượt gọi
     * thay vì bắt FE gọi hai API rồi tự ghép — hai lần gọi có thể rơi vào hai
     * thời điểm khác nhau và hiển thị lệch nhau.
     *
     * <ul>
     *   <li>{@code current} — bản ghi APPROVED mới nhất; null nếu chưa từng
     *       được duyệt lương lần nào.</li>
     *   <li>{@code pending} — bản ghi PENDING mới nhất; null nếu không có
     *       phiếu nào đang chờ.</li>
     * </ul>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SalaryOverviewDto {
        private Long userId;
        private String userFullName;
        private SalaryDto current;
        private SalaryDto pending;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SalaryBreakdownDto {
        private Long userId;
        private String userFullName;
        private String department;
        private String division;
        private String position;
        private String status;            // PENDING | APPROVED | REJECTED — trạng thái bản ghi lương hiện hành

        // ── Lương đã nhập (SUPER_ACCOUNTANT nhập NET thực nhận, không gồm phụ cấp/thưởng) ──
        private Long baseSalary;          // Lương NET thực nhận DÙNG ĐỂ TÍNH (đã prorate theo giờ nếu là Xưởng)
        private Long standardBaseSalary;  // Lương NET chuẩn đã nhập khi đủ công (trước khi prorate theo giờ)
        private Long allowance;           // TỔNG phụ cấp
        private List<AllowanceItemDto> allowances; // Chi tiết từng khoản phụ cấp (nhãn + tiền + taxable)
        private Long bonus;               // Thưởng GỐC đã nhập (trước khi nhân KPI)
        private Long effectiveBonus;      // Thưởng THỰC LĨNH = bonus × KPI%
        private Boolean bonusTaxable;     // Thưởng có tính vào thu nhập chịu thuế không
        private Integer dependents;       // Số người phụ thuộc dùng để tính giảm trừ gia cảnh

        /**
         * LƯƠNG KHOÁN THEO HỢP ĐỒNG BÊN THỨ BA (hiện chỉ Bảo vệ xưởng).
         *
         * <p>TRUE nghĩa là mọi con số bảo hiểm / thuế / phụ cấp trong DTO này đều
         * bằng 0 một cách CÓ CHỦ ĐÍCH, không phải do thiếu dữ liệu. FE dựa vào cờ
         * này để ẩn hẳn các dòng đó thay vì hiển thị "0đ" gây hiểu nhầm là chưa
         * khai báo hồ sơ bảo hiểm.
         */
        private Boolean flatContract;

        // ── THÂM NIÊN ─────────────────────────────────────────────────────────
        //   seniorityAllowance ĐÃ nằm trong `allowance`/`allowances` (một dòng
        //   phụ cấp như mọi khoản khác). Ba field dưới đây tách ra để FE hiện
        //   được "6 năm — 7%" mà không phải parse ngược từ nhãn.
        /** Ngày vào làm việc (epoch millis) — null nếu chưa khai báo. */
        private Long workStartDate;
        /** Mốc chốt thâm niên = ngày hoàn tất chấm công của kỳ (epoch millis). */
        private Long seniorityReferenceDate;
        /** Số năm thâm niên TRÒN tại kỳ lương này. */
        private Integer seniorityYears;
        /** % phụ cấp thâm niên (0, hoặc 2..10). */
        private Integer seniorityPercent;
        /** Tiền phụ cấp thâm niên = lương cơ bản chuẩn × %. */
        private Long seniorityAllowance;

        // ── Phân loại phần chịu thuế / không chịu thuế (phụ cấp + thưởng) ──────
        private Long taxableAdditions;    // Tổng phụ cấp/thưởng CÓ tính thuế TNCN
        private Long nonTaxableAdditions; // Tổng phụ cấp/thưởng KHÔNG tính thuế (cộng thẳng)

        // ── KPI & công thực tế ────────────────────────────────────────────────
        private Double kpiPercent;        // Tỷ lệ KPI đạt được (thang 100) — dùng nhân vào thưởng
        private Boolean hourlyBased;      // true nếu lương tính theo giờ (nhân viên Xưởng/Sản xuất)
        private Double standardWorkHours; // Số giờ công chuẩn trong tháng (208) — chỉ dùng khi hourlyBased
        private Double actualWorkHours;   // Số giờ công thực tế trong tháng — chỉ dùng khi hourlyBased

        // ── Lương chia theo NGÀY CÔNG (bộ phận Xưởng sản xuất) ────────────────
        private Boolean attendanceProrated; // true nếu lương đã chia theo ngày công thực tế
        private Double standardWorkdays;    // Công chuẩn 1 tháng (26)
        private Double actualWorkdays;      // Công thực tế lấy từ bảng chấm công (VD 22.64)
        private Long dailyRate;             // Đơn giá 1 ngày công = lương chuẩn / 26 (đã làm tròn về đồng)
        private Integer payrollMonth;       // Kỳ lương dùng để lấy công thực tế
        private Integer payrollYear;

        // ── GROSS ──────────────────────────────────────────────────────────────
        private Long grossSalary;         // Lương GROSS TỔNG (gồm mọi khoản, đã gross-up phần chịu thuế)
        // Mức lương đóng thuế/bảo hiểm đã nhập (căn cứ đóng BH — bảo hiểm tính cố định trên mức này)
        private Long insuranceSalary;

        private boolean insuranceExempt;

        // ── Bảo hiểm NGƯỜI LAO ĐỘNG đóng (10.5% trên insuranceSalary) — breakdown ──
        private Long employeeSocialInsurance;       // BHXH 8%
        private Long employeeHealthInsurance;       // BHYT 1.5%
        private Long employeeUnemploymentInsurance; // BHTN 1%
        private Long employeeInsuranceTotal;        // 10.5%

        // ── Bảo hiểm DOANH NGHIỆP đóng (21.5% trên insuranceSalary) — breakdown ──
        private Long employerSocialInsurance;       // BHXH 17%
        private Long employerAccidentInsurance;     // BH TNLĐ-BNN 0.5%
        private Long employerHealthInsurance;       // BHYT 3%
        private Long employerUnemploymentInsurance; // BHTN 1%
        private Long employerInsuranceTotal;        // 21.5%

        // ── Thuế TNCN ──────────────────────────────────────────────────────────
        private Long preTaxIncome;        // Thu nhập trước thuế = GROSS - bảo hiểm NLĐ
        private Long personalDeduction;   // Giảm trừ bản thân (15.5tr)
        private Long dependentDeduction;  // Giảm trừ người phụ thuộc (6.2tr × n)
        private Long taxableIncome;       // Thu nhập chịu thuế
        private Long personalIncomeTax;   // Tổng thuế TNCN
        private List<PitBracketDto> pitBrackets; // Chi tiết thuế theo từng bậc

        // ── Tổng hợp ─────────────────────────────────────────────────────────
        private Long netSalary;           // Lương thực nhận CUỐI CÙNG, ĐÃ làm tròn hàng nghìn
        private Long netSalaryExact;      // Số TẠM TÍNH trước khi làm tròn (còn lẻ tới đồng)
        private Long totalCost;           // Tổng chi phí doanh nghiệp = GROSS + BH doanh nghiệp đóng

        /**
         * TÀI XẾ — Thưởng theo đơn hàng (motorbike + truck) đã cộng vào {@code bonus}.
         * FE tách ra hiển thị dòng "Thưởng đơn hàng" trước dòng "Thưởng KPI" cho tài xế.
         */
        private Long driverOrderBonus;

        /** Chi tiết thưởng đơn hàng tách theo loại xe — dùng cho FE render 3 dòng. */
        private DriverOrderBonusDto driverOrderBonusDetail;

        /**
         * Chi tiết các khoản thưởng IMPORT từ Excel "Thưởng theo tháng" — mỗi khoản
         * là 1 nhãn riêng (VD "Chuyên cần", "Tháng 13"…) để FE render THÀNH TỪNG DÒNG
         * thay vì gộp chung vào "Thưởng KPI". {@code bonusItemsTotal} = tổng số tiền
         * của các dòng này (đã KHÔNG nhân KPI% — thưởng chuyên cần không phụ thuộc KPI).
         */
        private java.util.List<BonusItemDto> bonusItems;
        private Long bonusItemsTotal;

        /**
         * Phần thưởng KPI thuần (bonusInput × kpiPercent), tách khỏi imported bonus.
         * FE render dòng "Thưởng KPI — đạt X%" dựa trên trường này, không dùng
         * {@code effectiveBonus} nữa (vì {@code effectiveBonus} đang gộp cả imported
         * để tương thích ngược).
         */
        private Long effectiveBonusKpiOnly;
    }

    /** Một khoản thưởng import theo tháng — có label riêng để render 1 dòng. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BonusItemDto {
        private String label;
        private Long amount;
    }

    /**
     * Chi tiết thưởng "KPI 100%" của tài xế — GOM cả 3 nguồn:
     *   1. Tiền xăng (chỉ xe máy) = totalKm × giá xăng/km
     *   2. Thưởng đơn hàng xe máy = motorbikeTrips × đơn giá thưởng xe máy
     *   3. Thưởng đơn hàng xe tải = truckTrips × đơn giá thưởng xe tải
     *
     * <p>{@code totalAmount = gasAmount + motorbikeAmount + truckAmount} và
     * chính là số hiển thị ở dòng "Thưởng KPI — đạt 100%" trên phiếu lương
     * của tài xế. Ba trường {@code gas*} là mới (2026) — bản trước chỉ có
     * moto/truck, gas đứng ở allowances.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DriverOrderBonusDto {
        /** Số lượt xe máy trong tháng. */
        private Integer motorbikeTrips;
        /** Thưởng xe máy = motorbikeTrips × đơn giá thưởng xe máy. */
        private Long motorbikeAmount;
        /** Số lượt xe tải trong tháng. */
        private Integer truckTrips;
        /** Thưởng xe tải = truckTrips × đơn giá thưởng xe tải. */
        private Long truckAmount;
        /** Tổng số km xe máy đã chạy trong tháng (chỉ xe máy tính tiền xăng). */
        private Double gasKm;
        /** Đơn giá xăng (đồng/km) OWNER nhập ngày tính lương. */
        private Long gasPrice;
        /** Tiền xăng = round(gasKm × gasPrice). */
        private Long gasAmount;
        /** Tổng = gasAmount + motorbikeAmount + truckAmount. */
        private Long totalAmount;
    }

    /** Chi tiết thuế TNCN của một bậc lũy tiến. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PitBracketDto {
        private Integer ratePercent;      // 5, 10, 20, 30, 35
        private Long incomeInBracket;     // phần thu nhập tính thuế rơi vào bậc này
        private Long taxInBracket;        // tiền thuế của riêng bậc này
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SalaryBreakdownSummaryDto {
        private List<SalaryBreakdownDto> rows;
        // ── Tổng cộng tất cả nhân viên ───────────────────────────────────────
        private Long totalBaseSalary;
        private Long totalAllowance;
        private Long totalBonus;
        private Long totalEmployerInsurance;
        private Long totalEmployeeInsurance;
        private Long totalPersonalIncomeTax;
        private Long totalNetSalary;
        private Long totalCost;
    }

    // ── Employee Info ─────────────────────────────────────────────────────────

    @Data
    public static class UpdateEmployeeInfoRequest {
        private String department;
        private String division;
        private String position;

        /**
         * NGÀY VÀO LÀM VIỆC (epoch millis) — căn cứ tính thâm niên.
         *
         * <p>{@code null} = KHÔNG ĐỔI (giữ nguyên giá trị đang có), khớp với cách
         * xử lý của 3 field trên. Muốn XOÁ ngày vào làm thì gửi {@code 0}.
         */
        private Long workStartDate;

        /**
         * SỐ TÀI KHOẢN NGÂN HÀNG (để chi lương qua NH).
         *
         * <p>{@code null} = KHÔNG ĐỔI. Chuỗi RỖNG {@code ""} = xoá trắng
         * (nhân viên không còn tài khoản, cột số TK trong file NH sẽ để trống).
         * Chuỗi khác rỗng = ghi đè.
         */
        private String bankAccountNumber;

        /**
         * TÊN NGÂN HÀNG. Quy ước null / rỗng giống {@link #bankAccountNumber}.
         */
        private String bankName;

        /**
         * ĐANG NGHỈ THAI SẢN — {@code true} = tạm dừng chi lương qua ngân hàng.
         *
         * <p>{@code null} = KHÔNG ĐỔI. Gửi {@code true}/{@code false} rõ ràng
         * khi HR muốn bật/tắt trạng thái.
         */
        private Boolean onMaternityLeave;
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    @Data
    public static class LeaveRequestCreate {
        private Long userId;
        private String leaveType; // PAID | UNPAID
        private Long leaveDate;
        private Long leaveEndDate;
        private Double leaveDays;
        private String handoverTo;
        private String contactPhone;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaveRequestDto {
        private Long id;
        private Long userId;
        private String userFullName;
        private String department;
        private String position;
        private String leaveType;
        private Long leaveDate;
        private Long leaveEndDate;
        private Double leaveDays;
        private String handoverTo;
        private String contactPhone;
        private String note;
        private Long createdAt;
        private String createdByName;
    }

    // ── Overtime ──────────────────────────────────────────────────────────────

    @Data
    public static class OvertimeRequestCreate {
        private Long otDate;
        private String startTime;
        private String endTime;
        private Double otHours;
        private String reason;
        private List<Long> userIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OvertimeRequestDto {
        private Long id;
        private Long otDate;
        private String startTime;
        private String endTime;
        private Double otHours;
        private String reason;
        private List<OtEmployeeDto> employees;
        private Long createdAt;
        private String createdByName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtEmployeeDto {
        private Long userId;
        private String fullName;
        private String department;
        private String position;
    }
}