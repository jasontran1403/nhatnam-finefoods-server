package com.nhatnam.server.dto.factorypayroll;

import lombok.Data;

import java.util.List;

/**
 * Request body cho API export file lương tổng hợp.
 *
 * <ul>
 *   <li>{@code departments} — danh sách bộ phận muốn xuất (MANAGEMENT, ACCOUNTING, FACTORY, SALES, WAREHOUSE).
 *       Tài xế (DRIVER) nằm chung trong WAREHOUSE khi xuất file.</li>
 *   <li>{@code exportType} — loại xuất: SALARY_ONLY (chỉ lương), BONUS_ONLY (chỉ thưởng),
 *       SALARY_AND_BONUS (lương + thưởng).</li>
 * </ul>
 */
@Data
public class SalaryExportRequest {
    private List<String> departments;
    private String exportType; // SALARY_ONLY | BONUS_ONLY | SALARY_AND_BONUS
}