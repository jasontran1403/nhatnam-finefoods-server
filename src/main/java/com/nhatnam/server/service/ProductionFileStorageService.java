package com.nhatnam.server.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Lưu ảnh production module.
 * Tất cả file lưu FLAT vào BASE/ (không nested subdirectory).
 * URL trả về: /images/production/{filename}
 * Serve qua: GET /api/auth/images/production/{filename}
 * FileStorageServiceImpl.resolvePath("production") → BASE/
 */
@Service
@Log4j2
public class ProductionFileStorageService {

    // Phải trùng PRODUCTION_IMAGE_PATH trong FileStorageServiceImpl
    private static final String BASE =
            System.getProperty("user.home") + "/Desktop/nhatnam-finefoods-storage/production-files";

    private static final List<String> ALLOWED = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "heic", "heif", "pdf"
    );

    public ProductionFileStorageService() {
        try {
            Files.createDirectories(Paths.get(BASE));
        } catch (IOException e) {
            throw new RuntimeException("Cannot create production storage directory", e);
        }
    }

    // ── Public save methods ───────────────────────────────────────────────────

    public String saveBatchStepImage(Long batchId, int stepSeq, MultipartFile file) throws IOException {
        return saveFile(file, "step" + stepSeq + "-b" + batchId);
    }

    public String saveBatchCancelImage(Long batchId, MultipartFile file) throws IOException {
        return saveFile(file, "cancel-b" + batchId);
    }

    public String saveMaintenanceBeforeImage(Long maintenanceId, MultipartFile file) throws IOException {
        return saveFile(file, "maint-before-" + maintenanceId);
    }

    public String saveMaintenanceAfterImage(Long maintenanceId, MultipartFile file) throws IOException {
        return saveFile(file, "maint-after-" + maintenanceId);
    }

    public String saveMaintenanceReceiptImage(Long maintenanceId, MultipartFile file) throws IOException {
        return saveFile(file, "maint-receipt-" + maintenanceId);
    }

    public String saveMaterialInvoiceImage(Long workOrderId, MultipartFile file) throws IOException {
        return saveFile(file, "invoice-wo" + workOrderId);
    }

    public String saveMaterialRequestInvoiceImage(Long requestId, MultipartFile file) throws IOException {
        return saveFile(file, "mr-invoice-" + (requestId != null ? requestId : "new"));
    }

    // ── Core: lưu raw bytes, không resize ────────────────────────────────────

    private String saveFile(MultipartFile file, String prefix) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Invalid filename");

        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase() : "jpg";
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException("File type not allowed: " + ext);

        String filename = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "." + ext;
        Path target = Paths.get(BASE, filename);

        Files.write(target, file.getBytes());

        String url = "/images/production/" + filename;

        return url;
    }
}