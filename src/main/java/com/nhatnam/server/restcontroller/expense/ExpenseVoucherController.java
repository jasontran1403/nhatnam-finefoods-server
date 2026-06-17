package com.nhatnam.server.restcontroller.expense;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.expense.ApproveExpenseVoucherRequest;
import com.nhatnam.server.dto.expense.CreateExpenseVoucherRequest;
import com.nhatnam.server.dto.expense.ExpenseVoucherDto;
import com.nhatnam.server.dto.expense.RejectExpenseVoucherRequest;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.ExpenseVoucherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/expense-vouchers")
@RequiredArgsConstructor
public class ExpenseVoucherController {

    private final ExpenseVoucherService voucherService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','SUPER_WAREHOUSE','ADMIN','OWNER')")
    public ApiResponse<ExpenseVoucherDto> create(
            @Valid @RequestBody CreateExpenseVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.create(user.getId(), req));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','SUPER_WAREHOUSE','ADMIN','OWNER')")
    public ApiResponse<PageResponse<ExpenseVoucherDto>> listMy(
            Authentication auth,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.listForCreator(user.getId(), pageable));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','SUPER_ACCOUNTANT')")
    public ApiResponse<PageResponse<ExpenseVoucherDto>> listAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(voucherService.listAll(pageable));
    }

    /**
     * Lọc theo ngày (from/to là epoch millis).
     */
    @GetMapping("/by-date")
    @PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','SUPER_WAREHOUSE','ADMIN','OWNER')")
    public ApiResponse<PageResponse<ExpenseVoucherDto>> listByDate(
            @RequestParam Long from,
            @RequestParam Long to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(voucherService.listByDateRange(from, to, pageable));
    }

    /**
     * Tìm kiếm toàn bộ (không lọc ngày).
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','SUPER_WAREHOUSE','ADMIN','OWNER')")
    public ApiResponse<PageResponse<ExpenseVoucherDto>> search(
            @RequestParam String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(voucherService.search(q.trim(), from, to, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','SUPER_ACCOUNTANT','SUPER_WAREHOUSE')")
    public ApiResponse<ExpenseVoucherDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(voucherService.getById(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ApiResponse<ExpenseVoucherDto> approve(
            @PathVariable Long id,
            @RequestBody(required = false) ApproveExpenseVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        String note = req != null ? req.getNote() : null;
        return ApiResponse.ok(voucherService.approve(id, user.getId(), note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ApiResponse<ExpenseVoucherDto> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectExpenseVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.reject(id, user.getId(), req.getReason()));
    }
}