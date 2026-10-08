package com.nhatnam.server.restcontroller.admin;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.gift.GiftRecordDtos.GiftRecordPage;
import com.nhatnam.server.dto.gift.GiftRecordDtos.HandlerOption;
import com.nhatnam.server.service.GiftManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API TRANG QUẢN LÝ QUÀ TẶNG — dành cho OWNER / ADMIN / SUPER_ACCOUNTANT.
 *
 * <p>Đường dẫn nằm dưới {@code /api/admin/**} nên đã được {@code SecurityConfiguration}
 * mở cho {@code ADMIN, OWNER, SUPER_ACCOUNTANT, HR}. HR không có nhu cầu xem trang này
 * nên bị loại thêm bằng {@link PreAuthorize} ở đây — chặn hai lớp cho đồng bộ với các
 * trang quản lý khác của nghiệp vụ bán hàng.
 *
 * <p>Xem {@link GiftManagementService} để hiểu nguồn dữ liệu (đơn hàng có sản phẩm KM
 * + phiếu tặng quà đã duyệt) và cách flatten mỗi sản phẩm thành một dòng.
 */
@RestController
@RequestMapping("/api/admin/gift-management")
@RequiredArgsConstructor
@Log4j2
public class GiftManagementController {

    private final GiftManagementService service;

    /**
     * Danh sách quà tặng đã flatten, có phân trang.
     *
     * @param q          search theo tên KH / mã đơn / SĐT / tên SP — debounce ở FE 600ms
     * @param from       epoch ms — null = không lọc cận dưới
     * @param to         epoch ms — null = không lọc cận trên
     * @param handlerId  id người tạo (seller) — null = tất cả
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPER_ACCOUNTANT','SUPERADMIN')")
    public ApiResponse<GiftRecordPage> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Long handlerId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.ok(service.list(q, from, to, handlerId, page, size));
        } catch (Exception e) {
            log.error("[GiftManagement] list error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Danh sách người tạo cho dropdown filter — merge distinct từ hai nguồn (đơn KM +
     * phiếu tặng quà đã duyệt), sort theo tên.
     */
    @GetMapping("/handlers")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPER_ACCOUNTANT','SUPERADMIN')")
    public ApiResponse<List<HandlerOption>> handlers() {
        try {
            return ApiResponse.ok(service.listHandlers());
        } catch (Exception e) {
            log.error("[GiftManagement] handlers error", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
