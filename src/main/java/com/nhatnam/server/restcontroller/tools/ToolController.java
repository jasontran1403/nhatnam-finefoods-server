package com.nhatnam.server.restcontroller.tools;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.tools.ToolDtos.*;
import com.nhatnam.server.service.tools.ToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService service;

    @GetMapping("/data")
    public ApiResponse<AllDataDto> getAllData() {
        return ApiResponse.ok(service.getAllData());
    }

    @PostMapping("/invoice-detail")
    public ApiResponse<InvoiceDetailDto> addInvoiceDetail(@RequestBody InvoiceDetailReq req) {
        return ApiResponse.ok(service.addInvoiceDetail(req));
    }

    /**
     * Tạo 1 phiếu thu gộp nhiều đơn hàng.
     * Cột H = "Phiếu thu tiền mặt khách hàng".
     * Cột I và O = "Thu tiền Khách lẻ theo hóa đơn HĐ001,HĐ002,...".
     * Cột T = trống.
     */
    @PostMapping("/invoice-detail/batch")
    public ApiResponse<List<InvoiceDetailDto>> addBatchInvoiceDetail(
            @RequestBody List<InvoiceDetailReq> reqs) {
        return ApiResponse.ok(service.addBatchInvoiceDetail(reqs));
    }

    @PostMapping("/invoice-detail/lookup")
    public ApiResponse<List<LookupResultDto>> lookupInvoiceBatch(
            @RequestBody List<InvoiceDetailReq> reqs) {
        return ApiResponse.ok(service.lookupInvoiceBatch(reqs));
    }

    @GetMapping("/invoice-detail/exists")
    public ApiResponse<Boolean> checkOrderExists(@RequestParam String orderNumber) {
        return ApiResponse.ok(service.isOrderNumberExists(orderNumber));
    }

    @PostMapping("/renumber")
    public ApiResponse<Void> renumber(@RequestBody RenumberReq req) {
        service.renumberDocuments(req.getOldPrefix(), req.getNewPrefix());
        return ApiResponse.ok(null);
    }

    @PostMapping("/receipt/{id}/so-chung-tu")
    public ApiResponse<Void> updateSoChungTu(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.updateSoChungTuFromRow(id, body.get("soChungTu"));
        return ApiResponse.ok(null);
    }

    @PostMapping("/import/tracking")
    public ApiResponse<ImportResult> importTracking(@RequestBody List<Map<String, String>> rows) {
        return ApiResponse.ok(service.importTracking(rows));
    }

    @PostMapping("/import/sales")
    public ApiResponse<ImportResult> importSales(@RequestBody List<Map<String, String>> rows) {
        return ApiResponse.ok(service.importSales(rows));
    }

    @PostMapping("/import/customers")
    public ApiResponse<ImportResult> importCustomers(@RequestBody List<Map<String, String>> rows) {
        return ApiResponse.ok(service.importCustomers(rows));
    }

    @PostMapping("/config")
    public ApiResponse<Void> setConfig(@RequestBody ConfigDto dto) {
        service.setConfig(dto.getKey(), dto.getValue());
        return ApiResponse.ok(null);
    }

    // ─── CRUD cho data đã import ───────────────────────────────────────────

    @GetMapping("/tracking")
    public ApiResponse<Map<String, Object>> listTracking(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.ok(service.listTracking(q, page, size));
    }

    @PutMapping("/tracking/{id}")
    public ApiResponse<Void> updateTracking(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.updateTracking(id, body);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/tracking/{id}")
    public ApiResponse<Void> deleteTracking(@PathVariable Long id) {
        service.deleteTracking(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/sales")
    public ApiResponse<Map<String, Object>> listSales(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.ok(service.listSales(q, page, size));
    }

    @PutMapping("/sales/{id}")
    public ApiResponse<Void> updateSales(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.updateSales(id, body);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/sales/{id}")
    public ApiResponse<Void> deleteSales(@PathVariable Long id) {
        service.deleteSales(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/customers")
    public ApiResponse<Map<String, Object>> listCustomers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.ok(service.listCustomers(q, page, size));
    }

    @PutMapping("/customers/{id}")
    public ApiResponse<Void> updateCustomer(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.updateCustomer(id, body);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/customers/{id}")
    public ApiResponse<Void> deleteCustomer(@PathVariable Long id) {
        service.deleteCustomer(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/invoice-details")
    public ApiResponse<Map<String, Object>> listInvoiceDetails(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.ok(service.listInvoiceDetailsPaged(q, page, size));
    }

    @DeleteMapping("/invoice-details/{id}")
    public ApiResponse<Void> deleteInvoiceDetail(@PathVariable Long id) {
        service.deleteInvoiceDetail(id);
        return ApiResponse.ok(null);
    }

    // ─── MISA ORDER EXPORT ──────────────────────────────────────────────

    @GetMapping("/misa-orders")
    public ApiResponse<Map<String, Object>> listOrdersForMisa(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.listOrdersForMisa(from, to, customerId, status, page, size));
    }

    @PostMapping("/misa-generate")
    public ApiResponse<List<Map<String, Object>>> generateMisaData(@RequestBody List<Long> orderIds) {
        return ApiResponse.ok(service.generateMisaData(orderIds));
    }

    /** Clear all data cho từng loại */
    @DeleteMapping("/clear/tracking")
    public ApiResponse<Void> clearTracking() { service.clearTracking(); return ApiResponse.ok(null); }
    @DeleteMapping("/clear/sales")
    public ApiResponse<Void> clearSales() { service.clearSales(); return ApiResponse.ok(null); }
    @DeleteMapping("/clear/customers")
    public ApiResponse<Void> clearCustomers() { service.clearCustomers(); return ApiResponse.ok(null); }
    @DeleteMapping("/clear/invoice-details")
    public ApiResponse<Void> clearInvoiceDetails() { service.clearInvoiceDetails(); return ApiResponse.ok(null); }
    @DeleteMapping("/clear/receipts")
    public ApiResponse<Void> clearReceipts() { service.clearReceipts(); return ApiResponse.ok(null); }
    /** Xóa cả phiếu đặt hàng đã nhập VÀ phiếu thu (dùng cho nút "Xóa tất cả" ở tab Phiếu đặt hàng). */
    @DeleteMapping("/clear/invoice-and-receipts")
    public ApiResponse<Void> clearInvoiceDetailsAndReceipts() { service.clearInvoiceDetailsAndReceipts(); return ApiResponse.ok(null); }
}