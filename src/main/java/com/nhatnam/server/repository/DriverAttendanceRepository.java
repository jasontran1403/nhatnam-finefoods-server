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
}
