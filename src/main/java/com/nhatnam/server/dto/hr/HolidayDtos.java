package com.nhatnam.server.dto.hr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO cho module Ngày lễ (Phase 1).
 */
public class HolidayDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HolidayDto {
        private Long id;
        private LocalDate date;
        private Integer year;
        private String name;
        private Long createdAt;
        private String createdByName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateHolidayRequest {
        private LocalDate date;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportHolidayResult {
        /** Tổng số dòng trong file. */
        private int totalRows;
        /** Số ngày lễ đã lưu (mới + trùng). */
        private int saved;
        /** Số dòng bỏ qua do lỗi parse ngày hoặc năm sai. */
        private int skipped;
        /** Danh sách ngày lễ đã lưu để FE hiển thị preview. */
        private List<HolidayDto> items;
        /** Mô tả lỗi để OWNER kiểm tra. */
        private List<String> errors;
    }
}
