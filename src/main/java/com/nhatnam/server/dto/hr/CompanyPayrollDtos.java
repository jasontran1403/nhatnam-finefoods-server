package com.nhatnam.server.dto.hr;

import com.nhatnam.server.enumtype.PayrollCalcStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTOs cho API {@code /api/company-payroll} — Phase 2.
 */
public class CompanyPayrollDtos {

    /** Trạng thái tổng thể của 1 tháng — FE dùng để dựng nút enable/disable. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompanyPeriodStatusDto {
        private int month;
        private int year;
        private PayrollCalcStatus calcStatus;

        // ── Trạng thái file ────────────────────────────────────────────────────
        private boolean hasAttendanceFile;
        private boolean hasExceptionFile;
        private boolean hasLeaveFile;

        private String attendanceFileName;
        private String exceptionFileName;
        private String leaveFileName;

        private Long attendanceUploadedAt;
        private Long exceptionUploadedAt;
        private Long leaveUploadedAt;

        // ── Mốc chuyển trạng thái ──────────────────────────────────────────────
        private Long calculatedAt;
        private String calculatedByName;
        private Long publishedAt;
        private String publishedByName;

        // ── Số liệu tổng OT (CALCULATED/PUBLISHED mới có) ──────────────────────
        private Long otTotalWeekdayMinutes;
        private Long otTotalSundayMinutes;
        private Long otTotalHolidayMinutes;
        private Long otTotalAmount;

        // ── Nguồn cho FE enable/disable nút ───────────────────────────────────
        private boolean canUploadAttendance;
        private boolean canUploadAdjustments;   // bonus/leave/allowance
        private boolean canCalculate;
        private boolean canPublish;
        private boolean canUnpublish;
        private boolean canReopen;

        // ── Số dòng adjustment đã import (hiện badge) ─────────────────────────
        private long bonusLineCount;
        private long allowanceLineCount;

        /** Số nhân viên có dòng chấm công sau khi parse. */
        private Integer parsedRows;

        // ── PHASE 4 — Lifecycle KPI xưởng ────────────────────────────────────
        private PayrollCalcStatus kpiCalcStatus;
        private Long kpiCalculatedAt;    private String kpiCalculatedByName;
        private Long kpiPublishedAt;     private String kpiPublishedByName;

        // ── PHASE 4 — Lifecycle Thưởng DT (Sales/Accounting) ─────────────────
        private PayrollCalcStatus bonusCalcStatus;
        private Long bonusCalculatedAt;  private String bonusCalculatedByName;
        private Long bonusPublishedAt;   private String bonusPublishedByName;

        /**
         * PHASE 6: TRUE nếu đã upload file chấm công VÀ đã bấm Tính lương.
         * FE dùng để enable nút "Chuyên cần".
         */
        private boolean canChuyenCan;
    }

    /** Kết quả 1 lần upload file (bất kể loại). */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UploadResultDto {
        private String fileName;
        private int parsedRows;
        private int matchedUsers;
        private int unmatchedRows;
        private List<String> warnings;
        private CompanyPeriodStatusDto status;
    }

    /** Kết quả bấm "Tính lương". */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CalculateResultDto {
        private int month;
        private int year;
        private int employeesComputed;
        private long otTotalAmount;
        private long otAllowanceRowsCreated;
        private List<String> warnings;
        private CompanyPeriodStatusDto status;
    }
}
