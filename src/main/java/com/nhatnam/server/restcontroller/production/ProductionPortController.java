package com.nhatnam.server.restcontroller.production;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.entity.FactoryProduct;
import com.nhatnam.server.entity.Machine;
import com.nhatnam.server.repository.FactoryProductRepository;
import com.nhatnam.server.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Export / Import Excel cho MÁY MÓC và SẢN PHẨM SẢN XUẤT (Owner).
 *
 * <p>Cùng cơ chế với Export/Import Nhà cung cấp:
 * <ul>
 *   <li>Chỉ export các object đang hoạt động (máy: status=ACTIVE; sản phẩm: isActive=true).
 *       Object đã xóa (không hoạt động) thì bỏ qua.</li>
 *   <li>File có cột "Trạng thái" (dropdown 2 giá trị: {@value #STATUS_ACTIVE} / {@value #STATUS_DELETED}).
 *       Khi import, dòng nào đổi sang "Xóa" sẽ bị XÓA MỀM:
 *       <ul>
 *         <li>Máy móc  → status = INACTIVE + thêm tiền tố "[Đã xóa &lt;ts&gt;] " vào tên.</li>
 *         <li>Sản phẩm → isActive = false + thêm tiền tố "[Đã xóa &lt;ts&gt;] " vào tên.</li>
 *       </ul>
 *   </li>
 *   <li>Sheet ẩn __meta chứa token 1-lần: chỉ file vừa export mới import được và mỗi file
 *       chỉ import 1 lần.</li>
 * </ul>
 *
 * <p><b>Lưu ý về cột hiển thị:</b> cột "Xưởng" (máy) và "Đơn vị" (sản phẩm) chỉ để tham khảo —
 * khi import KHÔNG áp dụng thay đổi các cột này. Với sản phẩm liên kết Ingredient, tên/đơn vị
 * do Ingredient quyết định (nguồn sự thật) nên cũng không sửa qua file.
 */
@RestController
@Log4j2
@RequiredArgsConstructor
public class ProductionPortController {

    private final MachineRepository        machineRepository;
    private final FactoryProductRepository productRepository;

    @Value("${application.security.jwt.secret-key}")
    private String jwtSecretKey;

    private static final String STATUS_ACTIVE  = "Hoạt động";
    private static final String STATUS_DELETED = "Xóa";
    private static final String DELETE_PREFIX_FMT = "[Đã xóa %d] ";

    // ── Token 1-lần cho file export (giống Export/Import NCC) ────────────────
    private final ConcurrentHashMap<String, Long> usedExportTokens = new ConcurrentHashMap<>();
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX = "export-production:";

    private String _makeToken(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(jwtSecretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((TOKEN_PREFIX + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { throw new RuntimeException("Không thể tạo token"); }
    }

    private String _generateExportToken() {
        String ts = String.valueOf(System.currentTimeMillis());
        return ts + ":" + _makeToken(ts);
    }

    private void _validateAndConsumeToken(String token) {
        if (token == null || token.isBlank())
            throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
        String[] parts = token.split(":", 2);
        if (parts.length != 2 || !_makeToken(parts[0]).equals(parts[1]))
            throw new IllegalStateException("File không hợp lệ: mã xác thực không khớp. Vui lòng Export lại file mới.");
        if (usedExportTokens.containsKey(token))
            throw new IllegalStateException("File này đã được import rồi. Vui lòng Export file mới để import lại.");
        usedExportTokens.put(token, System.currentTimeMillis());
    }

    private String _cellStr(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private boolean _isDeleteStatus(String statusStr) {
        return statusStr != null && statusStr.trim().equalsIgnoreCase(STATUS_DELETED);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MÁY MÓC
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/owner/factory/machines/export")
    public ResponseEntity<?> exportMachines() {
        try {
            List<Machine> machines = machineRepository.findByStatusOrderByNameAsc(Machine.MachineStatus.ACTIVE);
            List<Object[]> rows = new ArrayList<>();
            for (Machine m : machines) {
                rows.add(new Object[]{
                        m.getId(),
                        m.getName() != null ? m.getName() : "",
                        m.getFactoryName() != null ? m.getFactoryName() : "",
                        STATUS_ACTIVE
                });
            }
            String[] headers = {"ID", "Tên máy", "Xưởng", "Trạng thái"};
            int[]    widths  = {10, 40, 26, 16};
            String note = "⚠ Không xóa cột ID. Trạng thái chọn từ dropdown. Đổi Trạng thái sang \"Xóa\" để ngừng dùng máy (INACTIVE). Cột Xưởng chỉ để tham khảo.";
            byte[] bytes = _buildExcel("DANH SÁCH MÁY MÓC", "máy", note, headers, widths, 3, rows);

            String now = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"danh-sach-may-moc-" + now + ".xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[PRODUCTION-PORT] exportMachines error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping(value = "/api/owner/factory/machines/import", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> importMachines(@RequestParam("file") MultipartFile file) {
        try {
            int updated = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
                try {
                    _validateTokenFromMeta(wb);
                } catch (IllegalStateException ex) {
                    return ApiResponse.error(ex.getMessage());
                }

                Sheet sheet = wb.getSheetAt(0);
                // Layout: row0 title, row1 info, row2 note, row3 header → data từ row 4
                // Cột: 0=ID  1=Tên máy  2=Xưởng(chỉ đọc)  3=Trạng thái
                for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String idStr = _cellStr(row, 0);
                    if (idStr == null || idStr.isBlank()) continue;

                    try {
                        Long id = Long.parseLong(idStr.trim());
                        Machine m = machineRepository.findById(id)
                                .orElseThrow(() -> new IllegalArgumentException("Máy ID=" + id + " không tồn tại"));

                        String nameStr   = _cellStr(row, 1);
                        String statusStr = _cellStr(row, 3);

                        if (_isDeleteStatus(statusStr)) {
                            // Xóa mềm: chỉ đổi khi đang ACTIVE (idempotent)
                            if (m.getStatus() == Machine.MachineStatus.ACTIVE) {
                                m.setName(String.format(DELETE_PREFIX_FMT, System.currentTimeMillis()) + m.getName());
                                m.setStatus(Machine.MachineStatus.INACTIVE);
                                machineRepository.save(m);
                            }
                            updated++;
                            continue;
                        }

                        // Không xóa → cho phép cập nhật tên (nếu có)
                        if (nameStr != null && !nameStr.isBlank() && !nameStr.trim().equals(m.getName())) {
                            m.setName(nameStr.trim());
                            machineRepository.save(m);
                        }
                        updated++;
                    } catch (Exception ex) {
                        errors.add("Dòng " + (r - 3) + ": " + ex.getMessage());
                        skipped++;
                    }
                }
            }

            return _importResult(updated, skipped, errors);
        } catch (Exception e) {
            log.error("[PRODUCTION-PORT] importMachines error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SẢN PHẨM SẢN XUẤT
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/api/owner/factory/products/export")
    public ResponseEntity<?> exportProducts() {
        try {
            List<FactoryProduct> products = productRepository.findByIsActiveTrueOrderByNameAsc();
            List<Object[]> rows = new ArrayList<>();
            for (FactoryProduct p : products) {
                rows.add(new Object[]{
                        p.getId(),
                        p.getName() != null ? p.getName() : "",
                        p.getUnit() != null ? p.getUnit() : "",
                        STATUS_ACTIVE
                });
            }
            String[] headers = {"ID", "Tên thành phẩm", "Đơn vị", "Trạng thái"};
            int[]    widths  = {10, 44, 16, 16};
            String note = "⚠ Không xóa cột ID. Trạng thái chọn từ dropdown. Đổi Trạng thái sang \"Xóa\" để xóa thành phẩm. Cột Tên/Đơn vị chỉ để tham khảo (không áp dụng khi import).";
            byte[] bytes = _buildExcel("DANH SÁCH THÀNH PHẨM SẢN XUẤT", "thành phẩm", note, headers, widths, 3, rows);

            String now = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"danh-sach-thanh-pham-" + now + ".xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[PRODUCTION-PORT] exportProducts error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping(value = "/api/owner/factory/products/import", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> importProducts(@RequestParam("file") MultipartFile file) {
        try {
            int updated = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
                try {
                    _validateTokenFromMeta(wb);
                } catch (IllegalStateException ex) {
                    return ApiResponse.error(ex.getMessage());
                }

                Sheet sheet = wb.getSheetAt(0);
                // Cột: 0=ID  1=Tên(chỉ đọc)  2=Đơn vị(chỉ đọc)  3=Trạng thái
                for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String idStr = _cellStr(row, 0);
                    if (idStr == null || idStr.isBlank()) continue;

                    try {
                        Long id = Long.parseLong(idStr.trim());
                        FactoryProduct p = productRepository.findById(id)
                                .orElseThrow(() -> new IllegalArgumentException("Thành phẩm ID=" + id + " không tồn tại"));

                        String statusStr = _cellStr(row, 3);

                        if (_isDeleteStatus(statusStr)) {
                            boolean active = p.getIsActive() == null || p.getIsActive();
                            if (active) {
                                p.setName(String.format(DELETE_PREFIX_FMT, System.currentTimeMillis()) + p.getName());
                                p.setIsActive(false);
                                productRepository.save(p);
                            }
                            updated++;
                            continue;
                        }
                        // Không xóa → không thay đổi (tên/đơn vị do Ingredient quyết định).
                        updated++;
                    } catch (Exception ex) {
                        errors.add("Dòng " + (r - 3) + ": " + ex.getMessage());
                        skipped++;
                    }
                }
            }

            return _importResult(updated, skipped, errors);
        } catch (Exception e) {
            log.error("[PRODUCTION-PORT] importProducts error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS DÙNG CHUNG
    // ═══════════════════════════════════════════════════════════════════════

    private void _validateTokenFromMeta(XSSFWorkbook wb) {
        int metaIdx = wb.getSheetIndex("__meta");
        if (metaIdx < 0)
            throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
        Row metaRow = wb.getSheetAt(metaIdx).getRow(0);
        String token = metaRow != null ? _cellStr(metaRow, 0) : null;
        _validateAndConsumeToken(token);
    }

    private ApiResponse<Map<String, Object>> _importResult(int updated, int skipped, List<String> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return ApiResponse.ok("Import hoàn tất: " + updated + " thành công, " + skipped + " bỏ qua", result);
    }

    /**
     * Dựng workbook Excel có style (giống file NCC).
     *
     * @param title       tiêu đề in đậm ở dòng đầu
     * @param unitWord    từ đơn vị đếm ở dòng info ("máy", "thành phẩm", ...)
     * @param note        dòng ghi chú hướng dẫn
     * @param headers     tiêu đề các cột
     * @param widths      độ rộng các cột
     * @param statusCol   chỉ số cột "Trạng thái" (được gắn dropdown Hoạt động/Xóa)
     * @param rows        mỗi phần tử là 1 mảng giá trị đúng thứ tự cột; phần tử [0] = ID (Long)
     */
    private byte[] _buildExcel(String title, String unitWord, String note,
                               String[] headers, int[] widths, int statusCol,
                               List<Object[]> rows) throws Exception {
        String exportToken = _generateExportToken();

        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet ws = wb.createSheet("Danh sách");
            ws.setDisplayGridlines(false);

            byte[] C_HEADER = {(byte)26,(byte)26,(byte)46};
            byte[] C_ACCENT = {(byte)201,(byte)168,(byte)76};
            byte[] C_WHITE  = {(byte)255,(byte)255,(byte)255};
            byte[] C_GRAY   = {(byte)250,(byte)247,(byte)242};
            byte[] C_BORDER = {(byte)232,(byte)221,(byte)208};
            byte[] C_TEXT   = {(byte)28,(byte)28,(byte)30};
            byte[] C_SUB    = {(byte)92,(byte)92,(byte)92};
            byte[] C_NOTE   = {(byte)254,(byte)243,(byte)199};

            java.util.function.Function<byte[], XSSFColor> mkColor = b -> new XSSFColor(b, null);
            java.util.function.BiFunction<byte[], byte[], XSSFCellStyle> mkStyle = (bg, fg) -> {
                XSSFCellStyle cs = wb.createCellStyle();
                cs.setFillForegroundColor(mkColor.apply(bg));
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cs.setVerticalAlignment(VerticalAlignment.CENTER);
                XSSFColor bc = mkColor.apply(C_BORDER);
                cs.setBorderTop(BorderStyle.THIN);    cs.setBorderColor(XSSFCellBorder.BorderSide.TOP,    bc);
                cs.setBorderBottom(BorderStyle.THIN); cs.setBorderColor(XSSFCellBorder.BorderSide.BOTTOM, bc);
                cs.setBorderLeft(BorderStyle.THIN);   cs.setBorderColor(XSSFCellBorder.BorderSide.LEFT,   bc);
                cs.setBorderRight(BorderStyle.THIN);  cs.setBorderColor(XSSFCellBorder.BorderSide.RIGHT,  bc);
                XSSFFont font = wb.createFont(); font.setFontName("Arial");
                font.setFontHeightInPoints((short)10); font.setColor(mkColor.apply(fg)); cs.setFont(font);
                return cs;
            };

            XSSFCellStyle titleStyle = mkStyle.apply(C_HEADER, C_ACCENT);
            ((XSSFFont)titleStyle.getFont()).setBold(true); ((XSSFFont)titleStyle.getFont()).setFontHeightInPoints((short)14);
            XSSFCellStyle noteStyle  = mkStyle.apply(C_NOTE, new byte[]{(byte)146,(byte)64,(byte)14});
            ((XSSFFont)noteStyle.getFont()).setFontHeightInPoints((short)8);
            XSSFCellStyle hdrStyle   = mkStyle.apply(C_HEADER, C_WHITE);
            ((XSSFFont)hdrStyle.getFont()).setBold(true); hdrStyle.setAlignment(HorizontalAlignment.CENTER);
            XSSFCellStyle infoStyle  = mkStyle.apply(C_GRAY, C_SUB);
            XSSFCellStyle dataStyle  = mkStyle.apply(C_WHITE, C_TEXT);
            XSSFCellStyle dataGStyle = mkStyle.apply(C_GRAY,  C_TEXT);
            XSSFCellStyle idStyle    = mkStyle.apply(new byte[]{(byte)240,(byte)235,(byte)227}, C_ACCENT);
            ((XSSFFont)idStyle.getFont()).setBold(true); idStyle.setAlignment(HorizontalAlignment.CENTER);

            for (int i = 0; i < widths.length; i++) ws.setColumnWidth(i, widths[i] * 256);

            // Sheet ẩn __data: cột A = nhãn Trạng thái (Hoạt động / Xóa) cho dropdown
            XSSFSheet dataSheet = wb.createSheet("__data");
            wb.setSheetHidden(wb.getSheetIndex("__data"), true);
            List<String> statusLabels = List.of(STATUS_ACTIVE, STATUS_DELETED);
            for (int i = 0; i < statusLabels.size(); i++)
                dataSheet.createRow(i).createCell(0).setCellValue(statusLabels.get(i));

            // Sheet ẩn __meta: token
            XSSFSheet meta = wb.createSheet("__meta");
            wb.setSheetHidden(wb.getSheetIndex("__meta"), true);
            meta.createRow(0).createCell(0).setCellValue(exportToken);

            // Row 0: Title
            Row r0 = ws.createRow(0); r0.setHeightInPoints(28);
            ws.addMergedRegion(new CellRangeAddress(0,0,0,headers.length-1));
            Cell tc = r0.createCell(0); tc.setCellValue(title); tc.setCellStyle(titleStyle);

            // Row 1: Info
            Row r1 = ws.createRow(1); r1.setHeightInPoints(18);
            ws.addMergedRegion(new CellRangeAddress(1,1,0,headers.length-1));
            Cell ic = r1.createCell(0);
            ic.setCellValue("Xuất lúc: " + java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    + "   |   Tổng: " + rows.size() + " " + unitWord + "   |   File chỉ import được 1 lần — Export lại nếu cần");
            ic.setCellStyle(infoStyle);

            // Row 2: Note
            Row r2 = ws.createRow(2); r2.setHeightInPoints(16);
            ws.addMergedRegion(new CellRangeAddress(2,2,0,headers.length-1));
            Cell nc = r2.createCell(0);
            nc.setCellValue(note);
            nc.setCellStyle(noteStyle);

            // Row 3: Header
            Row hdrRow = ws.createRow(3); hdrRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hdrRow.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(hdrStyle);
            }
            ws.createFreezePane(0, 4);

            // Dropdown cho cột Trạng thái — tham chiếu __data!A1:A2
            int DATA_START = 4, DATA_ROWS_MAX = Math.max(rows.size() + 20, 200);
            DataValidationHelper dvh = ws.getDataValidationHelper();
            DataValidationConstraint dvc = dvh.createFormulaListConstraint("__data!$A$1:$A$" + statusLabels.size());
            CellRangeAddressList addr = new CellRangeAddressList(DATA_START, DATA_START + DATA_ROWS_MAX, statusCol, statusCol);
            DataValidation dv = dvh.createValidation(dvc, addr);
            dv.setSuppressDropDownArrow(true); dv.setShowErrorBox(true); ws.addValidationData(dv);

            // Data rows
            for (int i = 0; i < rows.size(); i++) {
                Object[] vals = rows.get(i);
                Row row = ws.createRow(DATA_START + i); row.setHeightInPoints(18);
                boolean gray = i % 2 == 0;
                XSSFCellStyle ds = gray ? dataGStyle : dataStyle;

                for (int c = 0; c < headers.length; c++) {
                    Cell cell = row.createCell(c);
                    Object v = c < vals.length ? vals[c] : "";
                    if (c == 0 && v instanceof Number) {
                        cell.setCellValue(((Number) v).longValue());
                        cell.setCellStyle(idStyle);
                    } else {
                        cell.setCellValue(v != null ? String.valueOf(v) : "");
                        cell.setCellStyle(ds);
                    }
                }
            }

            ws.setAutoFilter(new CellRangeAddress(3, DATA_START + Math.max(rows.size(), 0), 0, headers.length - 1));
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
