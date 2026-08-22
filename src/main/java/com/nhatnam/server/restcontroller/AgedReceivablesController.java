package com.nhatnam.server.restcontroller;

import com.nhatnam.server.service.AgedReceivablesReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Báo cáo công nợ theo tuổi nợ (Aged Receivables) — xuất PDF.
 *
 * GET /api/accountant/reports/aged-receivables?asOf=2026-02-14
 *   - asOf (tuỳ chọn, ISO yyyy-MM-dd): ngày xuất báo cáo. Mặc định = hôm nay (giờ VN).
 *   - customerIds (tuỳ chọn, lặp lại hoặc ngăn cách bởi dấu phẩy): CHỈ xuất đúng
 *     những khách hàng được chọn ở modal. Khi có tham số này, các bộ lọc
 *     q/type/isActive/sellerId sẽ bị bỏ qua (lựa chọn tường minh được ưu tiên).
 *
 * Quyền: SUPER_ACCOUNTANT, ACCOUNTANT, OWNER, ADMIN.
 * (Đặt dưới /api/accountant/** để dùng lại cấu hình security sẵn có; @PreAuthorize
 *  siết lại đúng 4 role theo yêu cầu.)
 */
@RestController
@RequestMapping("/api/accountant/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','ACCOUNTANT','OWNER','ADMIN')")
public class AgedReceivablesController {

    private final AgedReceivablesReportService reportService;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @GetMapping("/aged-receivables")
    public ResponseEntity<byte[]> exportAgedReceivables(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) com.nhatnam.server.entity.Customer.CustomerType type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) java.util.List<Long> customerIds
    ) {
        LocalDate reportDate = (asOf != null) ? asOf : LocalDate.now(VN);
        byte[] pdf = reportService.generatePdf(reportDate, q, type, isActive, sellerId, customerIds);

        String filename = "aged-receivables_" + reportDate.format(FILE_DATE) + ".pdf";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}