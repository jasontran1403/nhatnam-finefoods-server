package com.nhatnam.server.dto.hr;

import lombok.*;

import java.util.List;

public class HrDtos {

    // ── Salary ────────────────────────────────────────────────────────────────

    @Data
    public static class SalaryRequest {
        private Long userId;
        private Long baseSalary;
        private Double socialInsuranceRate;
        private Long socialInsuranceSalary;
        private Long bonus;
        private Long mealAllowance;
        private Long transportAllowance;
    }

    @Data
    public static class BatchSalaryRequest {
        private List<Long> userIds;
        private Long baseSalary;
        private Double socialInsuranceRate;
        private Long socialInsuranceSalary;
        private Long bonus;
        private Long mealAllowance;
        private Long transportAllowance;
    }

    @Data
    public static class ApproveSalaryRequest {
        // no body needed; approved by current user
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
        private String position;
        private Long baseSalary;
        private Double socialInsuranceRate;
        private Long socialInsuranceSalary;
        private Long bonus;
        private Long mealAllowance;
        private Long transportAllowance;
        private String status;
        private String rejectReason;
        private Long createdAt;
        private Long updatedAt;
        private String createdByName;
        private String approvedByName;
    }

    // ── Employee Info ─────────────────────────────────────────────────────────

    @Data
    public static class UpdateEmployeeInfoRequest {
        private String department;
        private String position;
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

    // ── Payslip ───────────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayslipDto {
        private Long userId;
        private String userFullName;
        private String department;
        private String position;
        private int month;
        private int year;
        private Long periodStart;
        private Long periodEnd;

        // Lương cơ bản & phụ cấp
        private Long baseSalary;
        private Long mealAllowance;
        private Long transportAllowance;
        private Long bonus;

        // Công
        private Double standardWorkdays;   // công chuẩn (T2-T6=1, T7=0.5)
        private Double actualWorkdays;     // công thực tế (trừ nghỉ ko phép)
        private Double unpaidLeaveDays;    // ngày nghỉ không lương
        private Double paidLeaveDays;      // ngày nghỉ có lương

        // OT
        private Double otHours;            // tổng giờ OT
        private Long otPay;               // tiền OT (150% lương giờ)

        // BHXH
        private Double socialInsuranceRate;
        private Long socialInsuranceSalary;
        private Long socialInsuranceAmount; // = socialInsuranceSalary * rate / 100

        // Tổng
        private Long grossSalary;          // lương gộp trước khấu trừ
        private Long totalDeductions;      // tổng khấu trừ (BHXH)
        private Long netSalary;            // thực nhận
    }
}
