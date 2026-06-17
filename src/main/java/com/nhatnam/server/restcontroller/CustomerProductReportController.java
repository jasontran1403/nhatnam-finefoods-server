package com.nhatnam.server.restcontroller;

import com.nhatnam.server.entity.User;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/seller")
public class CustomerProductReportController {

    private final CustomerProductReportService reportService;

    @GetMapping("/customer-product-report")
    public ResponseEntity<byte[]> exportCustomerProductReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication
    ) throws IOException {

        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.status(401).build();

        User user = (User) authentication.getPrincipal();
        Long sellerId = user.getId();

        byte[] excelBytes = reportService.generateReport(from, to, null);

        String filename = String.format("bao-cao-kh-sp_%s_%s.xlsx", from, to);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}