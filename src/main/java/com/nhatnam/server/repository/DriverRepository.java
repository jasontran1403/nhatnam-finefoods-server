package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {

    /** Tài xế gắn với 1 tài khoản (quan hệ 1–1). */
    Optional<Driver> findByUser_Id(long userId);

    List<Driver> findByActiveTrueOrderByNameAsc();

    /**
     * Tài xế THẬT đang hoạt động — bỏ các bản ghi {@code systemDriver = true}
     * ("không xử lý": Grab, Giao tại kho, Khách tự lấy…).
     * Dùng cho màn điểm danh ODO và báo cáo ODO.
     */
    List<Driver> findByActiveTrueAndSystemDriverFalseOrderByNameAsc();

    /** Tài xế thật, kể cả đã ngưng hoạt động. */
    List<Driver> findBySystemDriverFalseOrderByNameAsc();
    List<Driver> findByNameContainingIgnoreCaseAndActiveTrue(String name);
    List<Driver> findByActiveTrueAndVehicleTypeInOrderByNameAsc(List<Driver.VehicleType> types);
    List<Driver> findByNameContainingIgnoreCaseAndActiveTrueAndVehicleTypeIn(String name, List<Driver.VehicleType> types);

    /** Tài xế CHƯA gắn tài khoản nào (dùng cho backfill và khi ghép theo tên). */
    List<Driver> findByUserIsNull();

    /** Tìm tài xế chưa gắn tài khoản theo ĐÚNG tên (bỏ qua hoa/thường). */
    List<Driver> findByUserIsNullAndNameIgnoreCase(String name);
}