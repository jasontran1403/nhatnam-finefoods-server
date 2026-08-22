package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.gift.GiftOrderDtos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.service.GiftOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API PHIẾU TẶNG QUÀ BẰNG SẢN PHẨM.
 *
 * <p>Seller tạo và theo dõi phiếu của mình; OWNER/ADMIN duyệt; nhân viên kho xử lý giao.
 */
@RestController
@RequestMapping("/api/gift-orders")
@RequiredArgsConstructor
@Log4j2
public class GiftOrderController {

    private final GiftOrderService giftOrderService;

    /**
     * Danh sách phiếu.
     *
     * <p>SELLER thường chỉ thấy PHIẾU DO MÌNH TẠO — ép {@code createdById} về id của
     * chính họ, bỏ qua tham số client gửi lên. Nếu tin tham số từ client thì bất kỳ ai
     * cũng đọc được phiếu của đồng nghiệp bằng cách đổi query string.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN','WAREHOUSE','SUPER_WAREHOUSE')")
    public ApiResponse<PageResponse<GiftOrderDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            Set<Role> roles = actor.getAllRoles();
            boolean seeAll = roles.contains(Role.ADMIN) || roles.contains(Role.OWNER)
                    || roles.contains(Role.SUPERADMIN) || roles.contains(Role.SUPER_SELLER)
                    || roles.contains(Role.WAREHOUSE) || roles.contains(Role.SUPER_WAREHOUSE);

            Long createdById = seeAll ? null : actor.getId();
            var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
            return ApiResponse.ok(giftOrderService.list(q, status, createdById, warehouseId, pageable));
        } catch (Exception e) {
            log.error("[GiftOrder] list error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN','WAREHOUSE','SUPER_WAREHOUSE')")
    public ApiResponse<GiftOrderDto> getById(@PathVariable Long id) {
        try {
            return ApiResponse.ok(giftOrderService.getById(id));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Danh sách kho chọn được ở form tạo phiếu.
     *
     * <p>Có endpoint riêng vì {@code /api/warehouse/**} chỉ mở cho role WAREHOUSE —
     * seller gọi vào đó sẽ nhận 401. Ở đây chỉ trả id + tên, không lộ gì thêm.
     */
    @GetMapping("/warehouses")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<List<Map<String, Object>>> warehouses() {
        try {
            List<Map<String, Object>> data = giftOrderService.selectableWarehouses().stream()
                    .map(w -> {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("id", w.getId());
                        m.put("name", w.getName());
                        return m;
                    })
                    .toList();
            return ApiResponse.ok(data);
        } catch (Exception e) {
            log.error("[GiftOrder] warehouses error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /** Sản phẩm có thể tặng từ một kho — dùng ở bước chọn hàng của form tạo phiếu. */
    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<List<GiftProductOption>> products(
            @RequestParam Long warehouseId,
            @RequestParam(required = false) String q) {
        try {
            return ApiResponse.ok(giftOrderService.availableProducts(warehouseId, q));
        } catch (Exception e) {
            log.error("[GiftOrder] products error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<GiftOrderDto> create(@Valid @RequestBody CreateGiftOrderRequest req,
                                            Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã tạo phiếu tặng quà, chờ duyệt",
                    giftOrderService.create(req, actor));
        } catch (Exception e) {
            log.warn("[GiftOrder] create failed: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    // ── Duyệt ────────────────────────────────────────────────────────────────

    /** Kiểm tồn kho trước khi duyệt — không thay đổi dữ liệu. */
    @GetMapping("/{id}/stock-check")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<StockCheckResult> stockCheck(@PathVariable Long id) {
        try {
            return ApiResponse.ok(giftOrderService.checkStock(id));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<GiftOrderDto> approve(@PathVariable Long id, Authentication auth) {
        try {
            User approver = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã duyệt phiếu và xuất kho", giftOrderService.approve(id, approver));
        } catch (Exception e) {
            log.warn("[GiftOrder] approve #{} failed: {}", id, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<GiftOrderDto> reject(@PathVariable Long id,
                                            @RequestBody(required = false) RejectRequest body,
                                            Authentication auth) {
        try {
            User approver = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã từ chối phiếu",
                    giftOrderService.reject(id, body != null ? body.getReason() : null, approver));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<GiftOrderDto> cancel(@PathVariable Long id, Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã huỷ phiếu", giftOrderService.cancel(id, actor));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ── Kho xử lý ────────────────────────────────────────────────────────────

    /** Phiếu chờ xử lý ở các kho nhân viên được phân công. */
    @GetMapping("/warehouse-queue")
    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPER_WAREHOUSE','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<List<GiftOrderDto>> warehouseQueue(Authentication auth) {
        try {
            User staff = (User) auth.getPrincipal();
            return ApiResponse.ok(giftOrderService.listForWarehouseStaff(staff));
        } catch (Exception e) {
            log.error("[GiftOrder] warehouseQueue error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/start-delivery")
    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPER_WAREHOUSE','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<GiftOrderDto> startDelivery(@PathVariable Long id, Authentication auth) {
        try {
            User staff = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã xác nhận, chuyển đi giao",
                    giftOrderService.startDelivery(id, staff));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPER_WAREHOUSE','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<GiftOrderDto> complete(@PathVariable Long id, Authentication auth) {
        try {
            User staff = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã hoàn thành", giftOrderService.complete(id, staff));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
