package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.FinishedGoodsDtos.*;
import com.nhatnam.server.entity.Warehouse;
import com.nhatnam.server.service.FinishedGoodsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kho thành phẩm — Issue #1 + #2.
 * Xem danh sách: dùng chung cho FACTORY_WORKER / SUPER_FACTORY_WORKER / OWNER.
 * Xuất kho / chuyển kho (Bước "kế toán kho xưởng"): chỉ FACTORY_ACCOUNTANT
 * (xem /api/factory-accountant/** ở SecurityConfiguration) — đây là người chịu
 * trách nhiệm chuyển kho thành phẩm → kho bán hàng (Warehouse) hoặc xuất kho
 * bán tại chỗ/lý do khác.
 */
@RestController
@RequiredArgsConstructor
public class FinishedGoodsController {

    private final FinishedGoodsService finishedGoodsService;

    // ─── Danh sách tổng hợp (UI chính: tên thành phẩm, tồn kho, cận date) ──────

    @GetMapping("/api/factory/finished-goods")
    public ApiResponse<List<FinishedGoodsSummaryDto>> listSummary(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long expiryBeforeMs,
            @RequestParam(required = false) Long factoryId) {
        return ApiResponse.ok(finishedGoodsService.listSummary(q, expiryBeforeMs, factoryId));
    }

    // ─── Danh sách kho bán hàng (cho dropdown chọn kho đích khi chuyển kho) ────
    // GIỮ LẠI cho tương thích ngược; UI mới dùng /transfer-targets (có cả trung chuyển).

    @GetMapping("/api/factory-accountant/finished-goods/sale-warehouses")
    public ApiResponse<List<Map<String, Object>>> listSaleWarehouses() {
        List<Map<String, Object>> result = finishedGoodsService.listSaleWarehouses().stream()
                .map(w -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", w.getId());
                    m.put("name", w.getName());
                    m.put("address", w.getAddress());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.ok(result);
    }

    // ─── Kho đích (Kho bán + Trung chuyển) — hiển thị "Tên kho — Loại kho" ─────

    @GetMapping("/api/factory-accountant/finished-goods/transfer-targets")
    public ApiResponse<List<TransferTargetDto>> listTransferTargets() {
        return ApiResponse.ok(finishedGoodsService.listTransferTargets());
    }

    // ─── Thành phẩm chuyển được sang kho đích (chỉ những cái kho đích ĐANG CÓ) ─

    @GetMapping("/api/factory-accountant/finished-goods/transferable")
    public ApiResponse<List<TransferableProductDto>> listTransferable(
            @RequestParam Long factoryId,
            @RequestParam Long targetWarehouseId) {
        return ApiResponse.ok(finishedGoodsService.listTransferableProducts(factoryId, targetWarehouseId));
    }

    // ─── Xuất kho — chỉ FACTORY_ACCOUNTANT (bán tại chỗ / lý do khác, bắt buộc lý do) ──

    @PostMapping("/api/factory-accountant/finished-goods/export")
    public ApiResponse<FinishedGoodsTransactionDto> exportGoods(
            @RequestBody ExportFinishedGoodsRequest req, Authentication auth) {
        return ApiResponse.ok(finishedGoodsService.exportGoods(req, auth.getName()));
    }

    // ─── Chuyển kho — chỉ FACTORY_ACCOUNTANT (kho thành phẩm → kho bán hàng) ───

    @PostMapping("/api/factory-accountant/finished-goods/transfer")
    public ApiResponse<FinishedGoodsTransactionDto> transferGoods(
            @RequestBody TransferFinishedGoodsRequest req, Authentication auth) {
        return ApiResponse.ok(finishedGoodsService.transferGoods(req, auth.getName()));
    }

    // ─── Lịch sử giao dịch ──────────────────────────────────────────────────

    @GetMapping("/api/factory-accountant/finished-goods/transactions")
    public ApiResponse<List<FinishedGoodsTransactionDto>> listTransactions(
            @RequestParam(required = false) String productName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(finishedGoodsService.listTransactions(productName, page, size));
    }
}
