package com.nhatnam.server.repository;

import com.nhatnam.server.entity.AdjustmentImportFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdjustmentImportFileRepository extends JpaRepository<AdjustmentImportFile, Long> {

    /**
     * Tìm file đã tải cho (kỳ + type + label + department). {@code label} và
     * {@code department} có thể null nên dùng OR để so sánh chuỗi rỗng "".
     */
    @Query("SELECT a FROM AdjustmentImportFile a WHERE a.month = :month AND a.year = :year "
            + "AND a.type = :type "
            + "AND (COALESCE(a.label, '') = COALESCE(:label, '')) "
            + "AND (COALESCE(a.department, '') = COALESCE(:department, ''))")
    Optional<AdjustmentImportFile> findOne(@Param("month") int month, @Param("year") int year,
                                            @Param("type") String type,
                                            @Param("label") String label,
                                            @Param("department") String department);

    /** Xoá file cùng khoá trước khi lưu file mới (khi import lại). */
    @Modifying
    @Query("DELETE FROM AdjustmentImportFile a WHERE a.month = :month AND a.year = :year "
            + "AND a.type = :type "
            + "AND (COALESCE(a.label, '') = COALESCE(:label, '')) "
            + "AND (COALESCE(a.department, '') = COALESCE(:department, ''))")
    void deleteByKey(@Param("month") int month, @Param("year") int year,
                     @Param("type") String type,
                     @Param("label") String label,
                     @Param("department") String department);

    /** Xoá mọi file của bộ phận + type trong kỳ (dùng khi clear all). */
    @Modifying
    @Query("DELETE FROM AdjustmentImportFile a WHERE a.month = :month AND a.year = :year "
            + "AND a.type = :type "
            + "AND (COALESCE(a.department, '') = COALESCE(:department, ''))")
    void deleteByPeriodTypeAndDepartment(@Param("month") int month, @Param("year") int year,
                                          @Param("type") String type,
                                          @Param("department") String department);

    List<AdjustmentImportFile> findByMonthAndYearAndTypeAndDepartment(
            Integer month, Integer year, String type, String department);
}
