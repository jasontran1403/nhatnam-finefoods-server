package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.supply.OfficeSupplyDtos.*;
import com.nhatnam.server.service.OfficeSupplyOrderPdfService;
import com.nhatnam.server.service.OfficeSupplyService;
import com.nhatnam.server.service.SupplyItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * API Đăng ký và Đặt hàng Văn phòng phẩm (VPP).
 *
 * <pre>
 *   Nhân viên  : /api/office-supply/my-request/**
 *   Owner/Admin: /api/office-supply/admin/**
 * </pre>
 */
@RestController
@RequestMapping("/api/office-supply")
@RequiredArgsConstructor
public class OfficeSupplyController {

    /**
     * Mọi nhân viên có tài khoản đều được gửi yêu cầu VPP — sau refactor nav
     * thêm entry /office-supply cho toàn bộ role trừ OWNER/ADMIN (họ có trang
     * tổng hợp riêng). Nếu quên role nào ở đây, user role đó sẽ vào page được
     * (route mở) nhưng bấm Gửi sẽ 403 → giữ danh sách này KHỚP 100% với
     * allowedRoles của route /office-supply trong routes/index.jsx.
     */
    private static final String ALL_STAFF =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN'," +
                    "'SUPER_SELLER','SELLER'," +
                    "'SUPER_ACCOUNTANT','ACCOUNTANT'," +
                    "'SUPER_WAREHOUSE','WAREHOUSE'," +
                    "'SUPER_FACTORY_WORKER','FACTORY_WORKER','FACTORY_STAFF'," +
                    "'FACTORY_ACCOUNTANT','FACTORY_PRODUCTION_WORKER'," +
                    "'FACTORY_MANAGER','FACTORY_SECURITY'," +
                    "'DRIVER','HR','OPERATOR','SECURITY')";

    private static final String OWNER_ADMIN =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN')";

    /** Owner/Admin + role PURCHASING cho các endpoint vận hành đơn VPP
     *  (xem tổng hợp, in PDF, đặt hàng). */
    private static final String OWNER_ADMIN_PURCHASING =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','PURCHASING')";

    private final OfficeSupplyService service;
    private final SupplyItemService supplyItemService;
    private final OfficeSupplyOrderPdfService orderPdfService;

    // ─────────────────────────────────────────────────────────────────────────
    // DANH MỤC VẬT DỤNG
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Danh sách vật dụng kèm thống kê (số lần mua, ngày mua gần nhất).
     * Dùng cho trang đăng ký nhân viên và trang kho VPP owner.
     */
    @PreAuthorize(ALL_STAFF)
    @GetMapping("/items")
    public ApiResponse<List<OfficeItemDto>> items(
            @RequestParam(required = false) Long warehouseId) {
        return ApiResponse.ok(service.listItems(warehouseId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PHIẾU ĐĂNG KÝ CỦA NHÂN VIÊN
    // ─────────────────────────────────────────────────────────────────────────

    /** Phiếu đăng ký hiện tại của user đang đăng nhập. */
    @PreAuthorize(ALL_STAFF)
    @GetMapping("/my-request")
    public ApiResponse<List<MyRequestDto>> myRequests() {
        return ApiResponse.ok(service.myRequests());
    }

    /** Lưu / cập nhật phiếu đăng ký cho 1 văn phòng. */
    @PreAuthorize(ALL_STAFF)
    @PutMapping("/my-request")
    public ApiResponse<MyRequestDto> saveRequest(@RequestBody SaveRequestBody body) {
        return ApiResponse.ok(service.saveRequest(body));
    }

    /** Xóa phiếu đăng ký cho 1 văn phòng. */
    @PreAuthorize(ALL_STAFF)
    @DeleteMapping("/my-request")
    public ApiResponse<Void> deleteRequest(@RequestParam Long warehouseId) {
        service.deleteRequest(warehouseId);
        return ApiResponse.ok(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OWNER: tổng hợp + đặt hàng
    // ─────────────────────────────────────────────────────────────────────────

    /** Tổng hợp toàn bộ phiếu đăng ký của 1 văn phòng (preview trước khi đặt). */
    @PreAuthorize(OWNER_ADMIN_PURCHASING)
    @GetMapping("/admin/summary")
    public ApiResponse<OrderSummaryDto> summary(@RequestParam Long warehouseId) {
        return ApiResponse.ok(service.summary(warehouseId));
    }

    /**
     * Xuất phiếu đặt hàng VPP ra PDF — chỉ chứa yêu cầu ĐANG PENDING (gần nhất),
     * không có lịch sử. Owner tải file này để in và trình duyệt trước khi bấm
     * "Đặt hàng" (bấm xác nhận sẽ reset toàn bộ về 0).
     *
     * <p>Content-Disposition dùng {@code filename*=UTF-8''…} vì có ký tự tiếng
     * Việt trong tên file, browser cũ đọc filename ASCII fallback.
     */
    @PreAuthorize(OWNER_ADMIN_PURCHASING)
    @GetMapping("/admin/order-voucher/pdf")
    public ResponseEntity<byte[]> exportOrderVoucherPdf(@RequestParam Long warehouseId) {
        OrderSummaryDto summary = service.summary(warehouseId);
        byte[] pdf = orderPdfService.generate(summary);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        String prettyName  = "Phieu-dat-VPP-" + ts + ".pdf";
        String encodedName = URLEncoder.encode("Phiếu đặt VPP - " + ts + ".pdf",
                StandardCharsets.UTF_8).replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + prettyName + "\"; "
                        + "filename*=UTF-8''" + encodedName);
        headers.setContentLength(pdf.length);
        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    /**
     * OWNER bấm "Đặt hàng" — tạo đơn và clear toàn bộ request.
     *
     * <p>Body bắt buộc có giá cho từng vật dụng (lineAmount); phí (fees) tùy chọn.
     */
    @PreAuthorize(OWNER_ADMIN_PURCHASING)
    @PostMapping("/admin/place-order")
    public ApiResponse<OrderHistoryDto> placeOrder(@RequestParam Long warehouseId,
                                                   @RequestBody PlaceOrderBody body) {
        return ApiResponse.ok(service.placeOrder(warehouseId, body));
    }

    /** Lịch sử giá của 1 vật dụng ở 1 văn phòng — dùng cho trang biến động giá. */
    @PreAuthorize(OWNER_ADMIN)
    @GetMapping("/admin/items/{itemId}/price-history")
    public ApiResponse<PriceHistoryDto> priceHistory(@PathVariable Long itemId,
                                                     @RequestParam Long warehouseId) {
        return ApiResponse.ok(service.priceHistory(warehouseId, itemId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LỊCH SỬ ĐẶT HÀNG
    // ─────────────────────────────────────────────────────────────────────────

    /** Lịch sử đặt hàng của 1 văn phòng — mới nhất trước. */
    @PreAuthorize(OWNER_ADMIN)
    @GetMapping("/admin/orders")
    public ApiResponse<List<OrderHistoryDto>> orderHistory(@RequestParam Long warehouseId) {
        return ApiResponse.ok(service.orderHistory(warehouseId));
    }

    /** Chi tiết 1 lần đặt hàng. */
    @PreAuthorize(OWNER_ADMIN)
    @GetMapping("/admin/orders/{orderId}")
    public ApiResponse<OrderDetailDto> orderDetail(@PathVariable Long orderId) {
        return ApiResponse.ok(service.orderDetail(orderId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BÁO CÁO VẬT DỤNG
    // ─────────────────────────────────────────────────────────────────────────

    /** Báo cáo theo từng vật dụng: số lần mua, ngày gần nhất, TB khoảng cách/lần. */
    @PreAuthorize(OWNER_ADMIN)
    @GetMapping("/admin/item-report")
    public ApiResponse<List<ItemReportRowDto>> itemReport(@RequestParam Long warehouseId) {
        return ApiResponse.ok(service.itemReport(warehouseId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADMIN: DANH MỤC VẬT DỤNG (thêm mới / sửa)
    // ─────────────────────────────────────────────────────────────────────────

    /** Danh sách vật dụng cho trang "Danh sách văn phòng phẩm". */
    @PreAuthorize(OWNER_ADMIN)
    @GetMapping("/admin/catalog")
    public ApiResponse<List<AdminItemDto>> adminListItems() {
        return ApiResponse.ok(service.adminListItems());
    }

    /** Thêm mới vật dụng (Owner) — chỉ cần tên + ĐVT, spec là optional. */
    @PreAuthorize(OWNER_ADMIN)
    @PostMapping("/admin/catalog")
    public ApiResponse<AdminItemDto> adminCreateItem(@RequestBody SaveItemBody body) {
        return ApiResponse.ok(service.adminCreateItem(body, supplyItemService));
    }

    /** Sửa nhãn hiển thị (name/spec/unit) của vật dụng. */
    @PreAuthorize(OWNER_ADMIN)
    @PutMapping("/admin/catalog/{id}")
    public ApiResponse<AdminItemDto> adminUpdateItem(@PathVariable Long id,
                                                     @RequestBody SaveItemBody body) {
        return ApiResponse.ok(service.adminUpdateItem(id, body));
    }
}