package com.nhatnam.server.restcontroller;

import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
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
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seller")
public class CustomerProductReportController {

    private final CustomerProductReportService reportService;
    private final CategoryRepository categoryRepository;

    /** Các user id đặc biệt được phép xuất báo cáo KH×SP (ngoài OWNER/ADMIN). */
    private static final Set<Long> ALLOWED_USER_IDS = Set.of(12L, 15L);

    private boolean canAccess(User u) {
        if (u == null) return false;
        Role r = u.getRole();
        if (r == Role.OWNER || r == Role.ADMIN || r == Role.SUPERADMIN) return true;
        return ALLOWED_USER_IDS.contains(u.getId());
    }

    /** Danh mục để chọn khi xuất báo cáo (chỉ danh mục đang bật). */
    @GetMapping("/report-categories")
    public ResponseEntity<?> reportCategories(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.status(401).build();
        User user = (User) authentication.getPrincipal();
        if (!canAccess(user)) return ResponseEntity.status(403).build();

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
        if (!canAccess(user)) return ResponseEntity.status(403).build();

        // Báo cáo toàn hệ thống (không giới hạn theo seller) — giữ nguyên hành vi cũ
        byte[] excelBytes = reportService.generateReport(from, to, null, categoryId);

        String filename = String.format("bao-cao-kh-sp_%s_%s.xlsx", from, to);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}
