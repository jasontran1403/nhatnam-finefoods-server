package com.nhatnam.server.dto.hr;

import lombok.*;

import java.util.List;

public class PayrollDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayrollBatchDto {
        private Long id;
        private Integer month;
        private Integer year;
        private String status;
        private Integer employeeCount;
        private String rejectReason;
        private Long createdAt;
        private Long updatedAt;
        private Long importedAt;
        private Long approvedAt;
        private String createdByName;
        private String approvedByName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayslipDto {
        private Long id;
        private Long batchId;
        private Integer month;
        private Integer year;
        private Long userId;
        private String userFullName;
        private String department;
        private String division;
        private String position;

        private Long baseSalary;
        private Integer dependents;
        private Double standardWorkdays;
        private Double actualWorkdays;
        private Long bonus;
        private Long allowance;
        private Long insuranceSalary;

        private Long actualSalary;
        private Long grossSalary;
        private Long socialInsuranceAmount;
        private Long healthInsuranceAmount;
        private Long unemploymentInsuranceAmount;
        private Long totalInsuranceAmount;
        private Long preTaxIncome;
        private Long personalDeduction;
        private Long dependentDeduction;
        private Long taxableIncome;
        private Long personalIncomeTax;
        private Long netSalary;
    }

    @Data
    public static class RejectBatchRequest {
        private String rejectReason;
    }
}
