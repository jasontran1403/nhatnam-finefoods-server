package com.nhatnam.server.restcontroller.income;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.income.CreateIncomeVoucherRequest;
import com.nhatnam.server.dto.income.EmployeeSuggestionDto;
import com.nhatnam.server.dto.income.IncomeVoucherDto;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.service.IncomeVoucherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Log4j2
@RequestMapping("/api/income-vouchers")
@RequiredArgsConstructor
public class IncomeVoucherController {

    private final IncomeVoucherService voucherService;

    @GetMapping("/employee-suggestions")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<java.util.List<EmployeeSuggestionDto>> suggestEmployees(
            @RequestParam(required = false, defaultValue = "") String q) {
        return ApiResponse.ok(voucherService.suggestEmployees(q.trim()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<IncomeVoucherDto> create(
            @Valid @RequestBody CreateIncomeVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        Role creatorRole = user.getRole();
        return ApiResponse.ok(voucherService.create(user.getId(), creatorRole, req));
    }

    /**
     * SỬA phiếu thu — đổi số tiền và/hoặc danh sách đơn cần thu.
     *
     * <p>Cùng nhóm quyền với tạo phiếu. Body giống hệt {@code create}: gửi lại
     * TOÀN BỘ trạng thái mong muốn (danh sách đơn mới, tổng tiền mới), không phải
     * gửi phần thay đổi — server đảo tác động cũ rồi áp lại theo body này.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<IncomeVoucherDto> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateIncomeVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        // Lấy role ĐANG ACTIVE từ JWT (authorities) — user có thể có nhiều role và
        // vừa chuyển sang Kế toán; user.getRole() chỉ trả role CHÍNH nên sai.
        Role activeRole = activeRole(auth, user);
        return ApiResponse.ok(voucherService.update(id, user.getId(), activeRole, req));
    }

    /**
     * CẤN TRỪ phần dư của phiếu thu nguồn sang 1 đơn CÙNG KHÁCH. Tạo 1 phiếu thu
     * MỚI gắn vào đơn đó; đồng thời cập nhật {@code offsetUsedAmount} của phiếu
     * nguồn để phần dư giảm đi.
     */
    @PostMapping("/{id}/offset")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<IncomeVoucherDto> offset(
            @PathVariable Long id,
            @Valid @RequestBody com.nhatnam.server.dto.income.OffsetIncomeVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        Role activeRole = activeRole(auth, user);
        return ApiResponse.ok(
                voucherService.offsetOverpayToOrder(id, user.getId(), activeRole, req));
    }

    /** Nhật ký tạo/sửa của một phiếu thu. */
    @GetMapping("/{id}/logs")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<java.util.List<com.nhatnam.server.dto.income.IncomeVoucherLogDto>> logs(
            @PathVariable Long id) {
        return ApiResponse.ok(voucherService.getLogs(id));
    }

    /** Role đang dùng, suy từ authorities "ROLE_x"; fallback về role chính. */
    private Role activeRole(Authentication auth, User user) {
        for (var ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("ROLE_")) {
                try { return Role.valueOf(a.substring(5)); }
                catch (IllegalArgumentException ignored) { /* không phải role enum */ }
            }
        }
        return user.getRole();
    }

    /** Xem phiếu thu do mình tạo */
    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<PageResponse<IncomeVoucherDto>> listMy(
            Authentication auth,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.listForCreator(user.getId(), pageable));
    }

    /** Xem tất cả phiếu thu (SUPER_ACCOUNTANT, ADMIN, OWNER) */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<PageResponse<IncomeVoucherDto>> listAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(voucherService.listAll(pageable));
    }

    /**
     * Lọc theo ngày (from/to là epoch millis — đầu ngày / cuối ngày).
     * Dùng cho trang danh sách phiếu thu theo ngày.
     */
    @GetMapping("/by-date")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<PageResponse<IncomeVoucherDto>> listByDate(
            @RequestParam Long from,
            @RequestParam Long to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(voucherService.listByDateRange(from, to, pageable));
    }

    /**
     * Tìm kiếm toàn bộ (không lọc ngày) — theo mã phiếu, lý do, tên người nộp, số tiền.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<PageResponse<IncomeVoucherDto>> search(
            @RequestParam String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(voucherService.search(q.trim(), from, to, pageable));
    }

    /**
     * TỔNG HỢP theo đúng bộ lọc đang áp dụng (từ khoá + khoảng ngày).
     *
     * <p>Trả về tổng tiền + tổng số phiếu của TOÀN BỘ kết quả khớp bộ lọc,
     * KHÔNG phụ thuộc trang đang xem. FE dùng số này cho thẻ "Tổng số tiền phiếu thu".
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<com.nhatnam.server.dto.income.IncomeVoucherSummaryDto> summary(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return ApiResponse.ok(voucherService.summary(q, from, to));
    }

    /**
     * Gợi ý số phiếu thu kế tiếp (placeholder) — lấy số lớn nhất hiện có + 1.
     * Người dùng vẫn có thể tự nhập số khác nếu muốn.
     */
    @GetMapping("/next-receipt-number")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<String> suggestNextReceiptNumber() {
        return ApiResponse.ok(voucherService.suggestNextReceiptNumber());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<IncomeVoucherDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(voucherService.getById(id));
    }

    /**
     * Xuất báo cáo phiếu thu theo khoảng thời gian.
     * Trả về file xlsx với: thời gian tạo, số phiếu thu, hóa đơn liên kết, tổng tiền.
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ResponseEntity<byte[]> exportReport(
            @RequestParam Long from,
            @RequestParam Long to,
            @RequestParam(required = false) String paymentType,
            Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String exportedBy = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();
            byte[] data = voucherService.exportReport(from, to, exportedBy, paymentType);

            java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy");
            java.time.ZoneId tz = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
            String fromStr = java.time.Instant.ofEpochMilli(from).atZone(tz).format(fmt);
            String toStr   = java.time.Instant.ofEpochMilli(to).atZone(tz).format(fmt);
            String filename = "phieu-thu-" + fromStr + "_" + toStr + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Xuất PDF phiếu thu (Mẫu 01-TT) ─────────────────────────────────────
    private final com.nhatnam.server.service.AccountingVoucherPdfService accountingPdfService;

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        try {
            byte[] data = accountingPdfService.generateIncomeVoucher(id);
            String filename = java.net.URLEncoder.encode("phieu-thu-" + id + ".pdf",
                    java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(data);
        } catch (Exception e) {
            log.error("Export income voucher PDF failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}