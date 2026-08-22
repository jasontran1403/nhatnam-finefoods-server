package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.voucher.VoucherDtos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.Voucher;
import com.nhatnam.server.service.VoucherPdfService;
import com.nhatnam.server.service.VoucherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * API QUẢN LÝ VOUCHER.
 *
 * <p>Quyền: OWNER/ADMIN toàn quyền; SELLER/SUPER_SELLER được tạo và xem (tặng khách của
 * mình khi tới sinh nhật / khai trương), nhưng KHÔNG được xoá cứng — voucher đã phát ra
 * là chứng từ, thu hồi thì dùng {@code /cancel} để còn dấu vết.
 */
@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
@Log4j2
public class VoucherController {

    private final VoucherService voucherService;
    private final VoucherPdfService voucherPdfService;
    private final com.nhatnam.server.service.VoucherRedemptionService voucherRedemptionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER','ACCOUNTANT','SUPER_ACCOUNTANT')")
    public ApiResponse<PageResponse<VoucherDto>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String effectiveStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
            return ApiResponse.ok(voucherService.list(q, customerId, reason, status, effectiveStatus, pageable));
        } catch (Exception e) {
            log.error("[Voucher] list error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER','ACCOUNTANT','SUPER_ACCOUNTANT')")
    public ApiResponse<VoucherDto> getById(@PathVariable Long id) {
        try {
            return ApiResponse.ok(voucherService.getById(id));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** Toàn bộ voucher của một khách — dùng ở modal chi tiết khách hàng. */
    @GetMapping("/by-customer/{customerId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER','ACCOUNTANT','SUPER_ACCOUNTANT')")
    public ApiResponse<List<VoucherDto>> byCustomer(@PathVariable Long customerId) {
        try {
            return ApiResponse.ok(voucherService.listByCustomer(customerId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** Lịch sử sử dụng: voucher này đã trừ vào những đơn hàng nào, mỗi lần bao nhiêu. */
    @GetMapping("/{id}/usages")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER','ACCOUNTANT','SUPER_ACCOUNTANT')")
    public ApiResponse<List<java.util.Map<String, Object>>> usages(@PathVariable Long id) {
        try {
            return ApiResponse.ok(voucherRedemptionService.historyOfVoucher(id));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER')")
    public ApiResponse<VoucherDto> create(@Valid @RequestBody CreateVoucherRequest req,
                                          Authentication auth) {
        try {
            User actor = auth != null ? (User) auth.getPrincipal() : null;
            return ApiResponse.ok("Đã tạo voucher", voucherService.create(req, actor));
        } catch (Exception e) {
            log.warn("[Voucher] create failed: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SUPER_SELLER')")
    public ApiResponse<VoucherDto> update(@PathVariable Long id,
                                          @RequestBody UpdateVoucherRequest req) {
        try {
            return ApiResponse.ok("Đã cập nhật voucher", voucherService.update(id, req));
        } catch (Exception e) {
            log.warn("[Voucher] update #{} failed: {}", id, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SUPER_SELLER')")
    public ApiResponse<VoucherDto> cancel(@PathVariable Long id) {
        try {
            return ApiResponse.ok("Đã thu hồi voucher", voucherService.cancel(id));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            voucherService.delete(id);
            return ApiResponse.ok("Đã xoá voucher", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ── In phiếu ─────────────────────────────────────────────────────────────

    /** Tải phiếu voucher PDF (khổ A5 ngang) để gửi in. */
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER','ACCOUNTANT','SUPER_ACCOUNTANT')")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Voucher v = voucherService.getEntity(id);
        byte[] bytes = voucherPdfService.generate(v);
        return _pdfResponse(bytes, "voucher-" + v.getCode() + ".pdf");
    }

    /**
     * In HÀNG LOẠT — mỗi voucher một trang.
     * @param ids danh sách id ngăn cách bởi dấu phẩy, VD {@code ?ids=1,2,3}
     */
    @GetMapping("/pdf")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SELLER','SUPER_SELLER')")
    public ResponseEntity<byte[]> pdfBatch(@RequestParam String ids) {
        List<Voucher> vouchers = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .map(voucherService::getEntity)
                .toList();
        byte[] bytes = voucherPdfService.generateBatch(vouchers);
        return _pdfResponse(bytes, "vouchers.pdf");
    }

    private ResponseEntity<byte[]> _pdfResponse(byte[] bytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
                new String(filename.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }
}
