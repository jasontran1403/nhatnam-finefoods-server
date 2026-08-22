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
        return ApiResponse.ok(service.listForFactory(auth.getName(), "FACTORY", status, dateFrom, dateTo, search, page, size));
    }

    /** Chi tiết 1 phiếu */
    @GetMapping("/api/factory/material-requests/{id}")
    public ApiResponse<MaterialRequestDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /**
     * Lưu MỘT ĐỢT nhận hàng (nhận lẻ / giao bù). Gọi được nhiều lần cho tới khi chốt.
     * Chỉ gửi các dòng thực giao trong đợt này.
     */
    @PostMapping("/api/factory/material-requests/{id}/receipts")
    public ApiResponse<MaterialRequestDto> saveReceipt(@PathVariable Long id,
                                                       @RequestBody SaveReceiptRequest req,
                                                       Authentication auth) {
        return ApiResponse.ok(service.saveReceipt(id, req, auth.getName()));
    }

    /**
     * Chốt "đã giao xong" — bước cuối, khoá phiếu. Bắt buộc lý do nếu còn thiếu.
     */
    @PostMapping("/api/factory/material-requests/{id}/finish-receiving")
    public ApiResponse<MaterialRequestDto> finishReceiving(@PathVariable Long id,
                                                           @RequestBody FinishReceivingRequest req,
                                                           Authentication auth) {
        return ApiResponse.ok(service.finishReceiving(id, req, auth.getName()));
    }

    /** [LEGACY] Nhận hàng 1 lần = lưu 1 đợt + chốt luôn. Giữ cho client cũ. */
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

    /** Hoàn thành phiếu: nhập đơn giá + xử lý thanh toán/công nợ theo từng NCC */
    @PostMapping("/api/super-accountant/material-requests/{id}/extend-delivery")
    public ApiResponse<MaterialRequestDto> extendDelivery(@PathVariable Long id,
                                                           @RequestBody DeliveryExtendRequest req) {
        return ApiResponse.ok(service.extendDelivery(id, req));
    }

    @PostMapping("/api/super-accountant/material-requests/{id}/complete")
    public ApiResponse<MaterialRequestDto> complete(@PathVariable Long id,
                                                    @RequestBody CompleteRequest req,
                                                    Authentication auth) {
        return ApiResponse.ok(service.complete(id, req, auth.getName()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUPER SELLER (phiếu SELLER — nguyên liệu Ingredient)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Nguyên liệu SUPER_SELLER được đặt (đã lọc theo category cho phép) */
    @GetMapping("/api/seller/material-requests/ingredients")
    public ApiResponse<List<SellerIngredientOption>> sellerIngredients(
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(service.getSellerOrderableIngredients(q));
    }

    /** Danh sách kho để chọn nơi nhận */
    @GetMapping("/api/seller/material-requests/warehouses")
    public ApiResponse<List<WarehouseOption>> sellerWarehouses() {
        return ApiResponse.ok(service.getActiveWarehouses());
    }

    /** SUPER_SELLER tạo phiếu đặt hàng (luôn là loại SELLER) */
    @PostMapping("/api/seller/material-requests")
    public ApiResponse<MaterialRequestDto> sellerCreate(@RequestBody CreateMaterialRequestRequest req,
                                                        Authentication auth) {
        req.setType("SELLER");
        return ApiResponse.ok(service.create(req, auth.getName()));
    }

    /** Danh sách phiếu của chính SUPER_SELLER */
    @GetMapping("/api/seller/material-requests")
    public ApiResponse<Page<MaterialRequestDto>> sellerList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long dateFrom,
            @RequestParam(required = false) Long dateTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        return ApiResponse.ok(service.listForFactory(auth.getName(), "SELLER", status, dateFrom, dateTo, search, page, size));
    }

    /** Chi tiết 1 phiếu (SUPER_SELLER) */
    @GetMapping("/api/seller/material-requests/{id}")
    public ApiResponse<MaterialRequestDto> sellerGetById(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    /** SUPER_SELLER lưu một đợt nhận (chọn kho cho từng dòng) */
    @PostMapping("/api/seller/material-requests/{id}/receipts")
    public ApiResponse<MaterialRequestDto> sellerSaveReceipt(@PathVariable Long id,
                                                             @RequestBody SaveReceiptRequest req,
                                                             Authentication auth) {
        return ApiResponse.ok(service.saveReceipt(id, req, auth.getName()));
    }

    /** SUPER_SELLER chốt đã giao xong */
    @PostMapping("/api/seller/material-requests/{id}/finish-receiving")
    public ApiResponse<MaterialRequestDto> sellerFinishReceiving(@PathVariable Long id,
                                                                 @RequestBody FinishReceivingRequest req,
                                                                 Authentication auth) {
        return ApiResponse.ok(service.finishReceiving(id, req, auth.getName()));
    }

    /** [LEGACY] SUPER_SELLER thực nhận 1 lần + chọn kho cho từng dòng */
    @PostMapping("/api/seller/material-requests/{id}/receive")
    public ApiResponse<MaterialRequestDto> sellerReceive(@PathVariable Long id,
                                                         @RequestBody ReceiveRequest req,
                                                         Authentication auth) {
        return ApiResponse.ok(service.confirmReceive(id, req, auth.getName()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KHO NGUYÊN LIỆU XƯỞNG (Owner + Factory Worker xem)
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/api/factory/material-stock")
    public ApiResponse<List<FactoryStockSummaryDto>> getStock(
            @RequestParam(required = false) Long factoryId) {
        return ApiResponse.ok(service.getStockSummary(factoryId));
    }

    @GetMapping("/api/owner/factory/material-stock")
    public ApiResponse<List<FactoryStockSummaryDto>> getStockOwner(
            @RequestParam(required = false) Long factoryId) {
        return ApiResponse.ok(service.getStockSummary(factoryId));
    }
}