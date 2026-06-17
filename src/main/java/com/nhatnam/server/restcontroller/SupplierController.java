package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.Supplier;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accountant/suppliers")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('ACCOUNTANT','SUPER_ACCOUNTANT','ADMIN','OWNER')")
public class SupplierController {

    private final SupplierRepository supplierRepository;

    /** Lấy danh sách nhà cung cấp active (dùng cho dropdown) */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Supplier>>> listActive() {
        return ResponseEntity.ok(ApiResponse.success(
                supplierRepository.findByActiveTrueOrderByNameAsc(), "OK"));
    }

    /** Tìm kiếm / phân trang */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<Supplier>>> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Supplier> result = supplierRepository.searchActive(
                q.isBlank() ? null : q,
                PageRequest.of(page, size, Sort.by("name").ascending()));
        return ResponseEntity.ok(ApiResponse.success(result, "OK"));
    }

    /** Tạo mới */
    @PostMapping
    public ResponseEntity<ApiResponse<Supplier>> create(@RequestBody Supplier req) {
        try {
            req.setId(null);
            req.setActive(true);
            Supplier saved = supplierRepository.save(req);
            return ResponseEntity.ok(ApiResponse.success(saved, "Tạo thành công"));
        } catch (Exception e) {
            log.error("Create supplier error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Cập nhật */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Supplier>> update(
            @PathVariable Long id, @RequestBody Supplier req) {
        return supplierRepository.findById(id).map(s -> {
            s.setName(req.getName());
            s.setPhone(req.getPhone());
            s.setAddress(req.getAddress());
            s.setEmail(req.getEmail());
            s.setContactPerson(req.getContactPerson());
            s.setNote(req.getNote());
            return ResponseEntity.ok(ApiResponse.success(supplierRepository.save(s), "Cập nhật thành công"));
        }).orElse(ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy")));
    }

    /** Ẩn (soft delete) */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        return supplierRepository.findById(id).map(s -> {
            s.setActive(false);
            supplierRepository.save(s);
            return ResponseEntity.ok(ApiResponse.<Void>success(null, "Đã ẩn nhà cung cấp"));
        }).orElse(ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, "Không tìm thấy")));
    }
}
