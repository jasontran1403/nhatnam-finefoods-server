package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.service.AddressCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * DANH MỤC HÀNH CHÍNH cho dropdown chọn tỉnh/thành và phường/xã.
 *
 * <p>Không giới hạn vai trò: đây là dữ liệu hành chính công khai, và mọi màn hình nhập
 * địa chỉ (tạo khách, sửa khách, thêm người nhận) đều cần — thêm rào quyền chỉ tạo ra
 * những lỗi 403 khó hiểu ở giữa luồng nhập liệu.
 */
@RestController
@RequestMapping("/api/address-catalog")
@RequiredArgsConstructor
@Log4j2
public class AddressCatalogController {

    private final AddressCatalogService addressCatalogService;

    /**
     * TOÀN BỘ danh mục — 34 tỉnh/thành kèm phường/xã.
     *
     * <p>Trả một lần thay vì tách hai endpoint tỉnh/phường: cả file chỉ ~180KB, nạp một
     * lượt rồi lọc tại chỗ cho dropdown phản hồi tức thì, không phải gọi lại mỗi lần
     * người dùng đổi tỉnh.
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> catalog() {
        return ApiResponse.ok(Map.of(
                "defaultProvince", AddressCatalogService.DEFAULT_PROVINCE,
                "provinces", addressCatalogService.getCatalog()));
    }

    /** Phường/xã của một tỉnh — dành cho client muốn tải lẻ. */
    @GetMapping("/wards")
    public ApiResponse<List<AddressCatalogService.Ward>> wards(@RequestParam String province) {
        return ApiResponse.ok(addressCatalogService.getWards(province));
    }
}
