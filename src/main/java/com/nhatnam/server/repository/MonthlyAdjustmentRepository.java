package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MonthlyAdjustment;
import com.nhatnam.server.entity.MonthlyAdjustment.Type;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MonthlyAdjustmentRepository extends JpaRepository<MonthlyAdjustment, Long> {

    /** Toàn bộ khoản của 1 nhân viên trong kỳ — dùng khi dựng phiếu lương. */
    @Query("SELECT a FROM MonthlyAdjustment a WHERE a.user.id = :userId "
            + "AND a.month = :month AND a.year = :year ORDER BY a.id")
    List<MonthlyAdjustment> findByUserAndPeriod(@Param("userId") Long userId,
                                                @Param("month") int month,
                                                @Param("year") int year);

    /**
     * Chỉ lấy khoản của user trong kỳ CHO ĐÚNG BỘ PHẬN — dùng khi tính lương
     * để không lấy nhầm khoản của bộ phận khác (VD import cho tài xế mà file
     * lỡ có user xưởng, sẽ chỉ được tính cho tài xế). {@code null} = tương
     * thích cũ (dữ liệu chưa có department).
     */
    @Query("SELECT a FROM MonthlyAdjustment a WHERE a.user.id = :userId "
            + "AND a.month = :month AND a.year = :year "
            + "AND (a.department = :department OR a.department IS NULL) ORDER BY a.id")
    List<MonthlyAdjustment> findByUserPeriodAndDepartment(@Param("userId") Long userId,
                                                          @Param("month") int month,
                                                          @Param("year") int year,
                                                          @Param("department") String department);

    /** Toàn bộ khoản của CẢ KỲ — nạp 1 lần rồi gom theo user, tránh N+1 query. */
    @Query("SELECT a FROM MonthlyAdjustment a WHERE a.month = :month AND a.year = :year")
    List<MonthlyAdjustment> findByPeriod(@Param("month") int month, @Param("year") int year);

    List<MonthlyAdjustment> findByMonthAndYearAndType(Integer month, Integer year, Type type);

    long countByMonthAndYearAndType(Integer month, Integer year, Type type);

    /**
     * Xoá sạch khoản cùng loại của kỳ trước khi ghi file mới.
     * File Excel là nguồn sự thật duy nhất nên import lại = thay thế, không cộng dồn.
     */
    @Modifying
    @Query("DELETE FROM MonthlyAdjustment a WHERE a.month = :month AND a.year = :year AND a.type = :type")
    void deleteByPeriodAndType(@Param("month") int month, @Param("year") int year, @Param("type") Type type);

    // ══════════════════════════════════════════════════════════════════════════
    // THAO TÁC THEO TỪNG NHÃN
    // ══════════════════════════════════════════════════════════════════════════
    //
    // Một kỳ có thể có NHIỀU khoản thưởng khác nhau, mỗi khoản một file. Vì vậy
    // phạm vi thao tác phải là (kỳ + loại + NHÃN) chứ không phải (kỳ + loại) —
    // nếu không, upload khoản thứ hai sẽ xoá mất khoản thứ nhất.

    /** Các dòng của ĐÚNG MỘT khoản trong kỳ, kèm nhân viên để đối chiếu. */
    @Query("SELECT a FROM MonthlyAdjustment a JOIN FETCH a.user "
            + "WHERE a.month = :month AND a.year = :year AND a.type = :type AND a.label = :label")
    List<MonthlyAdjustment> findByPeriodTypeAndLabel(@Param("month") int month,
                                                     @Param("year") int year,
                                                     @Param("type") Type type,
                                                     @Param("label") String label);

    /** Toàn bộ dòng của một loại trong kỳ, kèm nhân viên — dựng danh sách khoản đã có. */
    @Query("SELECT a FROM MonthlyAdjustment a JOIN FETCH a.user "
            + "WHERE a.month = :month AND a.year = :year AND a.type = :type ORDER BY a.label, a.id")
    List<MonthlyAdjustment> findByPeriodAndTypeWithUser(@Param("month") int month,
                                                        @Param("year") int year,
                                                        @Param("type") Type type);

    /**
     * Xoá ĐÚNG MỘT khoản của kỳ. Dùng khi OWNER lỡ tải nhầm file: xoá khoản đó
     * rồi tải lại, các khoản khác của cùng tháng không bị đụng tới.
     */
    @Modifying
    @Query("DELETE FROM MonthlyAdjustment a "
            + "WHERE a.month = :month AND a.year = :year AND a.type = :type AND a.label = :label")
    void deleteByPeriodTypeAndLabel(@Param("month") int month,
                                    @Param("year") int year,
                                    @Param("type") Type type,
                                    @Param("label") String label);
}