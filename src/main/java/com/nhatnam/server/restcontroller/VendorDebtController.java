package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.vendordebt.VendorDebtDtos.*;
import com.nhatnam.server.service.VendorDebtService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Công nợ nhà cung cấp nguyên liệu + Phiếu chi trả công nợ.
 *
 * Owner xem:                       /api/owner/production/vendor-debts/**   (read-only)
 * Accountant/Super Accountant xem
 * + tạo phiếu chi:                 /api/accountant/vendor-expenses/**, /api/super-accountant/vendor-expenses/**
 *                                  và cũng được phép đọc /api/owner/production/vendor-debts/** (trang công nợ chung)
 */
@RestController
@RequiredArgsConstructor
public class VendorDebtController {

    private final VendorDebtService service;

    private static final String ROLES_READ = "hasAnyRole('OWNER','ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','SUPERADMIN')";
    private static final String ROLES_WRITE = "hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')";

    // ═══════════════════════════════════════════════════════════════════════
    // Công nợ — Owner + Accountant/Super Accountant đều xem được (read-only)
    // ═══════════════════════════════════════════════════════════════════════

    /** Danh sách NCC đang có công nợ. sortBy=oldest (mặc định) | amount */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/vendor-debts")
    public ApiResponse<List<VendorDebtSummaryDto>> listVendorDebts(
            @RequestParam(required = false, defaultValue = "oldest") String sortBy) {
        return ApiResponse.ok(service.listVendorDebts(sortBy));
    }

    /** Lịch sử công nợ (theo từng phiếu đặt hàng) của 1 NCC */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/vendor-debts/{vendorId}/history")
    public ApiResponse<List<VendorDebtDetailDto>> getVendorDebtHistory(@PathVariable Long vendorId) {
        return ApiResponse.ok(service.getVendorDebtHistory(vendorId));
    }

    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/vendor-debts/{vendorId}/outstanding")
    public ApiResponse<BigDecimal> getOutstanding(@PathVariable Long vendorId) {
        return ApiResponse.ok(service.getOutstandingDebt(vendorId));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCOUNTANT / SUPER ACCOUNTANT — phiếu chi trả công nợ (tạo + xem)
    // ═══════════════════════════════════════════════════════════════════════

    @PreAuthorize(ROLES_WRITE)
    @PostMapping({"/api/accountant/vendor-expenses", "/api/super-accountant/vendor-expenses"})
    public ApiResponse<VendorExpenseVoucherDto> create(@RequestBody CreateVendorExpenseRequest req,
                                                         Authentication auth) {
        return ApiResponse.ok(service.createExpense(req, auth.getName()));
    }

    @PreAuthorize(ROLES_READ)
    @GetMapping({"/api/accountant/vendor-expenses", "/api/super-accountant/vendor-expenses"})
    public ApiResponse<Page<VendorExpenseVoucherDto>> list(
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(service.listExpenses(vendorId, search, pageable));
    }

    @PreAuthorize(ROLES_READ)
    @GetMapping({"/api/accountant/vendor-expenses/{id}", "/api/super-accountant/vendor-expenses/{id}"})
    public ApiResponse<VendorExpenseVoucherDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getExpenseById(id));
    }

    /** Công nợ hiện tại của 1 NCC — kế toán xem trước khi tạo phiếu chi */
    @PreAuthorize(ROLES_READ)
    @GetMapping({"/api/accountant/vendor-expenses/outstanding/{vendorId}",
                 "/api/super-accountant/vendor-expenses/outstanding/{vendorId}"})
    public ApiResponse<BigDecimal> getOutstandingForAccountant(@PathVariable Long vendorId) {
        return ApiResponse.ok(service.getOutstandingDebt(vendorId));
    }

    /** Owner/Admin chỉ xem, không duyệt — dùng chung endpoint list/getById ở trên */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/api/owner/production/vendor-expenses")
    public ApiResponse<Page<VendorExpenseVoucherDto>> listForOwner(
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(service.listExpenses(vendorId, search, pageable));
    }
}
