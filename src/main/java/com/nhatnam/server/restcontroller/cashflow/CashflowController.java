package com.nhatnam.server.restcontroller.cashflow;

import com.nhatnam.server.dto.cashflow.*;
import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.CashflowPdfService;
import com.nhatnam.server.service.CashflowService;
import com.nhatnam.server.utils.CashflowCombinedReportExcel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Quản lý dòng tiền — ADMIN, OWNER, ACCOUNTANT, SUPER_ACCOUNTANT. */
@RestController
@RequestMapping("/api/cashflow")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('ADMIN','OWNER','ACCOUNTANT','SUPER_ACCOUNTANT')")
public class CashflowController {

    private final CashflowService cashflowService;
    private final CashflowPdfService pdfService;

    @GetMapping("/banks")
    public ApiResponse<List<BankAccountDto>> banks() {
        return ApiResponse.ok(cashflowService.ensureSeedAndList());
    }

    @PostMapping("/banks")
    public ApiResponse<BankAccountDto> addBank(@Valid @RequestBody AddBankRequest req) {
        return ApiResponse.ok(cashflowService.addBank(req.getName(), req.getAccountNumber()));
    }

    @GetMapping("/summary")
    public ApiResponse<CashflowSummaryDto> summary(@RequestParam Long from, @RequestParam Long to) {
        return ApiResponse.ok(cashflowService.summary(from, to));
    }

    @PostMapping("/confirm")
    public ApiResponse<ConfirmResultDto> confirm(@RequestBody ConfirmCashflowRequest req, Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(cashflowService.confirm(user, req));
    }

    /**
     * Báo cáo dòng tiền GỘP (Phiếu thu + Phiếu chi) ra Excel. Thay cho 2 báo cáo rời
     * — mỗi dòng có cột "Loại", sắp xếp theo thời gian tạo, kèm dòng đầu kỳ/cuối kỳ.
     */
    @GetMapping("/report-excel")
    public ResponseEntity<byte[]> reportExcel(@RequestParam Long from,
                                              @RequestParam Long to,
                                              @RequestParam(required = false) String paymentType,
                                              Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String by = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();
            CashflowSummaryDto s = cashflowService.summary(from, to);
            byte[] data = CashflowCombinedReportExcel.build(s, by, paymentType);
            String filename = URLEncoder.encode("bao-cao-dong-tien.xlsx",
                    StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            log.error("Cashflow combined excel failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@RequestParam Long from, @RequestParam Long to, Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String by = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();
            CashflowSummaryDto s = cashflowService.summary(from, to);
            byte[] data = pdfService.generate(s, by);
            String filename = URLEncoder.encode("bao-cao-dong-tien.pdf", StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(data);
        } catch (Exception e) {
            log.error("Cashflow report failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}