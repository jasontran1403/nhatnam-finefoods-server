package com.nhatnam.server.restcontroller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.LandingpageCategory;
import com.nhatnam.server.entity.LandingpageEvent;
import com.nhatnam.server.entity.LandingpageProduct;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.LandingpageCategoryRepository;
import com.nhatnam.server.repository.LandingpageEventRepository;
import com.nhatnam.server.repository.LandingpageProductRepository;
import com.nhatnam.server.service.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Log4j2
public class LandingpageController {

    private final LandingpageProductRepository  productRepo;
    private final LandingpageCategoryRepository categoryRepo;
    private final LandingpageEventRepository eventRepo;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    // ── Seed danh mục mặc định khi khởi động ─────────────────────────────────
    @PostConstruct
    public void seedCategories() {
        record CatSeed(String name, String nameVi, int order) {}
        List<CatSeed> seeds = List.of(
                new CatSeed("Sausages",           "Xúc xích",          1),
                new CatSeed("Meat Products",      "Sản phẩm thịt",     2),
                new CatSeed("Hela Seasonings",    "Gia vị Hela",       3),
                new CatSeed("Rich's Cream",       "Kem Rich's",        4)
        );
        for (CatSeed s : seeds) {
            if (!categoryRepo.existsByName(s.name())) {
                categoryRepo.save(LandingpageCategory.builder()
                        .name(s.name())
                        .nameVi(s.nameVi())
                        .sortOrder(s.order())
                        .build());
            }
        }
    }

    // ── PUBLIC: Categories ────────────────────────────────────────────────────

    @GetMapping("/api/auth/landingpage/categories")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCategories() {
        List<Map<String, Object>> list = categoryRepo.findAllByOrderBySortOrderAscNameAsc()
                .stream().map(this::catToMap).toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ── PUBLIC: Products ─────────────────────────────────────────────────────

    @GetMapping("/api/auth/landingpage/products")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPublicProducts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false)    Long categoryId) {
        try {
            Page<LandingpageProduct> pg = (categoryId != null)
                    ? productRepo.findByCategoryIdOrderByCreatedAtDesc(categoryId, PageRequest.of(page, size))
                    : productRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content",     pg.getContent().stream().map(this::toMap).toList());
            data.put("totalItems",  pg.getTotalElements());
            data.put("totalPages",  pg.getTotalPages());
            data.put("currentPage", pg.getNumber());
            data.put("pageSize",    pg.getSize());
            return ResponseEntity.ok(ApiResponse.success(data, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/api/auth/landingpage/products/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPublicProduct(@PathVariable Long id) {
        return productRepo.findById(id)
                .map(p -> ResponseEntity.ok(ApiResponse.success(toMap(p), "OK")))
                .orElse(ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy")));
    }

    // ── OPERATOR: Create product ──────────────────────────────────────────────

    @PostMapping(value = "/api/operator/landingpage/products",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestParam("name")                                    String name,
            @RequestParam(value = "nameEn",        required = false) String nameEn,
            @RequestParam(value = "description",   required = false) String description,
            @RequestParam(value = "descriptionEn", required = false) String descriptionEn,
            @RequestParam(value = "categoryId",    required = false) Long categoryId,
            @RequestParam(value = "image",         required = false) MultipartFile image) {
        try {
            if (name == null || name.isBlank())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Tên sản phẩm là bắt buộc"));

            LandingpageCategory cat = null;
            if (categoryId != null) cat = categoryRepo.findById(categoryId).orElse(null);

            String imagePath = null;
            if (image != null && !image.isEmpty())
                imagePath = fileStorageService.saveLandingpageImage(image);

            long now = System.currentTimeMillis();
            LandingpageProduct p = LandingpageProduct.builder()
                    .name(name.trim())
                    .nameEn(nameEn != null ? nameEn.trim() : null)
                    .description(description)
                    .descriptionEn(descriptionEn)
                    .category(cat)
                    .imagePath(imagePath)
                    .createdAt(now).updatedAt(now)
                    .build();
            p = productRepo.save(p);
            return ResponseEntity.ok(ApiResponse.success(toMap(p), "Tạo thành công"));
        } catch (Exception e) {
            log.error("[LANDING] create error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── OPERATOR: Update product ──────────────────────────────────────────────

    @PutMapping(value = "/api/operator/landingpage/products/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id,
            @RequestParam(value = "name",          required = false) String name,
            @RequestParam(value = "nameEn",        required = false) String nameEn,
            @RequestParam(value = "description",   required = false) String description,
            @RequestParam(value = "descriptionEn", required = false) String descriptionEn,
            @RequestParam(value = "categoryId",    required = false) Long categoryId,
            @RequestParam(value = "image",         required = false) MultipartFile image) {
        try {
            LandingpageProduct p = productRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));

            if (name != null && !name.isBlank())    p.setName(name.trim());
            if (nameEn != null)                      p.setNameEn(nameEn.trim());
            if (description != null)                 p.setDescription(description);
            if (descriptionEn != null)               p.setDescriptionEn(descriptionEn);

            if (categoryId != null) {
                p.setCategory(categoryRepo.findById(categoryId).orElse(null));
            }

            if (image != null && !image.isEmpty()) {
                if (p.getImagePath() != null) {
                    try { fileStorageService.deleteFile(p.getImagePath()); } catch (Exception ignored) {}
                }
                p.setImagePath(fileStorageService.saveLandingpageImage(image));
            }

            p.setUpdatedAt(System.currentTimeMillis());
            p = productRepo.save(p);
            return ResponseEntity.ok(ApiResponse.success(toMap(p), "Cập nhật thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[LANDING] update error id={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── OPERATOR: Delete product ──────────────────────────────────────────────

    @DeleteMapping("/api/operator/landingpage/products/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            LandingpageProduct p = productRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            if (p.getImagePath() != null) {
                try { fileStorageService.deleteFile(p.getImagePath()); } catch (Exception ignored) {}
            }
            productRepo.delete(p);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── PUBLIC: Events ────────────────────────────────────────────────────────

    @GetMapping("/api/auth/landingpage/events")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getEvents() {
        List<Map<String, Object>> list = eventRepo.findRandom(9)
                .stream().map(this::eventToMap).toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ── OPERATOR: Event CRUD ──────────────────────────────────────────────────

    @GetMapping("/api/operator/landingpage/events")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listEvents() {
        List<Map<String, Object>> list = eventRepo.findAll()
                .stream().map(this::eventToMap).toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    /**
     * Feature 6: Tạo event với nhiều ảnh.
     * Hỗ trợ cả field "images" (List, mới) và "image" (single, cũ).
     */
    @PostMapping(value = "/api/operator/landingpage/events",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> createEvent(
            @RequestParam(value = "eventLabel", required = false) String eventLabel,
            @RequestParam(value = "images",     required = false) List<MultipartFile> images,
            @RequestParam(value = "image",      required = false) MultipartFile imageSingle) {
        try {
            List<MultipartFile> allImages = new ArrayList<>();
            if (images != null) allImages.addAll(images.stream().filter(f -> f != null && !f.isEmpty()).toList());
            if (imageSingle != null && !imageSingle.isEmpty()) allImages.add(imageSingle);

            if (allImages.isEmpty())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Phải có ít nhất 1 ảnh"));

            List<String> paths = new ArrayList<>();
            for (MultipartFile file : allImages) {
                paths.add(fileStorageService.saveEventImage(file));
            }

            String imageUrlsJson = objectMapper.writeValueAsString(paths);

            LandingpageEvent e = LandingpageEvent.builder()
                    .eventImgPath(paths.get(0))   // ảnh đầu tiên (backward compat)
                    .eventLabel(eventLabel)
                    .imageUrls(imageUrlsJson)      // tất cả ảnh
                    .createdAt(System.currentTimeMillis())
                    .build();
            e = eventRepo.save(e);
            return ResponseEntity.ok(ApiResponse.success(eventToMap(e), "Tạo thành công"));
        } catch (Exception ex) {
            log.error("[EVENT] create error", ex);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    /**
     * Feature 6: Cập nhật event với nhiều ảnh.
     */
    @PutMapping(value = "/api/operator/landingpage/events/{id}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateEvent(
            @PathVariable Long id,
            @RequestParam(value = "eventLabel", required = false) String eventLabel,
            @RequestParam(value = "images",     required = false) List<MultipartFile> images,
            @RequestParam(value = "image",      required = false) MultipartFile imageSingle) {
        try {
            LandingpageEvent e = eventRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            if (eventLabel != null) e.setEventLabel(eventLabel);

            List<MultipartFile> allImages = new ArrayList<>();
            if (images != null) allImages.addAll(images.stream().filter(f -> f != null && !f.isEmpty()).toList());
            if (imageSingle != null && !imageSingle.isEmpty()) allImages.add(imageSingle);

            if (!allImages.isEmpty()) {
                List<String> paths = new ArrayList<>();
                for (MultipartFile file : allImages) {
                    paths.add(fileStorageService.saveEventImage(file));
                }
                e.setEventImgPath(paths.get(0));
                e.setImageUrls(objectMapper.writeValueAsString(paths));
            }

            e = eventRepo.save(e);
            return ResponseEntity.ok(ApiResponse.success(eventToMap(e), "Cập nhật thành công"));
        } catch (RuntimeException ex) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    @DeleteMapping("/api/operator/landingpage/events/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEvent(@PathVariable Long id) {
        try {
            LandingpageEvent e = eventRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy #" + id));
            if (e.getEventImgPath() != null) {
                try { fileStorageService.deleteFile(e.getEventImgPath()); } catch (Exception ignored) {}
            }
            eventRepo.delete(e);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa"));
        } catch (RuntimeException ex) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(LandingpageProduct p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            p.getId());
        m.put("name",          p.getName());
        m.put("nameEn",        p.getNameEn());
        m.put("imagePath",     p.getImagePath());
        m.put("description",   p.getDescription());
        m.put("descriptionEn", p.getDescriptionEn());
        m.put("category",      p.getCategory() != null ? catToMap(p.getCategory()) : null);
        m.put("categoryId",    p.getCategory() != null ? p.getCategory().getId() : null);
        m.put("createdAt",     p.getCreatedAt());
        m.put("updatedAt",     p.getUpdatedAt());
        return m;
    }

    /** Feature 6: trả imageUrls[] thay vì chỉ 1 ảnh */
    private Map<String, Object> eventToMap(LandingpageEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           e.getId());
        m.put("eventImgPath", e.getEventImgPath());   // backward compat
        m.put("eventLabel",   e.getEventLabel());
        m.put("createdAt",    e.getCreatedAt());

        // Parse JSON array, fallback to single eventImgPath
        List<String> imageList = new ArrayList<>();
        if (e.getImageUrls() != null && !e.getImageUrls().isBlank()) {
            try {
                imageList = objectMapper.readValue(e.getImageUrls(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {}
        }
        if (imageList.isEmpty() && e.getEventImgPath() != null) {
            imageList.add(e.getEventImgPath());
        }
        m.put("imageUrls", imageList);
        return m;
    }

    private Map<String, Object> catToMap(LandingpageCategory c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        c.getId());
        m.put("name",      c.getName());
        m.put("nameVi",    c.getNameVi());
        m.put("sortOrder", c.getSortOrder());
        return m;
    }
}
