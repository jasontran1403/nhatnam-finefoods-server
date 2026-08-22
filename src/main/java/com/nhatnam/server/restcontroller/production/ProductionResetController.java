package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.service.ProductionResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ CHỈ DÙNG TRONG MÔI TRƯỜNG TEST — xoá sạch + khởi tạo lại dữ liệu module
 * sản xuất (xem ProductionResetService để biết chi tiết phạm vi & thứ tự xoá).
 *
 * Hành động KHÔNG THỂ HOÀN TÁC. Sau khi dùng xong ở môi trường test, NÊN XOÁ
 * file controller này (và ProductionResetService) khỏi codebase trước khi
 * deploy lên môi trường có dữ liệu thật, để tránh bấm nhầm gây mất dữ liệu.
 *
 * Quyền hạn: chỉ OWNER/ADMIN/SUPERADMIN được gọi (xem SecurityConfiguration,
 * rule riêng cho /api/owner/production-reset/** — KHÔNG dùng chung rule với
 * /api/owner/production/** vì rule đó cũng cho FACTORY_WORKER gọi được).
 */
@RestController
@RequiredArgsConstructor
public class ProductionResetController {

    private final ProductionResetService resetService;

    /** Chỉ xoá dữ liệu, không tạo lại FactoryProduct — dùng khi muốn tự tạo lại tay */
    @PostMapping("/api/owner/production-reset/reset-only")
    public ApiResponse<ProductionResetService.ResetResult> resetOnly() {
        return ApiResponse.ok(resetService.resetAll());
    }

    /** Xoá sạch + tự động tạo lại FactoryProduct từ toàn bộ Ingredient hiện có — dùng cho việc test lại từ đầu */
    @PostMapping("/api/owner/production-reset/reset-and-seed")
    public ApiResponse<ProductionResetService.ResetAndSeedResult> resetAndSeed() {
        return ApiResponse.ok(resetService.resetAndSeed());
    }
}