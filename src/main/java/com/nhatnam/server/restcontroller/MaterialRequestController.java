package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.MaterialRequestDtos.*;
import com.nhatnam.server.service.MaterialRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Phiếu đặt hàng nguyên liệu xưởng.
 *
 * Factory Worker:      /api/factory/material-requests/**
 * Super Accountant:    /api/super-accountant/material-requests/**
 * Super Seller (read): /api/super-seller/material-requests/**
 */
@RestController
@RequiredArgsConstructor
public class MaterialRequestController {

    private final MaterialRequestService service;

    // ═══════════════════════════════════════════════════════════════════════════
    // FACTORY WORKER
    // ═══════════════════════════════════════════════════════════════════════════

    /** Tạo phiếu đặt hàng mới */
    @PostMapping("/api/factory/material-requests")
    public ApiResponse<MaterialRequestDto> create(@RequestBody CreateMaterialRequestRequest req,
                                                   Authentication auth) {
        return ApiResponse.ok(service.create(req, auth.getName()));
    }

    /** Danh sách phiếu của nhân viên xưởng (mặc định: NEW + ORDERED) */
    @GetMapping("/api/factory/material-requests")
    public ApiResponse<Page<MaterialRequestDto>> listForFactory(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dateFrom,
            @RequestParam(required = false) Long dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        return ApiResponse.ok(service.listForFactory(auth.getName(), status, dateFrom, dateTo, search, page, size));
    }

    /** Chi tiết 1 phiếu */
    @GetMapping("/api/factory/material-requests/{id}")
    public ApiResponse<MaterialRequestDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /** Xác nhận đã nhận hàng + nhập số thực nhận */
    @PostMapping("/api/factory/material-requests/{id}/receive")
    public ApiResponse<MaterialRequestDto> receive(@PathVariable Long id,
                                                    @RequestBody ReceiveRequest req,
                                                    Authentication auth) {
        return ApiResponse.ok(service.confirmReceive(id, req, auth.getName()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUPER ACCOUNTANT
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/api/super-accountant/material-requests")
    public ApiResponse<Page<MaterialRequestDto>> listForAccountant(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dateFrom,
            @RequestParam(required = false) Long dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listForAccountant(status, dateFrom, dateTo, search, page, size));
    }

    @GetMapping("/api/super-accountant/material-requests/{id}")
    public ApiResponse<MaterialRequestDto> getByIdAccountant(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /** Xác nhận đặt hàng + thêm NCC + nhập ngày giao dự kiến */
    @PostMapping("/api/super-accountant/material-requests/{id}/order")
    public ApiResponse<MaterialRequestDto> confirmOrder(@PathVariable Long id,
                                                         @RequestBody ConfirmOrderRequest req,
                                                         Authentication auth) {
        return ApiResponse.ok(service.confirmOrder(id, req, auth.getName()));
    }

    /** Hoàn thành phiếu sau khi thanh toán */
    @PostMapping("/api/super-accountant/material-requests/{id}/complete")
    public ApiResponse<MaterialRequestDto> complete(@PathVariable Long id, Authentication auth) {
        return ApiResponse.ok(service.complete(id, auth.getName()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KHO NGUYÊN LIỆU XƯỞNG (Owner + Factory Worker xem)
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/api/factory/material-stock")
    public ApiResponse<List<FactoryStockSummaryDto>> getStock() {
        return ApiResponse.ok(service.getStockSummary());
    }

    @GetMapping("/api/owner/factory/material-stock")
    public ApiResponse<List<FactoryStockSummaryDto>> getStockOwner() {
        return ApiResponse.ok(service.getStockSummary());
    }
}
