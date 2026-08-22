package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.ExpenseCategorySummaryDto;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.MaterialPriceAnalysisDto;
import com.nhatnam.server.service.ExpenseCategoryAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trang "Phân tích danh mục chi" — mở từ Quản lý nhà cung cấp.
 *
 * <ul>
 *   <li>GET /expense-categories — danh sách danh mục chi (nguyên liệu + khoản chi NCC),
 *       mỗi dòng kèm giá thấp nhất / cao nhất / gần nhất + tổng tiền đã mua/đã chi.</li>
 *   <li>GET /expense-category-analysis — phân tích chi tiết 1 danh mục, trả về cùng
 *       DTO với phân tích giá nguyên liệu để FE dùng chung 1 trang.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class ExpenseCategoryAnalysisController {

    private static final String ROLES_READ =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','ACCOUNTANT','SUPER_ACCOUNTANT')";

    private final ExpenseCategoryAnalysisService service;

    /**
     * @param search tìm theo tên danh mục chi / tên nguyên liệu (tuỳ chọn)
     * @param kind   MATERIAL | EXPENSE — bỏ trống = lấy cả hai
     */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/expense-categories")
    public ApiResponse<List<ExpenseCategorySummaryDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String kind) {
        return ApiResponse.ok(service.listCategories(search, kind));
    }

    /** Phân tích 1 danh mục chi (kind = MATERIAL → tái dùng phân tích giá nguyên liệu). */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/expense-category-analysis")
    public ApiResponse<MaterialPriceAnalysisDto> analyze(
            @RequestParam String name,
            @RequestParam(required = false, defaultValue = "MATERIAL") String kind) {
        return ApiResponse.ok(service.analyze(name, kind));
    }
}