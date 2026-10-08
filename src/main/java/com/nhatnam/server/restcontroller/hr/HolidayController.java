package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.hr.HolidayDtos.*;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.hr.HolidayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * API quản lý Ngày lễ (Phase 1).
 *
 * <pre>
 *   GET    /api/hr/holidays?year=2026                  — danh sách
 *   POST   /api/hr/holidays                             — thêm 1 ngày lễ
 *   DELETE /api/hr/holidays/{id}                        — xoá 1 ngày
 *   DELETE /api/hr/holidays?year=2026                   — xoá toàn bộ năm
 *   POST   /api/hr/holidays/import?replaceYear=2026     — import Excel (query 'replaceYear' tuỳ chọn)
 *   GET    /api/hr/holidays/template?year=2026          — tải file mẫu
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/hr/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    private static final String ADMIN_ROLES = "hasAnyRole('OWNER','ADMIN','HR','SUPER_ACCOUNTANT')";

    @GetMapping
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<List<HolidayDto>>> list(@RequestParam int year) {
        return ResponseEntity.ok(ApiResponse.success(holidayService.listByYear(year), "OK"));
    }

    @PostMapping
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<HolidayDto>> create(
            @RequestBody CreateHolidayRequest req,
            @AuthenticationPrincipal User user) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    holidayService.create(req, user),
                    "Đã lưu ngày lễ " + req.getDate()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[Holiday] Lỗi lưu", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable Long id) {
        try {
            holidayService.delete(id);
            return ResponseEntity.ok(ApiResponse.success("OK", "Đã xoá ngày lễ"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<String>> deleteYear(@RequestParam int year) {
        long n = holidayService.deleteYear(year);
        return ResponseEntity.ok(ApiResponse.success("OK",
                "Đã xoá " + n + " ngày lễ của năm " + year));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<ApiResponse<ImportHolidayResult>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "replaceYear", required = false) Integer replaceYear,
            @AuthenticationPrincipal User user) {
        try {
            ImportHolidayResult r = holidayService.importExcel(file, replaceYear, user);
            return ResponseEntity.ok(ApiResponse.success(r,
                    "Đã import %d/%d ngày lễ".formatted(r.getSaved(), r.getTotalRows())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[Holiday] Lỗi import", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/template")
    @PreAuthorize(ADMIN_ROLES)
    public ResponseEntity<Resource> template(@RequestParam int year) throws Exception {
        byte[] data = holidayService.buildTemplate(year);
        String filename = "mau-ngay-le-" + year + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }
}
