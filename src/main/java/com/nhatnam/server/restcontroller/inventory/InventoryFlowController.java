package com.nhatnam.server.restcontroller.inventory;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.inventory.ConfirmInventoryRequest;
import com.nhatnam.server.dto.inventory.IngredientLiteDto;
import com.nhatnam.server.dto.inventory.InventorySummaryDto;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.InventoryFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Quản lý tồn kho nguyên liệu theo kỳ — chỉ ADMIN & OWNER. */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class InventoryFlowController {

    private final InventoryFlowService inventoryFlowService;

    @GetMapping("/summary")
    public ApiResponse<InventorySummaryDto> summary(
            @RequestParam Long from,
            @RequestParam Long to,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(inventoryFlowService.summary(from, to, q));
    }

    @GetMapping("/ingredients")
    public ApiResponse<List<IngredientLiteDto>> ingredients(@RequestParam(required = false) String q) {
        return ApiResponse.ok(inventoryFlowService.listIngredients(q));
    }

    /** Tồn kho theo xưởng (bảng riêng: kho nguyên liệu + kho thành phẩm mỗi xưởng). */
    @GetMapping("/factory-stock")
    public ApiResponse<List<com.nhatnam.server.dto.inventory.FactoryInventoryDto.FactoryBlock>> factoryStock(
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(inventoryFlowService.factoryStockOverview(q));
    }

    @PostMapping("/confirm")
    public ApiResponse<Void> confirm(@RequestBody ConfirmInventoryRequest req, Authentication auth) {
        User user = (User) auth.getPrincipal();
        inventoryFlowService.confirm(user, req);
        return ApiResponse.ok((Void) null);
    }
}
