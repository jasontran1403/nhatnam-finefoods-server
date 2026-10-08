package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.materialprice.MaterialPriceTrackingDtos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.MaterialPriceTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Biến động giá nguyên liệu — OWNER/ADMIN/SUPER_ACCOUNTANT/ACCOUNTANT.
 */
@RestController
@RequestMapping("/api/material-price-tracking")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SUPER_ACCOUNTANT','ACCOUNTANT')")
public class MaterialPriceTrackingController {

    private final MaterialPriceTrackingService service;

    /** Danh sách nguyên liệu (sản xuất) + sản phẩm đồ dùng tiêu hao */
    @GetMapping("/materials")
    public ApiResponse<List<MaterialItemDto>> listMaterials() {
        return ApiResponse.ok(service.listMaterials());
    }

    /** Dữ liệu chart cho 1 nguyên liệu */
    @GetMapping("/chart")
    public ApiResponse<PriceChartResponse> chart(@RequestParam String materialName) {
        return ApiResponse.ok(service.getChartData(materialName));
    }

    /** Thêm giá mới */
    @PostMapping("/entries")
    public ApiResponse<PricePointDto> addEntry(@RequestBody CreatePriceEntryRequest req,
                                               Authentication auth) {
        User user = (User) auth.getPrincipal();
        String name = user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName() : user.getUsername();
        return ApiResponse.ok(service.addEntry(req, name));
    }
}
