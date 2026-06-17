package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.CertificateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/certificates")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('OWNER','OPERATOR','ADMIN')")
public class CertificateController {

    private final CertificateService certService;

    /** Danh sách sản phẩm có chứng nhận (kèm số lượng) */
    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<CertificateService.ProductWithCertCount>>> listProductsWithCerts() {
        return ResponseEntity.ok(ApiResponse.success(certService.listProductsWithCerts(), "OK"));
    }

    /** Danh sách tất cả sản phẩm để chọn khi upload */
    @GetMapping("/all-products")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAllProducts() {
        return ResponseEntity.ok(ApiResponse.success(certService.listAllProducts(), "OK"));
    }

    /** Danh sách chứng nhận của 1 sản phẩm */
    @GetMapping("/by-product/{productId}")
    public ResponseEntity<ApiResponse<List<CertificateService.CertDto>>> listByProduct(
            @PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success(certService.listByProduct(productId), "OK"));
    }

    /** Xem chi tiết 1 chứng nhận */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CertificateService.CertDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(certService.getById(id), "OK"));
    }

    /**
     * Tạo chứng nhận mới (multipart: certName, issuedAt, expiredAt?, files[])
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CertificateService.CertDto>> create(
            @RequestParam("productId") Long productId,
            @RequestParam("certName") String certName,
            @RequestParam("issuedAt") Long issuedAt,
            @RequestParam(value = "expiredAt", required = false) Long expiredAt,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        try {
            CertificateService.CertDto dto = certService.createCert(productId, certName, issuedAt, expiredAt, files);
            return ResponseEntity.ok(ApiResponse.success(dto, "Tạo chứng nhận thành công"));
        } catch (Exception e) {
            log.error("❌ createCert", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Xóa 1 chứng nhận (kéo theo tất cả file) */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCert(@PathVariable Long id) {
        try {
            certService.deleteCert(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa chứng nhận"));
        } catch (Exception e) {
            log.error("❌ deleteCert", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Xóa 1 file trong chứng nhận */
    @DeleteMapping("/{certId}/files/{fileId}")
    public ResponseEntity<ApiResponse<CertificateService.CertDto>> deleteFile(
            @PathVariable Long certId, @PathVariable Long fileId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(certService.deleteFile(certId, fileId), "Đã xóa file"));
        } catch (Exception e) {
            log.error("❌ deleteCertFile", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}
