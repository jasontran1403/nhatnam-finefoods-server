package com.nhatnam.server.dto.driver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Báo cáo ODO của MỘT tài xế trong một khoảng ngày (màn OWNER/ADMIN).
 *
 * <p>Tài xế loại {@code BOTH} chạy cả xe máy lẫn xe tải nên mỗi loại xe có đồng hồ
 * riêng — vì vậy số liệu km được tách theo {@link VehicleOdometer}, không gộp chung.
 */
@Data
@Builder
public class DriverOdometerReportDto {

    private Long driverId;
    private String driverName;
    /** TRUCK | MOTORBIKE | BOTH */
    private String vehicleType;
    private boolean active;

    /** Số liệu ODO tách theo từng loại xe tài xế thực sự có điểm danh trong kỳ. */
    private List<VehicleOdometer> vehicles;

    /** Tổng km của mọi loại xe cộng lại — tiện hiển thị nhanh trên card. */
    private Integer totalKm;

    /** Số đơn tài xế này giao trong khoảng ngày đã chọn. */
    private Integer orderCount;

    @Data
    @Builder
    public static class VehicleOdometer {
        /** TRUCK | MOTORBIKE */
        private String vehicleType;

        /** ODO đầu kỳ và NGÀY thực tế lấy được số đó (có thể muộn hơn ngày bắt đầu). */
        private Integer startOdometer;
        private String startDate;
        /** START = lấy từ lần điểm danh vào ca, END = ngày đó chỉ có kết ca. */
        private String startSession;

        /** ODO cuối kỳ và NGÀY thực tế lấy được số đó (có thể sớm hơn ngày kết thúc). */
        private Integer endOdometer;
        private String endDate;
        private String endSession;

        /** Tổng km của loại xe này trong kỳ = sum(END.odo - START.odo) các ngày có đủ cả START và END. */
        private Integer km;

        /** Số lần điểm danh ghi nhận được trong kỳ cho loại xe này. */
        private Integer recordCount;

        /** Số ngày trong kỳ có đủ cả START và END (đã tính km). */
        private Integer daysWithData;

        private List<OdoNote> odoNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OdoNote {
        private String date;       // "yyyy-MM-dd"
        private String session;    // "START" hoặc "END"
        private Integer odometer;  // số ODO lúc đó
        private String note;       // lý do người nhập ghi
    }
}