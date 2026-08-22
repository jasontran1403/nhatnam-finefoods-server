package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.production.SemiFinishedGoodsDtos.*;
import com.nhatnam.server.entity.PackagingLossReport;
import com.nhatnam.server.entity.SemiFinishedTransferNote;
import com.nhatnam.server.service.SemiFinishedGoodsService;
import com.nhatnam.server.service.WarehouseTransferFormExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Kho bán thành phẩm + Kho Scrap + Phiếu chuyển kho + Biên bản hao hụt đóng gói
 * (quy trình "đóng gói & hao hụt" — 4 bước).
 *
 * - Xem kho bán thành phẩm/scrap, lập phiếu chuyển kho: FACTORY_WORKER / SUPER_FACTORY_WORKER / OWNER
 *   (đã cấp quyền /api/factory/** và /api/owner/production/** ở SecurityConfiguration).
 * - Xác nhận nhận phiếu chuyển kho (Bước 3): FACTORY_ACCOUNTANT (xem /api/factory-accountant/**).
 * - Xuất Excel 3 biểu mẫu: Phiếu xuất kho BTP, Phiếu nhập kho TP, Biên bản hao hụt đóng gói
 *   (xem WarehouseTransferFormExportService để biết quy tắc từng mẫu).
 */
@RestController
@RequiredArgsConstructor
public class SemiFinishedGoodsController {

    private final SemiFinishedGoodsService semiFinishedGoodsService;
    private final WarehouseTransferFormExportService exportService;

    // ─── Kho bán thành phẩm — danh sách tổng hợp theo tên thành phẩm ───────────

    @GetMapping("/api/factory/semi-finished-goods")
    public ApiResponse<List<SemiFinishedSummaryDto>> listSummary(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long factoryId) {
        return ApiResponse.ok(semiFinishedGoodsService.listSummary(q, factoryId));
    }

    // ─── Kho Scrap — danh sách hàng lỗi (mới nhất trước) ───────────────────────

    @GetMapping("/api/factory/scrap-stock")
    public ApiResponse<List<ScrapLotDto>> listScrap(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(semiFinishedGoodsService.listScrap(page, size));
    }

    // ─── Bước 2: Lập phiếu chuyển kho (Trưởng xưởng/NV xưởng) ──────────────────

    @PostMapping("/api/factory/semi-finished-transfers")
    public ApiResponse<TransferNoteDto> createTransferNote(
            @RequestBody CreateTransferNoteRequest req, Authentication auth) {
        return ApiResponse.ok(semiFinishedGoodsService.createTransferNote(req, auth.getName()));
    }

    @GetMapping("/api/factory/semi-finished-transfers")
    public ApiResponse<List<TransferNoteDto>> listTransferNotes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(semiFinishedGoodsService.listTransferNotes(status, page, size));
    }

    @GetMapping("/api/factory/semi-finished-transfers/{id}")
    public ApiResponse<TransferNoteDto> getTransferNote(@PathVariable Long id) {
        return ApiResponse.ok(semiFinishedGoodsService.getTransferNote(id));
    }

    // ─── Bước 3+4: Kế toán kho xưởng xác nhận nhận → tự tính hao hụt ───────────

    @PostMapping("/api/factory-accountant/semi-finished-transfers/{id}/receive")
    public ApiResponse<TransferNoteDto> confirmReceive(
            @PathVariable Long id, @RequestBody ReceiveTransferNoteRequest req, Authentication auth) {
        return ApiResponse.ok(semiFinishedGoodsService.confirmReceive(id, req, auth.getName()));
    }

    @GetMapping("/api/factory-accountant/semi-finished-transfers")
    public ApiResponse<List<TransferNoteDto>> listTransferNotesForAccountant(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(semiFinishedGoodsService.listTransferNotes(status, page, size));
    }

    @GetMapping("/api/factory-accountant/semi-finished-transfers/{id}")
    public ApiResponse<TransferNoteDto> getTransferNoteForAccountant(@PathVariable Long id) {
        return ApiResponse.ok(semiFinishedGoodsService.getTransferNote(id));
    }

    // ─── Biên bản hao hụt đóng gói — xem được bởi OWNER + FACTORY_ACCOUNTANT ───

    @GetMapping("/api/factory-accountant/packaging-loss-reports")
    public ApiResponse<List<PackagingLossReportDto>> listLossReportsForAccountant(
            @RequestParam(required = false) String productName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(semiFinishedGoodsService.listLossReports(productName, page, size));
    }

    @GetMapping("/api/owner/production/packaging-loss-reports")
    public ApiResponse<List<PackagingLossReportDto>> listLossReportsForOwner(
            @RequestParam(required = false) String productName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(semiFinishedGoodsService.listLossReports(productName, page, size));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Xuất Excel 3 biểu mẫu — theo đúng mẫu Word do chủ dự án cung cấp
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 1) Phiếu xuất kho bán thành phẩm — in được ngay sau khi Trưởng xưởng/NV
     * xưởng lập phiếu chuyển kho (PENDING hoặc RECEIVED đều xem được, vì đây
     * là phiếu XUẤT — không phụ thuộc việc kế toán đã nhận hay chưa).
     */
    @GetMapping("/api/factory/semi-finished-transfers/{id}/export-out")
    public ResponseEntity<byte[]> exportTransferOut(@PathVariable Long id) throws java.io.IOException {
        SemiFinishedTransferNote note = semiFinishedGoodsService.getTransferNoteEntity(id);
        byte[] bytes = exportService.exportTransferOut(note);
        return excelResponse(bytes, "phieu-xuat-btp-" + note.getNoteCode() + ".xlsx");
    }

    /**
     * 2) Phiếu nhập kho thành phẩm — CHỈ in được khi kế toán kho đã xác nhận
     * nhận (status = RECEIVED), vì cần số liệu đóng gói thực tế.
     */
    @GetMapping("/api/factory-accountant/semi-finished-transfers/{id}/export-in")
    public ResponseEntity<byte[]> exportTransferIn(@PathVariable Long id) throws java.io.IOException {
        SemiFinishedTransferNote note = semiFinishedGoodsService.getTransferNoteEntity(id);
        byte[] bytes = exportService.exportTransferIn(note);
        return excelResponse(bytes, "phieu-nhap-tp-" + note.getNoteCode() + ".xlsx");
    }

    /**
     * 3) Biên bản ghi nhận hao hụt — chỉ tồn tại (FE chỉ hiện nút) khi
     * lossQty > 0 của phiếu chuyển kho đã được kế toán xác nhận nhận.
     */
    @GetMapping("/api/factory-accountant/packaging-loss-reports/{id}/export")
    public ResponseEntity<byte[]> exportLossReport(@PathVariable Long id) throws java.io.IOException {
        PackagingLossReport report = semiFinishedGoodsService.getLossReportEntity(id);
        byte[] bytes = exportService.exportLossReport(report);
        return excelResponse(bytes, "bien-ban-hao-hut-" + report.getReportCode() + ".xlsx");
    }

    /** Cùng endpoint cho OWNER xem/in lại biên bản hao hụt */
    @GetMapping("/api/owner/production/packaging-loss-reports/{id}/export")
    public ResponseEntity<byte[]> exportLossReportForOwner(@PathVariable Long id) throws java.io.IOException {
        PackagingLossReport report = semiFinishedGoodsService.getLossReportEntity(id);
        byte[] bytes = exportService.exportLossReport(report);
        return excelResponse(bytes, "bien-ban-hao-hut-" + report.getReportCode() + ".xlsx");
    }

    private ResponseEntity<byte[]> excelResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
