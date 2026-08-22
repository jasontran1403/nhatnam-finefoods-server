package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.FactoryStockDtos.*;
import com.nhatnam.server.service.FactoryStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Xuất / chuyển kho nguyên liệu xưởng (Mục 2).
 * Dùng chung cho SUPER_FACTORY_WORKER (kho nguyên liệu xưởng của họ).
 */
@RestController
@RequestMapping("/api/factory/material-stock")
@RequiredArgsConstructor
public class FactoryStockController {

    private final FactoryStockService service;

    /** Kho đích: kho bán/trung chuyển + kho NL xưởng khác + kho TP xưởng khác. */
    @GetMapping("/transfer-targets")
    public ApiResponse<List<StockTargetDto>> targets(@RequestParam Long factoryId) {
        return ApiResponse.ok(service.listTargets(factoryId));
    }

    /** Nguyên liệu chuyển được sang kho đích (chỉ cái kho đích đang có, trùng tên). */
    @GetMapping("/transferable")
    public ApiResponse<List<TransferableMaterialDto>> transferable(
            @RequestParam Long factoryId, @RequestParam String targetKey) {
        return ApiResponse.ok(service.listTransferable(factoryId, targetKey));
    }

    @PostMapping("/export")
    public ApiResponse<FactoryStockNoteDto> export(@RequestBody ExportFactoryMaterialRequest req,
                                                   Authentication auth) {
        return ApiResponse.ok(service.exportStock(req, auth.getName()));
    }

    @PostMapping("/transfer")
    public ApiResponse<FactoryStockNoteDto> transfer(@RequestBody TransferFactoryMaterialRequest req,
                                                     Authentication auth) {
        return ApiResponse.ok(service.transferStock(req, auth.getName()));
    }

    /** Lịch sử phiếu nhập/xuất/chuyển của kho nguyên liệu xưởng. */
    @GetMapping("/notes")
    public ApiResponse<List<FactoryStockNoteDto>> notes(
            @RequestParam Long factoryId,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(service.listNotes(factoryId, type));
    }
}
