package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.DriverAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DriverAttendanceRepository extends JpaRepository<DriverAttendance, Long> {

    /** Lấy toàn bộ record của 1 ngày */
    List<DriverAttendance> findByAttendanceDateOrderByCreatedAtAsc(String date);

    /** Lấy record cụ thể theo ngày + session + tài xế + loại xe */
    Optional<DriverAttendance> findByAttendanceDateAndSessionTypeAndDriverAndVehicleType(
            String date,
            DriverAttendance.SessionType sessionType,
            Driver driver,
            Driver.VehicleType vehicleType);

    /** Lấy theo ngày + session (để hiển thị toàn bộ vào ca / kết ca) */
    List<DriverAttendance> findByAttendanceDateAndSessionTypeOrderByCreatedAtAsc(
            String date, DriverAttendance.SessionType sessionType);

    @Query("SELECT a FROM DriverAttendance a WHERE a.attendanceDate BETWEEN :from AND :to ORDER BY a.attendanceDate DESC, a.createdAt ASC")
    List<DriverAttendance> findByDateRange(@Param("from") String from, @Param("to") String to);

    /**
     * SỐ ODO ĐÃ GHI GẦN NHẤT TRƯỚC MỘT NGÀY, của cùng tài xế + cùng loại xe.
     *
     * <p>Công-tơ-mét chỉ tăng, không bao giờ quay ngược. Đây là mốc sàn để chặn
     * việc gõ nhầm một số nhỏ hơn — nếu không, số km của ngày sau sẽ ra âm và
     * báo cáo bị lệch mà không ai phát hiện ra cho tới cuối tháng.
     *
     * <p>{@code attendanceDate} lưu dạng "yyyy-MM-dd" nên so sánh chuỗi cũng
     * chính là so sánh thời gian.
     */
    @Query("""
           SELECT a FROM DriverAttendance a
            WHERE a.driver = :driver
              AND a.vehicleType = :vehicleType
              AND a.attendanceDate < :date
            ORDER BY a.attendanceDate DESC, a.odometer DESC
           """)
    List<DriverAttendance> findPreviousBefore(@Param("driver") Driver driver,
                                              @Param("vehicleType") Driver.VehicleType vehicleType,
                                              @Param("date") String date);
}
