package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.vendordebt.SupplierMgmtDtos.*;
import com.nhatnam.server.entity.MaterialVendor;
import com.nhatnam.server.repository.MaterialVendorRepository;
import com.nhatnam.server.service.SupplierManagementService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trang "Quản lý nhà cung cấp" — Owner/Admin (chỉ xem).
 *
 * Base: /api/owner/production/suppliers/**  (dùng chung cho cả Admin qua role).
 */
@RestController
@Log4j2
@RequiredArgsConstructor
@RequestMapping("/api/owner/production/suppliers")
public class SupplierManagementController {

    private static final String ROLES_READ =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN','ACCOUNTANT','SUPER_ACCOUNTANT')";
    private static final String ROLES_MANAGE =
            "hasAnyRole('OWNER','ADMIN','SUPERADMIN')";

    private final SupplierManagementService service;
    private final MaterialVendorRepository  vendorRepository;

    @Value("${application.security.jwt.secret-key}")
    private String jwtSecretKey;

    // ── Token 1-lần cho file export (giống Export/Import khách hàng) ──────────
    private final ConcurrentHashMap<String, Long> usedExportTokens = new ConcurrentHashMap<>();
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX_VENDOR = "export-vendor:";

    /** Nhãn tiếng Việt của từng loại NCC (khớp với FE VENDOR_TYPE_LABELS). */
    private static final LinkedHashMap<MaterialVendor.VendorType, String> TYPE_LABELS = new LinkedHashMap<>();
    static {
        TYPE_LABELS.put(MaterialVendor.VendorType.MATERIAL,         "Nguyên liệu");
        TYPE_LABELS.put(MaterialVendor.VendorType.MACHINE,          "Máy móc");
        TYPE_LABELS.put(MaterialVendor.VendorType.REPAIR,           "Sửa chữa");
        TYPE_LABELS.put(MaterialVendor.VendorType.ELECTRICITY,      "Điện");
        TYPE_LABELS.put(MaterialVendor.VendorType.WATER,            "Nước");
        TYPE_LABELS.put(MaterialVendor.VendorType.GAS,              "Gas");
        TYPE_LABELS.put(MaterialVendor.VendorType.LOGISTICS,        "Vận chuyển");
        TYPE_LABELS.put(MaterialVendor.VendorType.SERVICE,          "Dịch vụ");
        TYPE_LABELS.put(MaterialVendor.VendorType.OTHER,            "Khác");
        TYPE_LABELS.put(MaterialVendor.VendorType.OFFICE_RENTAL,    "Thuê văn phòng");
        TYPE_LABELS.put(MaterialVendor.VendorType.OFFICE_SUPPLIER,  "Văn phòng phẩm");
        TYPE_LABELS.put(MaterialVendor.VendorType.TRUCKING_SERVICE, "Dịch vụ xe tải");
        TYPE_LABELS.put(MaterialVendor.VendorType.DELIVERY_SERVICE, "Dịch vụ giao nhận");
    }

    /** Nhãn trạng thái NCC dùng trong file Excel (hardcode để chọn từ dropdown). */
    private static final String STATUS_ACTIVE  = "Hoạt động";
    private static final String STATUS_DELETED = "Xóa";

    private String _makeToken(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(jwtSecretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((TOKEN_PREFIX_VENDOR + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /** Cập nhật thông tin NCC (tên, loại, liên hệ, địa chỉ, MST) */
    @PreAuthorize(ROLES_MANAGE)
    @PutMapping("/{vendorId}")
    public ApiResponse<SupplierInfoDto> updateVendor(
            @PathVariable Long vendorId,
            @RequestBody UpdateVendorRequest req) {
        return ApiResponse.ok(service.updateVendor(vendorId, req));
    }

    /** Xóa mềm NCC (ẩn + đổi tên tiền tố để tên gốc dùng lại được). */
    @PreAuthorize(ROLES_MANAGE)
    @DeleteMapping("/{vendorId}")
    public ApiResponse<Void> deleteVendor(@PathVariable Long vendorId) {
        service.deleteVendor(vendorId);
        return ApiResponse.ok((Void) null);
    }

    /** Danh sách NCC + công nợ + badge số ngày nợ lâu nhất. sortBy=debt|amount|name */
    @PreAuthorize(ROLES_READ)
    @GetMapping
    public ApiResponse<List<SupplierListItemDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "debt") String sortBy) {
        return ApiResponse.ok(service.listSuppliers(search, sortBy));
    }

    /** Các lô công nợ còn lại của 1 NCC — mở khi click badge */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/{vendorId}/debt-lots")
    public ApiResponse<List<DebtLotDto>> debtLots(@PathVariable Long vendorId) {
        return ApiResponse.ok(service.getDebtLots(vendorId));
    }

    /** Thông tin NCC (đầu trang chi tiết) */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/{vendorId}")
    public ApiResponse<SupplierInfoDto> info(@PathVariable Long vendorId) {
        return ApiResponse.ok(service.getSupplierInfo(vendorId));
    }

    /** Lịch sử đặt hàng của NCC — search theo tên sản phẩm */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/{vendorId}/orders")
    public ApiResponse<List<OrderHistoryDto>> orders(
            @PathVariable Long vendorId,
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(service.getOrderHistory(vendorId, search));
    }

    /** Phân tích giá 1 sản phẩm (modal) */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/{vendorId}/product-price-stats")
    public ApiResponse<ProductPriceStatsDto> productPriceStats(
            @PathVariable Long vendorId,
            @RequestParam String name) {
        return ApiResponse.ok(service.getProductPriceStats(vendorId, name));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXPORT / IMPORT NHÀ CUNG CẤP (Excel) — chỉ Owner/Admin
    //
    // File export gồm: ID (khóa), Tên NCC, Loại NCC (dropdown từ danh sách có sẵn).
    // Có sheet ẩn __meta chứa token 1-lần: chỉ file vừa export mới import được, và
    // mỗi file chỉ import 1 lần (đã import thì không import lại được).
    // ═══════════════════════════════════════════════════════════════════════

    @PreAuthorize(ROLES_MANAGE)
    @GetMapping("/export")
    public ResponseEntity<?> exportSuppliers(@RequestParam(required = false) String search) {
        try {
            List<MaterialVendor> vendors = (search == null || search.isBlank())
                    ? vendorRepository.findByActiveTrueOrderByNameAsc()
                    : vendorRepository.findByNameContainingIgnoreCaseAndActiveTrue(search.trim());
            byte[] bytes = _buildVendorExcel(vendors);
            String now = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy"));
            String filename = "danh-sach-nha-cung-cap-" + now + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[SUPPLIER] exportSuppliers error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PreAuthorize(ROLES_MANAGE)
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> importSuppliers(@RequestParam("file") MultipartFile file) {
        try {
            int updated = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            // Map nhãn (đã lowercase, bỏ khoảng trắng thừa) → enum. Chấp nhận cả tên enum thô.
            Map<String, MaterialVendor.VendorType> labelToType = new HashMap<>();
            for (Map.Entry<MaterialVendor.VendorType, String> e : TYPE_LABELS.entrySet()) {
                labelToType.put(e.getValue().trim().toLowerCase(), e.getKey());
                labelToType.put(e.getKey().name().toLowerCase(), e.getKey());
            }

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
                // Xác thực token từ sheet __meta
                try {
                    int metaIdx = wb.getSheetIndex("__meta");
                    if (metaIdx < 0)
                        throw new IllegalStateException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
                    Row metaRow = wb.getSheetAt(metaIdx).getRow(0);
                    String token = metaRow != null ? _cellStr(metaRow, 0) : null;
                    _validateAndConsumeToken(token);
                } catch (IllegalStateException ex) {
                    return ApiResponse.error(ex.getMessage());
                }

                Sheet sheet = wb.getSheetAt(0);
                // Layout: row0 title, row1 info, row2 note, row3 header → data từ row 4
                // Cột: 0=ID  1=Tên NCC  2=Loại NCC  3=Trạng thái (Hoạt động/Xóa)
                for (int r = 4; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String idStr = _cellStr(row, 0);
                    if (idStr == null || idStr.isBlank()) continue;

                    try {
                        Long vendorId = Long.parseLong(idStr.trim());
                        MaterialVendor v = vendorRepository.findById(vendorId)
                                .orElseThrow(() -> new IllegalArgumentException("Nhà cung cấp ID=" + vendorId + " không tồn tại"));

                        String nameStr   = _cellStr(row, 1);
                        String typeStr   = _cellStr(row, 2);
                        String statusStr = _cellStr(row, 3);

                        // Trạng thái = "Xóa" → xóa mềm NCC (đặt active=false + thêm tiền tố
                        // "[Đã xóa <timestamp>] " vào tên để giải phóng tên gốc). Dùng lại
                        // logic deleteVendor để đồng nhất với nút Xóa trên giao diện.
                        boolean markDelete = statusStr != null
                                && statusStr.trim().equalsIgnoreCase(STATUS_DELETED);
                        if (markDelete) {
                            service.deleteVendor(vendorId);   // idempotent: đã xóa thì bỏ qua
                            updated++;
                            continue;                          // bỏ qua cập nhật tên/loại cho dòng xóa
                        }

                        // Tên: chỉ cập nhật nếu có giá trị
                        if (nameStr != null && !nameStr.isBlank()) {
                            v.setName(nameStr.trim());
                        }
                        // Loại NCC: map nhãn → enum. Nếu không khớp thì báo lỗi (không cho gõ tự do).
                        if (typeStr != null && !typeStr.isBlank()) {
                            MaterialVendor.VendorType t = labelToType.get(typeStr.trim().toLowerCase());
                            if (t == null)
                                throw new IllegalArgumentException("Loại NCC \"" + typeStr + "\" không hợp lệ");
                            v.setVendorType(t);
                        }

                        vendorRepository.save(v);
                        updated++;
                    } catch (Exception ex) {
                        errors.add("Dòng " + (r - 3) + ": " + ex.getMessage());
                        skipped++;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("updated", updated);
            result.put("skipped", skipped);
            result.put("errors", errors);
            return ApiResponse.ok("Import hoàn tất: " + updated + " thành công, " + skipped + " bỏ qua", result);
        } catch (Exception e) {
            log.error("[SUPPLIER] importSuppliers error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    private byte[] _buildVendorExcel(List<MaterialVendor> vendors) throws Exception {
        String exportToken = _generateExportToken();

        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet ws = wb.createSheet("Nhà cung cấp");
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

            String[] headers = {"ID", "Tên nhà cung cấp", "Loại nhà cung cấp", "Trạng thái"};
            int[] widths = {10, 40, 26, 16};
            for (int i = 0; i < widths.length; i++) ws.setColumnWidth(i, widths[i] * 256);

            // Sheet ẩn __data: danh sách nhãn Loại NCC cho dropdown
            XSSFSheet dataSheet = wb.createSheet("__data");
            wb.setSheetHidden(wb.getSheetIndex("__data"), true);
            List<String> typeLabels = new ArrayList<>(TYPE_LABELS.values());
            for (int i = 0; i < typeLabels.size(); i++)
                dataSheet.createRow(i).createCell(0).setCellValue(typeLabels.get(i));
            // Cột B của __data: danh sách nhãn Trạng thái (Hoạt động / Xóa) cho dropdown
            List<String> statusLabels = List.of(STATUS_ACTIVE, STATUS_DELETED);
            for (int i = 0; i < statusLabels.size(); i++) {
                Row dr = dataSheet.getRow(i);
                if (dr == null) dr = dataSheet.createRow(i);
                dr.createCell(1).setCellValue(statusLabels.get(i));
            }

            // Sheet ẩn __meta: token
            XSSFSheet meta = wb.createSheet("__meta");
            wb.setSheetHidden(wb.getSheetIndex("__meta"), true);
            meta.createRow(0).createCell(0).setCellValue(exportToken);

            // Row 0: Title
            Row r0 = ws.createRow(0); r0.setHeightInPoints(28);
            ws.addMergedRegion(new CellRangeAddress(0,0,0,headers.length-1));
            Cell tc = r0.createCell(0); tc.setCellValue("DANH SÁCH NHÀ CUNG CẤP"); tc.setCellStyle(titleStyle);

            // Row 1: Info
            Row r1 = ws.createRow(1); r1.setHeightInPoints(18);
            ws.addMergedRegion(new CellRangeAddress(1,1,0,headers.length-1));
            Cell ic = r1.createCell(0);
            ic.setCellValue("Xuất lúc: " + java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                    + "   |   Tổng: " + vendors.size() + " NCC   |   File chỉ import được 1 lần — Export lại nếu cần");
            ic.setCellStyle(infoStyle);

            // Row 2: Note
            Row r2 = ws.createRow(2); r2.setHeightInPoints(16);
            ws.addMergedRegion(new CellRangeAddress(2,2,0,headers.length-1));
            Cell nc = r2.createCell(0);
            nc.setCellValue("⚠ Không xóa cột ID. Loại NCC & Trạng thái chọn từ dropdown (không gõ tự do). Đổi Trạng thái sang \"Xóa\" để xóa nhà cung cấp.");
            nc.setCellStyle(noteStyle);

            // Row 3: Header
            Row hdrRow = ws.createRow(3); hdrRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hdrRow.createCell(i); c.setCellValue(headers[i]); c.setCellStyle(hdrStyle);
            }
            ws.createFreezePane(0, 4);

            // Dropdown cho cột 2 (Loại NCC) — tham chiếu __data
            int DATA_START = 4, DATA_ROWS_MAX = Math.max(vendors.size() + 20, 200);
            DataValidationHelper dvh = ws.getDataValidationHelper();
            DataValidationConstraint dvc = dvh.createFormulaListConstraint(
                    "__data!$A$1:$A$" + typeLabels.size());
            CellRangeAddressList addr = new CellRangeAddressList(DATA_START, DATA_START + DATA_ROWS_MAX, 2, 2);
            DataValidation dv = dvh.createValidation(dvc, addr);
            dv.setSuppressDropDownArrow(true); dv.setShowErrorBox(true); ws.addValidationData(dv);

            // Dropdown cho cột 3 (Trạng thái) — tham chiếu __data cột B (Hoạt động/Xóa)
            DataValidationConstraint dvcStatus = dvh.createFormulaListConstraint("__data!$B$1:$B$2");
            CellRangeAddressList addrStatus = new CellRangeAddressList(DATA_START, DATA_START + DATA_ROWS_MAX, 3, 3);
            DataValidation dvStatus = dvh.createValidation(dvcStatus, addrStatus);
            dvStatus.setSuppressDropDownArrow(true); dvStatus.setShowErrorBox(true); ws.addValidationData(dvStatus);

            // Data rows
            for (int i = 0; i < vendors.size(); i++) {
                MaterialVendor v = vendors.get(i);
                Row row = ws.createRow(DATA_START + i); row.setHeightInPoints(18);
                boolean gray = i % 2 == 0;
                XSSFCellStyle ds = gray ? dataGStyle : dataStyle;

                MaterialVendor.VendorType vt = v.getVendorType() != null ? v.getVendorType() : MaterialVendor.VendorType.MATERIAL;
                String typeLabel = TYPE_LABELS.getOrDefault(vt, "Khác");

                Cell c0 = row.createCell(0); c0.setCellValue(v.getId()); c0.setCellStyle(idStyle);
                Cell c1 = row.createCell(1); c1.setCellValue(v.getName() != null ? v.getName() : ""); c1.setCellStyle(ds);
                Cell c2 = row.createCell(2); c2.setCellValue(typeLabel); c2.setCellStyle(ds);
                // Export chỉ gồm NCC đang hoạt động → mặc định "Hoạt động".
                // Đổi sang "Xóa" khi import để xóa mềm NCC.
                Cell c3 = row.createCell(3); c3.setCellValue(STATUS_ACTIVE); c3.setCellStyle(ds);
            }

            ws.setAutoFilter(new CellRangeAddress(3, DATA_START + Math.max(vendors.size(), 0), 0, headers.length - 1));
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DANH MỤC KHOẢN CHI — POOL DÙNG CHUNG cho MỌI NCC
    //
    // Owner tạo nhãn MỘT LẦN, tất cả nhà cung cấp đều chọn được.
    // (Trước: mỗi NCC một danh mục riêng → 10 nhãn × 200 NCC = 2.000 thao tác tạo.)
    //
    // Các endpoint cũ có {vendorId} vẫn giữ để không vỡ client cũ, nhưng vendorId
    // BỊ BỎ QUA — chúng chỉ là alias của endpoint pool chung bên dưới.
    // ═══════════════════════════════════════════════════════════════════════

    /** Danh mục khoản chi dùng chung. activeOnly=true → chỉ nhãn đang bật (cho dropdown phiếu chi) */
    @PreAuthorize(ROLES_READ)
    @GetMapping("/expense-categories")
    public ApiResponse<List<VendorExpenseCategoryDto>> listCategories(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return ApiResponse.ok(service.listCategories(activeOnly));
    }

    @PreAuthorize(ROLES_MANAGE)
    @PostMapping("/expense-categories")
    public ApiResponse<VendorExpenseCategoryDto> createCategory(
            @RequestBody CategoryUpsertRequest req,
            org.springframework.security.core.Authentication auth) {
        return ApiResponse.ok(service.createCategory(req, auth != null ? auth.getName() : null));
    }

    @PreAuthorize(ROLES_MANAGE)
    @PutMapping("/expense-categories/{categoryId}")
    public ApiResponse<VendorExpenseCategoryDto> updateCategory(
            @PathVariable Long categoryId,
            @RequestBody CategoryUpsertRequest req) {
        return ApiResponse.ok(service.updateCategory(categoryId, req));
    }

    /** Ẩn nhãn (không xoá cứng — phiếu chi cũ giữ nguyên tham chiếu) */
    @PreAuthorize(ROLES_MANAGE)
    @DeleteMapping("/expense-categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long categoryId) {
        service.deleteCategory(categoryId);
        return ApiResponse.ok(null);
    }

    // ── ALIAS TƯƠNG THÍCH NGƯỢC (vendorId bị bỏ qua) ───────────────────────

    @Deprecated
    @PreAuthorize(ROLES_READ)
    @GetMapping("/{vendorId}/expense-categories")
    public ApiResponse<List<VendorExpenseCategoryDto>> listCategoriesLegacy(
            @PathVariable Long vendorId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        return ApiResponse.ok(service.listCategories(activeOnly));
    }

    @Deprecated
    @PreAuthorize(ROLES_MANAGE)
    @PostMapping("/{vendorId}/expense-categories")
    public ApiResponse<VendorExpenseCategoryDto> createCategoryLegacy(
            @PathVariable Long vendorId,
            @RequestBody CategoryUpsertRequest req,
            org.springframework.security.core.Authentication auth) {
        return ApiResponse.ok(service.createCategory(req, auth != null ? auth.getName() : null));
    }

    @Deprecated
    @PreAuthorize(ROLES_MANAGE)
    @PutMapping("/{vendorId}/expense-categories/{categoryId}")
    public ApiResponse<VendorExpenseCategoryDto> updateCategoryLegacy(
            @PathVariable Long vendorId,
            @PathVariable Long categoryId,
            @RequestBody CategoryUpsertRequest req) {
        return ApiResponse.ok(service.updateCategory(categoryId, req));
    }

    @Deprecated
    @PreAuthorize(ROLES_MANAGE)
    @DeleteMapping("/{vendorId}/expense-categories/{categoryId}")
    public ApiResponse<Void> deleteCategoryLegacy(
            @PathVariable Long vendorId,
            @PathVariable Long categoryId) {
        service.deleteCategory(categoryId);
        return ApiResponse.ok(null);
    }
}
