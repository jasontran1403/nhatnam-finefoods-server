package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.request.QuotationRequest;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.QuotationPdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/seller/quotations")
public class QuotationController {

    private final QuotationPdfService quotationPdfService;

    /**
     * POST /api/seller/quotations/export-pdf
     *
     * Tạo và trả về file PDF báo giá.
     * Yêu cầu đăng nhập (SELLER / ADMIN / OWNER ...).
     */
    @PostMapping("/export-pdf")
    public ResponseEntity<?> exportPdf(
            @RequestBody QuotationRequest request,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // Validate tối thiểu
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body("Danh sách sản phẩm không được trống");
        }

        User creator = null;
        try {
            creator = (User) authentication.getPrincipal();
        } catch (Exception e) {
            log.warn("[Quotation] Không lấy được user từ Authentication");
        }

        try {
            byte[] pdfBytes = quotationPdfService.generate(request, creator);

            String filename = "BaoGia_" + LocalDate.now().toString().replace("-", "") + ".pdf";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBytes);

        } catch (IllegalArgumentException e) {
            log.warn("[Quotation] Bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("[Quotation] Lỗi tạo PDF báo giá", e);
            return ResponseEntity.internalServerError()
                    .body("Lỗi khi tạo báo giá: " + e.getMessage());
        }
    }
}