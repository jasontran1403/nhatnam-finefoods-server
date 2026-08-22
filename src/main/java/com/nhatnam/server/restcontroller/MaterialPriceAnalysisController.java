package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.MaterialPriceAnalysisDto;
import com.nhatnam.server.service.SupplierManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phân tích giá nguyên liệu — GỘP ĐA-NHÀ-CUNG-CẤP theo TÊN nguyên liệu.
 *
 * Khác với {@link SupplierManagementController} (phân tích giá trong phạm vi 1 NCC),
 * endpoint này gộp toàn bộ lịch sử mua của cùng 1 tên nguyên liệu trên MỌI NCC.
 * Được mở từ trang Sản xuất → Tồn kho nguyên liệu.
 */
@RestController
@RequiredArgsConstructor
public class MaterialPriceAnalysisController {

    private static final String ROLES_READ =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','ACCOUNTANT','SUPER_ACCOUNTANT')";

    private final SupplierManagementService service;

    /** Phân tích giá 1 nguyên liệu theo tên — gộp mọi NCC. */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/material-price-analysis")
    public ApiResponse<MaterialPriceAnalysisDto> analyze(@RequestParam String name) {
        return ApiResponse.ok(service.getMaterialPriceAnalysis(name));
    }
}
