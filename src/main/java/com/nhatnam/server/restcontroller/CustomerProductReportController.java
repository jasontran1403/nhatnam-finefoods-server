package com.nhatnam.server.restcontroller;

import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.service.CustomerProductReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seller")
public class CustomerProductReportController {

    private final CustomerProductReportService reportService;
    private final CategoryRepository categoryRepository;

    /** Danh mục để chọn khi xuất báo cáo (chỉ danh mục đang bật). */
    @GetMapping("/report-categories")
    public ResponseEntity<?> reportCategories(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.status(401).build();

        List<Map<String, Object>> data = categoryRepository.findByIsActiveTrueOrderByNameAsc()
                .stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .toList();
        return ResponseEntity.ok(Map.of("message", "OK", "data", data));
    }

    @GetMapping("/customer-product-report")
    public ResponseEntity<byte[]> exportCustomerProductReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long categoryId,
            Authentication authentication
    ) throws IOException {

        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.status(401).build();

        User user = (User) authentication.getPrincipal();
        // (10/2026) SELLER ngoài whitelist → scope theo id của họ (chỉ KH của họ);
        // các role khác + SELLER whitelist → null = toàn hệ thống (hành vi cũ).
        Long scopeSellerId = com.nhatnam.server.common.ReportAccessGuard.sellerScopeId(user);

        byte[] excelBytes = reportService.generateReport(from, to, scopeSellerId, categoryId);

        String filename = String.format("bao-cao-kh-sp_%s_%s.xlsx", from, to);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}