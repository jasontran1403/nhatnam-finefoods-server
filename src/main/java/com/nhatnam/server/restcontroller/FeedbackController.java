package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.feedback.FeedbackDtos.*;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.FeedbackService;
import com.nhatnam.server.service.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * API FEEDBACK — tạo, đọc, upload ảnh cho feedback KH.
 *
 * <p>URL {@code /api/feedback/**} không nằm trong prefix nào có sẵn của
 * SecurityConfiguration, nên rơi vào rule mặc định {@code anyRequest().authenticated()}.
 * Phân quyền chi tiết được áp dụng qua {@link PreAuthorize} trên từng endpoint.
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Log4j2
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final FileStorageService fileStorageService;

    /** Tra thông tin đơn để dựng form. */
    @GetMapping("/lookup/{orderCode}")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<OrderLookupDto> lookup(@PathVariable String orderCode) {
        try {
            return ApiResponse.ok(feedbackService.lookupOrder(orderCode));
        } catch (RuntimeException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * List feedback theo mã đơn — dùng cho SELLER/SUPER_SELLER/WAREHOUSE để khi tra đơn
     * thấy được feedback cũ (thay vì tạo lại). OWNER/ADMIN cũng cho phép để mở
     * xem chi tiết đơn nào đó nếu cần.
     */
    @GetMapping("/by-order/{orderCode}")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<List<FeedbackDto>> byOrder(@PathVariable String orderCode) {
        try {
            return ApiResponse.ok(feedbackService.listByOrderCode(orderCode));
        } catch (Exception e) {
            log.error("[Feedback] byOrder error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * Upload nhiều ảnh cùng lúc — trả về list URL để FE gắn vào {@link CreateFeedbackRequest}.
     *
     * <p>Tách khỏi endpoint POST /api/feedback để (1) FE có thể upload từng ảnh song song,
     * (2) một ảnh fail không làm mất form đã điền. Cùng pattern các nghiệp vụ khác dùng
     * FileStorageService.
     */
    @PostMapping(value = "/images", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE','SUPERADMIN')")
    public ApiResponse<Map<String, Object>> uploadImages(@RequestParam("files") MultipartFile[] files) {
        try {
            if (files == null || files.length == 0) {
                return ApiResponse.error("Không có ảnh nào để upload");
            }
            List<String> urls = new ArrayList<>(files.length);
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                urls.add(fileStorageService.saveFeedbackImage(f));
            }
            return ApiResponse.ok("Đã upload " + urls.size() + " ảnh", Map.of("urls", urls));
        } catch (Exception e) {
            log.error("[Feedback] uploadImages error", e);
            return ApiResponse.error("Lỗi upload ảnh: " + e.getMessage());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','WAREHOUSE','SUPER_WAREHOUSE','SUPERADMIN')")
    public ApiResponse<FeedbackDto> create(@Valid @RequestBody CreateFeedbackRequest req,
                                            Authentication auth) {
        try {
            User actor = (User) auth.getPrincipal();
            return ApiResponse.ok("Đã ghi nhận feedback", feedbackService.create(req, actor));
        } catch (RuntimeException e) {
            log.warn("[Feedback] create error: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("[Feedback] create error", e);
            return ApiResponse.error("Không tạo được feedback");
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<FeedbackPage> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.ok(feedbackService.list(q, page, size));
        } catch (Exception e) {
            log.error("[Feedback] list error", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
