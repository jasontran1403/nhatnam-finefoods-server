package com.nhatnam.server.dto.income;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EmployeeSuggestionDto {
    private Long id;
    private String fullName;
    /** Chức vụ tính lương (payrollRole) hiển thị cho FE */
    private String payrollRole;
    /** Chức vụ / vị trí công việc */
    private String position;
}