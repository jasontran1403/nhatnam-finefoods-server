package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class CertificateService {

    private final ProductCertificateRepository certRepo;
    private final ProductCertificateFileRepository fileRepo;
    private final ProductRepository productRepo;
    private final FileStorageService fileStorageService;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ProductWithCertCount(
            Long productId, String productName, String imageUrl, long certCount) {}

    public record CertFileDto(
            Long id, String fileUrl, String originalName, String fileType, Long createdAt) {}

    public record CertDto(
            Long id, Long productId, String productNameSnapshot,
            String certName, Long issuedAt, Long expiredAt,
            Long createdAt, Long updatedAt, List<CertFileDto> files) {}

    // ── List products that have certificates ─────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProductWithCertCount> listProductsWithCerts() {
        List<Object[]> rows = certRepo.countByProduct();
        if (rows.isEmpty()) return Collections.emptyList();

        // countByProduct chỉ trả về productId != null
        Map<Long, Long> countMap = rows.stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        List<Product> products = productRepo.findAllById(new ArrayList<>(countMap.keySet()));
        Map<Long, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        return countMap.entrySet().stream()
                .map(e -> {
                    Long pid = e.getKey();
                    Long cnt = e.getValue();
                    Product p = productMap.get(pid);
                    // Product có thể đã soft-delete (isActive=false) nhưng vẫn tồn tại trong DB
                    String name = p != null ? p.getName() : "(Đã xóa #" + pid + ")";
                    String img  = p != null ? p.getImageUrl() : null;
                    return new ProductWithCertCount(pid, name, img, cnt);
                })
                .sorted(Comparator.comparing(ProductWithCertCount::productName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ── List all products (for upload dropdown) ───────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAllProducts() {
        return productRepo.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("imageUrl", p.getImageUrl());
                    return m;
                })
                .toList();
    }

    // ── Get certs for a product ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CertDto> listByProduct(Long productId) {
        return certRepo.findByProductIdWithFiles(productId).stream()
                .sorted((a, b) -> {
                    // null expiredAt (không hạn) → lên đầu
                    if (a.getExpiredAt() == null && b.getExpiredAt() == null) return 0;
                    if (a.getExpiredAt() == null) return -1;
                    if (b.getExpiredAt() == null) return 1;
                    // hạn xa nhất → trước
                    return Long.compare(b.getExpiredAt(), a.getExpiredAt());
                })
                .map(this::toDto)
                .toList();
    }

    // ── Get single cert ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CertDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    // ── Create certificate ────────────────────────────────────────────────────

    @Transactional
    public CertDto createCert(Long productId, String certName, Long issuedAt, Long expiredAt,
                               List<MultipartFile> files) throws IOException {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại: " + productId));

        long now = System.currentTimeMillis();
        ProductCertificate cert = ProductCertificate.builder()
                .productId(productId)
                .productNameSnapshot(product.getName())   // ← snapshot tên
                .certName(certName)
                .issuedAt(issuedAt)
                .expiredAt(expiredAt)
                .createdAt(now)
                .updatedAt(now)
                .build();

        cert = certRepo.save(cert);

        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                String url = fileStorageService.saveCertificateFile(f);
                String ext = getExt(f.getOriginalFilename());
                String fileType = "pdf".equals(ext) ? "pdf" : "image";
                ProductCertificateFile pf = ProductCertificateFile.builder()
                        .certificate(cert)
                        .fileUrl(url)
                        .originalName(f.getOriginalFilename())
                        .fileType(fileType)
                        .createdAt(now)
                        .build();
                fileRepo.save(pf);
                cert.getFiles().add(pf);
            }
        }

        return toDto(cert);
    }

    // ── Delete all certificates of a product (called when product is deleted) ─

    /**
     * Xóa toàn bộ chứng nhận của sản phẩm. Gọi từ ProductService khi xóa product.
     */
    @Transactional
    public void deleteAllByProductId(Long productId) {
        List<ProductCertificate> certs = certRepo.findByProductId(productId);
        for (ProductCertificate cert : certs) {
            for (ProductCertificateFile f : cert.getFiles()) {
                tryDeleteFile(f.getFileUrl());
            }
        }
        certRepo.deleteAll(certs);
    }

    // ── Delete certificate (and all its files) ────────────────────────────────

    @Transactional
    public void deleteCert(Long id) {
        ProductCertificate cert = findOrThrow(id);
        for (ProductCertificateFile f : cert.getFiles()) {
            tryDeleteFile(f.getFileUrl());
        }
        certRepo.delete(cert);
    }

    // ── Delete a single file from a certificate ───────────────────────────────

    @Transactional
    public CertDto deleteFile(Long certId, Long fileId) {
        ProductCertificate cert = findOrThrow(certId);
        ProductCertificateFile file = fileRepo.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File không tồn tại: " + fileId));
        if (!file.getCertificate().getId().equals(certId))
            throw new ResourceNotFoundException("File không thuộc chứng nhận này");

        tryDeleteFile(file.getFileUrl());
        cert.getFiles().remove(file);
        fileRepo.delete(file);
        cert.setUpdatedAt(System.currentTimeMillis());
        certRepo.save(cert);
        return toDto(cert);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ProductCertificate findOrThrow(Long id) {
        return certRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Chứng nhận không tồn tại: " + id));
    }

    private void tryDeleteFile(String url) {
        try { fileStorageService.deleteFile(url); }
        catch (Exception e) { log.warn("⚠️ Could not delete cert file: {}", url); }
    }

    private String getExt(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private CertFileDto toFileDto(ProductCertificateFile f) {
        return new CertFileDto(
                f.getId(), f.getFileUrl(), f.getOriginalName(),
                f.getFileType(), f.getCreatedAt());
    }

    private CertDto toDto(ProductCertificate c) {
        List<CertFileDto> fileDtos = c.getFiles() == null
                ? Collections.emptyList()
                : c.getFiles().stream().map(this::toFileDto).toList();
        return new CertDto(
                c.getId(), c.getProductId(), c.getProductNameSnapshot(),
                c.getCertName(), c.getIssuedAt(), c.getExpiredAt(),
                c.getCreatedAt(), c.getUpdatedAt(), fileDtos);
    }
}
