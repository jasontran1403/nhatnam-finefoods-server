package com.nhatnam.server.restcontroller.income;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.income.CreateIncomeVoucherRequest;
import com.nhatnam.server.dto.income.IncomeVoucherDto;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.service.IncomeVoucherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/income-vouchers")
@RequiredArgsConstructor
public class IncomeVoucherController {

    private final IncomeVoucherService voucherService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
    public ApiResponse<IncomeVoucherDto> create(
            @Valid @RequestBody CreateIncomeVoucherRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        Role creatorRole = user.getRole();
        return ApiResponse.ok(voucherService.create(user.getId(), creatorRole, req));
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
            Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            String exportedBy = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();
            byte[] data = voucherService.exportReport(from, to, exportedBy);

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
}