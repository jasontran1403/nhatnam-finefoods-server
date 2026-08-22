package com.nhatnam.server.restcontroller.bank;

import com.nhatnam.server.dto.cashflow.AddBankRequest;
import com.nhatnam.server.dto.cashflow.BankAccountDto;
import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.service.CashflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Danh mục ngân hàng dùng chung:
 * <ul>
 *   <li>GET  — mọi người dùng đã đăng nhập (để form phiếu thu/phiếu chi chọn ngân hàng).</li>
 *   <li>POST — chỉ ADMIN & OWNER được tạo ngân hàng mới.</li>
 * </ul>
 * Danh mục này đồng bộ với trang Quản lý dòng tiền.
 */
@RestController
@RequestMapping("/api/bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {

    private final CashflowService cashflowService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<BankAccountDto>> list() {
        return ApiResponse.ok(cashflowService.ensureSeedAndList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ApiResponse<BankAccountDto> add(@Valid @RequestBody AddBankRequest req) {
        return ApiResponse.ok(cashflowService.addBank(req.getName(), req.getAccountNumber()));
    }
}
