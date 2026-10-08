package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    Optional<Holiday> findByDate(LocalDate date);

    boolean existsByDate(LocalDate date);

    /** Tất cả ngày lễ trong 1 năm, xếp theo ngày tăng dần. */
    List<Holiday> findByYearOrderByDateAsc(Integer year);

    /** Ngày lễ trong khoảng (dùng để tính cho 1 tháng cụ thể). */
    @Query("SELECT h FROM Holiday h WHERE h.date >= :from AND h.date <= :to ORDER BY h.date ASC")
    List<Holiday> findInRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Xoá sạch 1 năm — dùng cho endpoint "nhập lại từ đầu". */
    long deleteByYear(Integer year);
}
