package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('SELLER','ADMIN','OWNER','POS','WAREHOUSE','OPERATOR','ACCOUNTANT','SUPER_ACCOUNTANT','SUPER_WAREHOUSE')")
public class FileUploadController {

    private final FileStorageService fileStorageService;

    private Map<String, String> buildResult(String imageUrl) {
        return Map.of(
                "imageUrl", imageUrl,
                "filename", imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
        );
    }

    @PostMapping("/inventory-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadInventoryImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            return ResponseEntity.ok(ApiResponse.success(
                    buildResult(fileStorageService.saveInventoryImage(file)), "Image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadInventoryImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/categories/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<String>> uploadCategoryImage(
            @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    fileStorageService.saveCategoryImage(file), "Image uploaded successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (IOException e) {
            log.error("❌ Failed to upload category image", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Failed to upload image"));
        }
    }

    @PostMapping("/product-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadProductImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            return ResponseEntity.ok(ApiResponse.success(
                    buildResult(fileStorageService.saveProductImage(file)), "Image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadProductImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/ingredient-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadIngredientImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            return ResponseEntity.ok(ApiResponse.success(
                    buildResult(fileStorageService.saveIngredientImage(file)), "Image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadIngredientImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/expense-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadExpenseImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            return ResponseEntity.ok(ApiResponse.success(
                    buildResult(fileStorageService.saveExpenseImage(file)), "Image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadExpenseImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Endpoint mới riêng cho phiếu thu — lưu vào folder income-voucher */
    @PostMapping("/income-image")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadIncomeImage(
            @RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Image file is required"));
            return ResponseEntity.ok(ApiResponse.success(
                    buildResult(fileStorageService.saveIncomeImage(file)), "Image uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadIncomeImage", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/product-images")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadProductImages(
            @RequestParam("images") List<MultipartFile> files) {
        try {
            if (files == null || files.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "At least one image is required"));
            if (files.size() > 5)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Maximum 5 images allowed"));

            List<Map<String, String>> uploaded = new ArrayList<>();
            for (MultipartFile f : files) {
                if (!f.isEmpty()) uploaded.add(buildResult(fileStorageService.saveProductImage(f)));
            }

            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("images", uploaded, "count", uploaded.size()), "Images uploaded successfully"));
        } catch (IOException e) {
            log.error("❌ uploadProductImages", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}