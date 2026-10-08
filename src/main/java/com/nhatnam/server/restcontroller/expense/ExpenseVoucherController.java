package com.nhatnam.server.restcontroller.expense;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.expense.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.ExpenseVoucherService;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

// Các role được phép TẠO phiếu chi + xem danh sách phòng ban của mình
// (ACCOUNTANT, SUPER_ACCOUNTANT, SUPER_WAREHOUSE, SUPER_FACTORY_WORKER, ADMIN, OWNER)
@RestController
@RequestMapping("/api/expense-vouchers")
@RequiredArgsConstructor
@Log4j2
public class ExpenseVoucherController {

    private static final String CREATE_ROLES =
            "hasAnyRole('SUPER_ACCOUNTANT','ACCOUNTANT','SUPER_WAREHOUSE','SUPER_FACTORY_WORKER','ADMIN','OWNER')";
    private static final String APPROVE_ROLES =
            "hasAnyRole('SUPER_ACCOUNTANT','ADMIN','OWNER')";

    private final ExpenseVoucherService voucherService;
    private final com.nhatnam.server.service.SupplierManagementService supplierManagementService;

    private String roleOf(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return null;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))   // bỏ qua permission
                .map(a -> a.substring(5))                          // ROLE_OWNER → OWNER
                .findFirst()
                .orElse(null);
    }


    /**
     * DANH MỤC KHOẢN CHI ĐANG BẬT — POOL DÙNG CHUNG cho mọi NCC.
     *
     * <p>Đặt dưới /api/expense-vouchers/** để mọi role được phép TẠO phiếu chi
     * (gồm cả SUPER_WAREHOUSE) đều đọc được, mà không lộ dữ liệu giá/công nợ NCC.
     */
    @GetMapping("/expense-categories")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<java.util.List<com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.VendorExpenseCategoryDto>>
    expenseCategories() {
        return ApiResponse.ok(supplierManagementService.listCategories(true));
    }

    /**
     * TẠO NHANH nhãn khoản chi ngay khi lập phiếu chi — dành cho các role được
     * phép TẠO phiếu chi (ACCOUNTANT, SUPER_ACCOUNTANT...), không cần Owner tạo trước.
     *
     * <p>Tên được chuẩn hoá: viết HOA chữ cái đầu của từ đầu tiên (VD: "tiền chành xe"
     * → "Tiền chành xe"). Nếu nhãn đã tồn tại (không phân biệt hoa/thường) thì
     * service sẽ báo lỗi trùng.
     */
    @PostMapping("/expense-categories")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.VendorExpenseCategoryDto>
    createExpenseCategory(
            @RequestBody com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.CategoryUpsertRequest req,
            Authentication auth) {
        // Chuẩn hoá: viết hoa chữ cái đầu (giữ nguyên phần còn lại)
        if (req != null && req.getName() != null) {
            String n = req.getName().trim();
            if (!n.isEmpty()) {
                req.setName(Character.toUpperCase(n.charAt(0)) + n.substring(1));
            }
        }
        String createdBy = auth != null ? auth.getName() : null;
        return ApiResponse.ok(supplierManagementService.createCategory(req, createdBy));
    }

    /**
     * CẬP NHẬT nhãn khoản chi — cho phép kế toán đổi tên danh mục.
     * Phiếu chi cũ snapshot {@code itemName} nên không bị ảnh hưởng.
     */
    @PutMapping("/expense-categories/{categoryId}")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.VendorExpenseCategoryDto>
    updateExpenseCategory(
            @PathVariable Long categoryId,
            @RequestBody com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.CategoryUpsertRequest req) {
        if (req != null && req.getName() != null) {
            String n = req.getName().trim();
            if (!n.isEmpty()) {
                req.setName(Character.toUpperCase(n.charAt(0)) + n.substring(1));
            }
        }
        return ApiResponse.ok(supplierManagementService.updateCategory(categoryId, req));
    }

    /**
     * ẨN nhãn khoản chi (soft-delete) — phiếu chi cũ giữ nguyên tham chiếu.
     */
    @DeleteMapping("/expense-categories/{categoryId}")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<Void> deleteExpenseCategory(@PathVariable Long categoryId) {
        supplierManagementService.deleteCategory(categoryId);
        return ApiResponse.ok(null);
    }

    /**
     * @deprecated Danh mục giờ là POOL CHUNG — {@code vendorId} bị bỏ qua.
     *             Giữ lại để không vỡ client cũ. Dùng {@code GET /expense-categories}.
     */
    @Deprecated
    @GetMapping("/vendor-categories/{vendorId}")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<java.util.List<com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.VendorExpenseCategoryDto>>
    vendorCategories(@PathVariable Long vendorId) {
        return ApiResponse.ok(supplierManagementService.listCategories(true));
    }

    @PostMapping
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<ExpenseVoucherDto> create(
            @Valid @RequestBody CreateExpenseVoucherRequest req, Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.create(user.getId(), req));
    }

    /**
     * TẠO PHIẾU CHI HOÀN PHẦN DƯ cho một đơn khách trả dư.
     * Body: {@code { "orderCode": "ORD-...", "paymentType": "CASH"|"BANK_TRANSFER",
     *                "customerBankName": "...", "customerBankAccount": "...", "customerBankHolder": "..." }}.
     */
    @PostMapping("/refund-overpay")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<ExpenseVoucherDto> refundOverpay(
            @RequestBody RefundOverpayRequest req, Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.createOverpayRefund(
                user.getId(), req.orderCode(),
                req.paymentType(), req.customerBankName(),
                req.customerBankAccount(), req.customerBankHolder()));
    }

    public record RefundOverpayRequest(
            String orderCode,
            String paymentType,         // "CASH" | "BANK_TRANSFER"
            String customerBankName,    // Tên ngân hàng khách hàng
            String customerBankAccount, // Số tài khoản khách hàng
            String customerBankHolder   // Tên chủ tài khoản
    ) {}

    /** Tải file Excel mẫu để nhập phiếu chi hàng loạt (kèm dropdown NCC + khoản chi). */
    @GetMapping("/import-template")
    @PreAuthorize(CREATE_ROLES)
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] bytes = voucherService.buildImportTemplate();
        String now = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));
        String filename = "mau-nhap-phieu-chi-" + now + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** Nhập phiếu chi hàng loạt từ file Excel (các dòng cùng "Mã phiếu" gộp thành 1 phiếu). */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<ExpenseImportResultDto> importFromExcel(
            @RequestParam("file") MultipartFile file, Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.importFromExcel(user.getId(), file));
    }

    /** Xuất báo cáo phiếu chi (Excel) theo khoảng thời gian đang chọn. */
    @GetMapping("/export")
    @PreAuthorize(CREATE_ROLES)
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
            String filename = "phieu-chi-" + fromStr + "_" + toStr + ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            log.error("Lỗi xuất báo cáo phiếu chi", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<PageResponse<ExpenseVoucherDto>> listAll(
            Authentication auth,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        User u = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.listAll(roleOf(auth), u.getId(), pageable));
    }

    @GetMapping("/by-expense-date")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<PageResponse<ExpenseVoucherDto>> listByExpenseDateRange(
            Authentication auth,
            @RequestParam Long from,
            @RequestParam Long to,
            @PageableDefault(size = 20, sort = "expenseDate", direction = Sort.Direction.DESC) Pageable pageable) {
        User u = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.listByExpenseDateRange(roleOf(auth), u.getId(), from, to, pageable));
    }

    /**
     * Tìm kiếm phiếu chi theo từ khóa và khoảng thời gian chi.
     * Đặt endpoint này TRƯỚC endpoint /{id}
     */
    @GetMapping("/search-by-expense-date")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<PageResponse<ExpenseVoucherDto>> searchByExpenseDateRange(
            Authentication auth,
            @RequestParam String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @PageableDefault(size = 20, sort = "expenseDate", direction = Sort.Direction.DESC) Pageable pageable) {
        User u = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.searchByExpenseDateRange(roleOf(auth), u.getId(), q.trim(), from, to, pageable));
    }

    @GetMapping("/next-payment-number")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<String> suggestNextPaymentNumber() {
        return ApiResponse.ok(voucherService.suggestNextPaymentNumber());
    }

    // ── Cấu hình duyệt (OWNER quản lý) ────────────────────────────────────────
    @GetMapping("/approval-config")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<ExpenseApprovalConfigDto> getApprovalConfig() {
        return ApiResponse.ok(voucherService.getApprovalConfig());
    }

    @PutMapping("/approval-config")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ApiResponse<ExpenseApprovalConfigDto> updateApprovalConfig(
            @RequestBody ExpenseApprovalConfigDto dto, Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.updateApprovalConfig(user.getId(), dto));
    }

    @GetMapping("/{id}")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<ExpenseVoucherDto> getById(@PathVariable Long id) {
        return ApiResponse.ok(voucherService.getById(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE_ROLES)
    public ApiResponse<ExpenseVoucherDto> approve(
            @PathVariable Long id,
            @RequestBody(required = false) ApproveExpenseVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        String note = req != null ? req.getNote() : null;
        return ApiResponse.ok(voucherService.approve(id, user.getId(), note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE_ROLES)
    public ApiResponse<ExpenseVoucherDto> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectExpenseVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.reject(id, user.getId(), req.getReason()));
    }

    /**
     * DUYỆT HÀNG LOẠT — nhận danh sách id do client gom lại, kể cả các phiếu được
     * tick ở nhiều trang khác nhau (backend không phụ thuộc phân trang).
     * Mỗi phiếu xử lý độc lập; kết quả trả về nêu rõ phiếu nào lỗi và vì sao.
     *
     * <p>Mở cho cả SUPER_ACCOUNTANT: quyền THỰC SỰ vẫn do {@code assertCanApprove}
     * quyết định trên TỪNG phiếu — SA chỉ duyệt được phiếu có
     * {@code approverScope = SUPER_ACCOUNTANT}, phiếu ngoài tầm sẽ báo lỗi riêng
     * trong kết quả chứ không làm hỏng cả lô.
     */
    @PostMapping("/bulk-approve")
    @PreAuthorize(APPROVE_ROLES)
    public ApiResponse<com.nhatnam.server.dto.expense.BulkExpenseActionResultDto> bulkApprove(
            @Valid @RequestBody com.nhatnam.server.dto.expense.BulkExpenseActionRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.bulkApprove(user.getId(), req));
    }

    /**
     * ONE-TIME MIGRATION: duyệt TẤT CẢ phiếu chi còn PENDING bằng Owner user_id=2.
     * Thời điểm duyệt = thời gian hiện tại. Chỉ cho OWNER/ADMIN gọi.
     *
     * <p>Trả về {@code { approved: <số phiếu đã duyệt> }}. Chạy xong có thể gỡ bỏ.
     */
    @PostMapping("/admin/approve-all-pending-by-owner2")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<java.util.Map<String, Integer>> approveAllPendingByOwner2() {
        int n = voucherService.approveAllPendingByOwner2();
        return ApiResponse.ok(java.util.Map.of("approved", n));
    }

    /** TỪ CHỐI HÀNG LOẠT — dùng chung một lý do từ chối cho mọi phiếu được chọn. */
    @PostMapping("/bulk-reject")
    @PreAuthorize(APPROVE_ROLES)
    public ApiResponse<com.nhatnam.server.dto.expense.BulkExpenseActionResultDto> bulkReject(
            @Valid @RequestBody com.nhatnam.server.dto.expense.BulkExpenseActionRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.bulkReject(user.getId(), req));
    }

    /**
     * CHUYỂN PHIẾU VỀ LẠI CHỜ DUYỆT — chỉ OWNER/ADMIN, áp dụng cho phiếu đã duyệt
     * hoặc đã từ chối. Ghi nhật ký kèm vai trò đang active trong JWT (OWNER/ADMIN).
     */
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ApiResponse<ExpenseVoucherDto> reopen(
            @PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, String> body,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        String note = body != null ? body.get("note") : null;
        return ApiResponse.ok(voucherService.reopen(id, user.getId(), note));
    }

    /** Nhật ký thao tác của một phiếu chi (duyệt / từ chối / mở lại / sửa). */
    @GetMapping("/{id}/logs")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<List<com.nhatnam.server.dto.expense.ExpenseVoucherLogDto>> logs(
            @PathVariable Long id) {
        return ApiResponse.ok(voucherService.getLogs(id));
    }

    @PatchMapping("/{id}/reason")
    @PreAuthorize(CREATE_ROLES)
    public ApiResponse<ExpenseVoucherDto> updateReason(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.updateReason(id, user.getId(), body.get("reason")));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<ExpenseVoucherDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateExpenseVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.updateVoucher(id, user.getId(), req));
    }

    /**
     * SỬA DANH SÁCH KHOẢN CHI của phiếu (thay thế toàn bộ: sửa / thêm / xoá khoản chi).
     * <ul>
     *   <li>Phiếu ĐANG CHỜ DUYỆT — ACCOUNTANT / SUPER_ACCOUNTANT / OWNER / ADMIN đều sửa được
     *       nhãn khoản chi, số tiền, thêm khoản mới và xoá khoản khỏi phiếu.</li>
     *   <li>Phiếu ĐÃ DUYỆT — chỉ OWNER / ADMIN. Kế toán chỉ sửa được lý do chi
     *       (dùng {@code PATCH /{id}/reason}).</li>
     *   <li>Phiếu ĐÃ TỪ CHỐI — không sửa được.</li>
     * </ul>
     * Backend tự TÍNH LẠI cấp duyệt theo tổng tiền mới: xoá bớt khoản chi làm tổng tiền
     * tụt dưới ngưỡng (và danh mục được phép) thì SUPER_ACCOUNTANT duyệt được phiếu.
     */
    @PatchMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<ExpenseVoucherDto> updateItems(
            @PathVariable Long id,
            @Valid @RequestBody com.nhatnam.server.dto.expense.UpdateExpenseItemsRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(voucherService.updateItems(id, user.getId(), req));
    }

    // ── Xuất PDF phiếu chi (Mẫu 02-TT) ─────────────────────────────────────
    private final com.nhatnam.server.service.AccountingVoucherPdfService accountingPdfService;

    @GetMapping("/{id}/pdf")
    @PreAuthorize(CREATE_ROLES)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        try {
            byte[] data = accountingPdfService.generateExpenseVoucher(id);
            String filename = java.net.URLEncoder.encode("phieu-chi-" + id + ".pdf",
                    java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .body(data);
        } catch (Exception e) {
            log.error("Export expense voucher PDF failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}