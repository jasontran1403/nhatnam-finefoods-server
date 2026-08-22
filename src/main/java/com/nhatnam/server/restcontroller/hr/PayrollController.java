package com.nhatnam.server.restcontroller.hr;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.hr.PayrollDtos.*;
import com.nhatnam.server.service.hr.PayrollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/payroll")
@RequiredArgsConstructor
@Slf4j
public class PayrollController {

    private final PayrollService payrollService;

    /** Wrapper response nhẹ — đồng bộ format với phần export/import nhân sự. */
    public static class ApiResponseLike {
        public boolean success;
        public String message;
        public Object data;

        public static ApiResponseLike success(Object data, String message) {
            ApiResponseLike r = new ApiResponseLike();
            r.success = true; r.message = message; r.data = data;
            return r;
        }
        public static ApiResponseLike error(String message) {
            ApiResponseLike r = new ApiResponseLike();
            r.success = false; r.message = message; r.data = null;
            return r;
        }
    }

    // ── 1. Tạo phiếu lương tháng + export Excel ─────────────────────────────────

    @GetMapping("/export")
    public ResponseEntity<?> createBatchAndExport(
            @RequestParam int month,
            @RequestParam int year) {
        try {
            byte[] bytes = payrollService.createBatchAndExport(month, year);
            String filename = "tinh-luong-" + month + "-" + year + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[PAYROLL] createBatchAndExport error month={} year={}", month, year, e);
            return ResponseEntity.badRequest().body(ApiResponseLike.error(e.getMessage()));
        }
    }

    // ── 2. Import Excel → tính lương ────────────────────────────────────────────

    @PostMapping("/batches/import")
    public ResponseEntity<ApiResponseLike> importBatch(@RequestParam("file") MultipartFile file) {
        try {
            PayrollBatchDto dto = payrollService.importBatch(file);
            return ResponseEntity.ok(ApiResponseLike.success(dto,
                    "Đã tính lương thành công cho " + dto.getEmployeeCount() + " nhân viên — chờ Owner duyệt"));
        } catch (Exception e) {
            log.error("[PAYROLL] importBatch error", e);
            return ResponseEntity.ok(ApiResponseLike.error(e.getMessage()));
        }
    }

    // ── 3. List & detail ─────────────────────────────────────────────────────────

    @GetMapping("/batches")
    public ResponseEntity<PageResponse<PayrollBatchDto>> listBatches(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Không truyền Sort vào Pageable — query JPQL ở repository đã có
        // ORDER BY cố định (b.year DESC, b.month DESC). Nếu Pageable mang
        // thêm Sort, Hibernate sẽ cố nối thêm ORDER BY thứ 2 vào câu JPQL
        // đã có sẵn ORDER BY, gây lỗi cú pháp SQL (HTTP 500) khi gọi DB.
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(payrollService.listBatches(status, pageable));
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<PayrollBatchDto> getBatch(@PathVariable Long id) {
        return ResponseEntity.ok(payrollService.getBatch(id));
    }

    @GetMapping("/batches/{id}/payslips")
    public ResponseEntity<List<PayslipDto>> getBatchPayslips(@PathVariable Long id) {
        return ResponseEntity.ok(payrollService.getBatchPayslips(id));
    }

    @GetMapping("/my-payslips")
    public ResponseEntity<List<PayslipDto>> getMyPayslips(@RequestParam Long userId) {
        return ResponseEntity.ok(payrollService.getMyPayslips(userId));
    }

    // ── 4. Duyệt / từ chối toàn bộ batch ─────────────────────────────────────────

    @PutMapping("/batches/{id}/approve")
    public ResponseEntity<?> approveBatch(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponseLike.success(payrollService.approveBatch(id), "Đã duyệt phiếu lương"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponseLike.error(e.getMessage()));
        }
    }

    @PutMapping("/batches/{id}/reject")
    public ResponseEntity<?> rejectBatch(@PathVariable Long id, @RequestBody RejectBatchRequest req) {
        try {
            return ResponseEntity.ok(ApiResponseLike.success(payrollService.rejectBatch(id, req), "Đã từ chối phiếu lương"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponseLike.error(e.getMessage()));
        }
    }

    // ── 5. Tải file phiếu lương chi tiết (sau khi duyệt) ────────────────────────

    @GetMapping("/batches/{id}/download")
    public ResponseEntity<?> downloadPayslips(@PathVariable Long id) {
        try {
            byte[] bytes = payrollService.exportPayslipsExcel(id);
            PayrollBatchDto batch = payrollService.getBatch(id);
            String filename = "phieu-luong-" + batch.getMonth() + "-" + batch.getYear() + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[PAYROLL] downloadPayslips error batchId={}", id, e);
            return ResponseEntity.badRequest().body(ApiResponseLike.error(e.getMessage()));
        }
    }
}
