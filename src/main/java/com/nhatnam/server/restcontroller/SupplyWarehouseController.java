package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.supply.SupplyDtos.*;
import com.nhatnam.server.service.SupplyItemService;
import com.nhatnam.server.service.SupplyWarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Kho Văn phòng phẩm / Dụng cụ + Danh mục vật dụng.
 *
 * <pre>
 *   Người được gán kho : /api/supply-warehouses/**
 *   Owner              : /api/owner/supply-warehouses/**, /api/owner/supply-items/**
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class SupplyWarehouseController {

    private static final String ROLES_STAFF =
            "hasAnyRole('SUPER_SELLER','SUPER_WAREHOUSE','SUPER_FACTORY_WORKER',"
            + "'SUPER_ACCOUNTANT','OWNER','ADMIN','SUPERADMIN')";
    private static final String ROLES_OWNER = "hasAnyRole('OWNER','ADMIN','SUPERADMIN')";

    /**
     * Quyền CHỈ XEM kho: thêm hai role kế toán vào các endpoint đọc.
     *
     * <p>Kế toán cần nhìn được tồn kho và ai đang giữ kho nào để đối chiếu chứng
     * từ, nhưng KHÔNG được gán kho hay sửa danh mục — các endpoint ghi vẫn giữ
     * nguyên {@link #ROLES_OWNER}.
     */
    private static final String ROLES_OWNER_OR_ACCOUNTANT =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','ACCOUNTANT','SUPER_ACCOUNTANT')";

    private final SupplyWarehouseService service;
    private final SupplyItemService itemService;

    // ── Kho được gán ─────────────────────────────────────────────────────────

    /**
     * Kho user được thao tác. Dùng cho dropdown "kho nhận" khi tạo phiếu và cho
     * page Rút sử dụng. FE auto-select khi list chỉ có 1 phần tử.
     */
    @PreAuthorize(ROLES_STAFF)
    @GetMapping("/api/supply-warehouses")
    public ApiResponse<List<SupplyWarehouseDto>> myWarehouses(Authentication auth) {
        return ApiResponse.ok(service.listWarehouses(auth.getName()));
    }

    /** Tồn hiện tại của 1 kho. {@code onlyPositive=true} cho dropdown rút sử dụng. */
    @PreAuthorize(ROLES_STAFF)
    @GetMapping("/api/supply-warehouses/{warehouseId}/stock")
    public ApiResponse<List<SupplyStockRowDto>> stock(
            @PathVariable Long warehouseId,
            @RequestParam(defaultValue = "false") boolean onlyPositive,
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(service.stockOf(warehouseId, onlyPositive, search));
    }

    /** Rút sử dụng — chỉ người được gán kho. */
    @PreAuthorize(ROLES_STAFF)
    @PostMapping("/api/supply-warehouses/withdraw")
    public ApiResponse<List<SupplyTransactionDto>> withdraw(@RequestBody WithdrawRequest req,
                                                            Authentication auth) {
        return ApiResponse.ok(service.withdraw(req, auth.getName()));
    }

    /** Lịch sử của kho. {@code type}: IN | OUT | ALL. */
    @PreAuthorize(ROLES_STAFF)
    @GetMapping("/api/supply-warehouses/{warehouseId}/history")
    public ApiResponse<Page<SupplyTransactionDto>> history(
            @PathVariable Long warehouseId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.history(warehouseId, type, from, to, page, size));
    }

    // ── Owner ────────────────────────────────────────────────────────────────

    /** Owner thấy CẢ 2 kho, read-only. Kế toán cũng được xem. */
    @PreAuthorize(ROLES_OWNER_OR_ACCOUNTANT)
    @GetMapping("/api/owner/supply-warehouses")
    public ApiResponse<List<SupplyWarehouseDto>> allWarehouses() {
        return ApiResponse.ok(service.listAllWarehouses());
    }

    @PreAuthorize(ROLES_OWNER_OR_ACCOUNTANT)
    @GetMapping("/api/owner/supply-warehouses/assignments")
    public ApiResponse<List<UserWarehouseAssignmentDto>> assignments() {
        return ApiResponse.ok(service.listAssignments());
    }

    /** Gán kho cho user (ghi đè toàn bộ danh sách kho của user đó). */
    @PreAuthorize(ROLES_OWNER)
    @PostMapping("/api/owner/supply-warehouses/assignments")
    public ApiResponse<UserWarehouseAssignmentDto> assign(@RequestBody AssignWarehouseRequest req,
                                                          Authentication auth) {
        return ApiResponse.ok(service.assign(req, auth.getName()));
    }

    // ── Danh mục vật dụng ────────────────────────────────────────────────────

    /**
     * Autocomplete — biện pháp CHÍNH chống nhập sai gây phân mảnh tồn kho.
     * FE: chọn gợi ý ⇒ tự điền tên + quy cách + ĐVT và KHOÁ 3 ô đó lại.
     */
    @PreAuthorize(ROLES_STAFF)
    @GetMapping("/api/supply-items/suggest")
    public ApiResponse<List<SupplyItemSuggestDto>> suggest(@RequestParam(required = false) String q) {
        return ApiResponse.ok(itemService.suggest(q));
    }

    @PreAuthorize(ROLES_OWNER)
    @GetMapping("/api/owner/supply-items")
    public ApiResponse<List<SupplyItemDto>> listItems() {
        return ApiResponse.ok(itemService.listAll());
    }

    /**
     * MERGE THỦ CÔNG — gộp 2 bản ghi bị nhập lệch về 1.
     * Cộng dồn tồn theo TỪNG KHO, chuyển lịch sử, trỏ lại danh mục, soft-delete nguồn.
     */
    @PreAuthorize(ROLES_OWNER)
    @PostMapping("/api/owner/supply-items/{sourceId}/merge-into/{targetId}")
    public ApiResponse<Void> merge(@PathVariable Long sourceId,
                                   @PathVariable Long targetId,
                                   Authentication auth) {
        itemService.merge(sourceId, targetId, auth.getName());
        return ApiResponse.ok(null);
    }
}
