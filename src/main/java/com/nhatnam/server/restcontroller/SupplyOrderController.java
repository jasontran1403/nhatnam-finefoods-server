package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.supply.SupplyDtos.*;
import com.nhatnam.server.service.SupplyOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Phiếu đặt hàng Văn phòng phẩm / Đồ dùng.
 *
 * <pre>
 *   Người tạo phiếu   : /api/supply-orders/**            (SUPER_SELLER, SUPER_WAREHOUSE, SUPER_FACTORY_WORKER)
 *   Kế toán trưởng    : /api/super-accountant/supply-orders/**
 *   Owner (read-only) : /api/owner/supply-orders/**
 * </pre>
 *
 * Base path tách biệt hoàn toàn với {@code /api/factory/material-requests} —
 * page phiếu nguyên liệu cũ không bị ảnh hưởng.
 */
@RestController
@RequiredArgsConstructor
public class SupplyOrderController {

    private static final String ROLES_CREATOR =
            "hasAnyRole('SUPER_SELLER','SUPER_WAREHOUSE','SUPER_FACTORY_WORKER')";
    private static final String ROLES_ACCOUNTANT =
            "hasAnyRole('SUPER_ACCOUNTANT','OWNER','ADMIN','SUPERADMIN')";
    private static final String ROLES_ANY =
            "hasAnyRole('SUPER_SELLER','SUPER_WAREHOUSE','SUPER_FACTORY_WORKER',"
            + "'SUPER_ACCOUNTANT','OWNER','ADMIN','SUPERADMIN')";

    private final SupplyOrderService service;

    // ═══════════════════════════════════════════════════════════════════════
    //  BƯỚC 1 — Người tạo phiếu
    // ═══════════════════════════════════════════════════════════════════════

    /** Dropdown NCC — TẤT CẢ nhà cung cấp đang hoạt động. */
    @PreAuthorize(ROLES_ANY)
    @GetMapping("/api/supply-orders/suppliers")
    public ApiResponse<List<SupplierOptionDto>> suppliers(@RequestParam(required = false) String q) {
        return ApiResponse.ok(service.listSuppliers(q));
    }

    /** Danh mục khoản chi (kèm loại / ĐVT / quy cách để FE hiển thị read-only). */
    @PreAuthorize(ROLES_ANY)
    @GetMapping("/api/supply-orders/expense-categories")
    public ApiResponse<List<ExpenseCategoryOptionDto>> categories(
            @RequestParam(required = false) Long supplierId) {
        return ApiResponse.ok(service.listCategories(supplierId));
    }

    /**
     * TẠO NHANH nhãn khoản chi ngay trên form lập phiếu.
     *
     * <p>Mở quyền cho cả 3 role lập phiếu — trước đây chỉ OWNER tạo được nên gặp
     * món chưa có trong danh mục là phiếu phải dừng lại chờ. Nhãn tạo ra nằm
     * trong POOL DÙNG CHUNG, y hệt nhãn do OWNER tạo, nên OWNER vẫn sửa/ẩn được
     * ở trang Quản lý NCC.
     *
     * <p>Body giống hệt endpoint của OWNER
     * ({@code POST /api/owner/production/suppliers/expense-categories}):
     * {@code { name, description, categoryKind, unit, specification, supplyItemId }}.
     * Với {@code categoryKind = CONSUMABLE}, BE bắt buộc có ĐVT + quy cách rồi tự
     * gán {@code supplyItemId} để không tách dòng tồn kho.
     */
    @PreAuthorize(ROLES_CREATOR)
    @PostMapping("/api/supply-orders/expense-categories")
    public ApiResponse<ExpenseCategoryOptionDto> createCategory(
            @RequestBody com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.CategoryUpsertRequest req,
            Authentication auth) {
        return ApiResponse.ok(service.createCategory(req, auth != null ? auth.getName() : null));
    }

    /** Tạo phiếu. {@code draft = true} → Lưu nháp; {@code false} → Tạo phiếu (gửi kế toán). */
    @PreAuthorize(ROLES_CREATOR)
    @PostMapping("/api/supply-orders")
    public ApiResponse<SupplyOrderDto> create(@RequestBody CreateSupplyOrderRequest req,
                                              Authentication auth) {
        return ApiResponse.ok(service.create(req, auth.getName()));
    }

    /** Sửa phiếu nháp (chỉ khi còn NEW và do chính người tạo). */
    @PreAuthorize(ROLES_CREATOR)
    @PutMapping("/api/supply-orders/{id}")
    public ApiResponse<SupplyOrderDto> updateDraft(@PathVariable Long id,
                                                   @RequestBody CreateSupplyOrderRequest req,
                                                   Authentication auth) {
        return ApiResponse.ok(service.updateDraft(id, req, auth.getName()));
    }

    /** Danh sách phiếu do CHÍNH mình tạo. */
    @PreAuthorize(ROLES_CREATOR)
    @GetMapping("/api/supply-orders")
    public ApiResponse<Page<SupplyOrderDto>> listMine(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long dateFrom,
            @RequestParam(required = false) Long dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        return ApiResponse.ok(service.listForCreator(
                auth.getName(), status, warehouseId, dateFrom, dateTo, search, page, size));
    }

    @PreAuthorize(ROLES_ANY)
    @GetMapping("/api/supply-orders/{id}")
    public ApiResponse<SupplyOrderDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BƯỚC 3 — Nhận hàng (chỉ người tạo phiếu)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lưu / xác nhận một đợt nhận. {@code draft = true} → chỉ lưu, không cộng kho.
     * Nhận nhiều lần được (NCC giao thiếu rồi giao bù).
     */
    @PreAuthorize(ROLES_CREATOR)
    @PostMapping("/api/supply-orders/{id}/receipts")
    public ApiResponse<SupplyOrderDto> saveReceipt(@PathVariable Long id,
                                                   @RequestBody SaveSupplyReceiptRequest req,
                                                   Authentication auth) {
        return ApiResponse.ok(service.saveReceipt(id, req, auth.getName()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BƯỚC 2 & 4 — SUPER_ACCOUNTANT
    // ═══════════════════════════════════════════════════════════════════════

    /** Tab "Đồ dùng" của page Phiếu đặt hàng. */
    @PreAuthorize(ROLES_ACCOUNTANT)
    @GetMapping("/api/super-accountant/supply-orders")
    public ApiResponse<Page<SupplyOrderDto>> listForAccountant(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long dateFrom,
            @RequestParam(required = false) Long dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listForAccountant(
                status, warehouseId, dateFrom, dateTo, search, page, size));
    }

    @PreAuthorize(ROLES_ACCOUNTANT)
    @GetMapping("/api/super-accountant/supply-orders/{id}")
    public ApiResponse<SupplyOrderDto> getForAccountant(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /** Xác nhận đặt hàng — DUYỆT HẾT, sinh các nhóm theo NCC. */
    @PreAuthorize(ROLES_ACCOUNTANT)
    @PostMapping("/api/super-accountant/supply-orders/{id}/confirm")
    public ApiResponse<SupplyOrderDto> confirm(@PathVariable Long id,
                                               @RequestBody ConfirmSupplyOrderRequest req,
                                               Authentication auth) {
        return ApiResponse.ok(service.confirmOrder(id, req, auth.getName()));
    }

    /** Từ chối — TERMINAL, không thể duyệt lại. */
    @PreAuthorize(ROLES_ACCOUNTANT)
    @PostMapping("/api/super-accountant/supply-orders/{id}/reject")
    public ApiResponse<SupplyOrderDto> reject(@PathVariable Long id,
                                              @RequestBody RejectSupplyOrderRequest req,
                                              Authentication auth) {
        return ApiResponse.ok(service.reject(id, req, auth.getName()));
    }

    @PreAuthorize(ROLES_ACCOUNTANT)
    @PostMapping("/api/super-accountant/supply-orders/{id}/extend-delivery")
    public ApiResponse<SupplyOrderDto> extendDelivery(@PathVariable Long id,
                                                      @RequestBody ExtendDeliveryRequest req,
                                                      Authentication auth) {
        return ApiResponse.ok(service.extendDelivery(id, req, auth.getName()));
    }

    /** Tất toán — tạo phiếu chi (PAY_NOW) hoặc ghi công nợ (DEBT) cho từng NCC. */
    @PreAuthorize(ROLES_ACCOUNTANT)
    @PostMapping("/api/super-accountant/supply-orders/{id}/settle")
    public ApiResponse<SupplyOrderDto> settle(@PathVariable Long id,
                                              @RequestBody SettleSupplyOrderRequest req,
                                              Authentication auth) {
        return ApiResponse.ok(service.settle(id, req, auth.getName()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  OWNER — read only
    // ═══════════════════════════════════════════════════════════════════════

    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    @GetMapping("/api/owner/supply-orders")
    public ApiResponse<Page<SupplyOrderDto>> listForOwner(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long dateFrom,
            @RequestParam(required = false) Long dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listForAccountant(
                status, warehouseId, dateFrom, dateTo, search, page, size));
    }
}
