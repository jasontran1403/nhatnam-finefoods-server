package com.nhatnam.server.utils;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dựng TEMPLATE và ĐỌC file Excel để nhập phiếu chi hàng loạt.
 *
 * <h3>Bố cục (sheet "Phiếu chi")</h3>
 * <pre>
 *  Cột: 0=Số phiếu chi 1=Tên NCC 2=Loại phiếu chi(Kỳ/Ngày) 3=Tháng(khi Kỳ)
 *       4=Ngày chi(khi Ngày) 5=Lý do 6=Tên khoản chi 7=Số tiền
 *  Dòng 0: tiêu đề · Dòng 1: hướng dẫn · Dòng 2: header · Dữ liệu từ dòng 4.
 * </pre>
 *
 * <p><b>Các dòng có CÙNG "Số phiếu chi" = MỘT phiếu chi nhiều khoản.</b> KHÔNG cần merge:
 * điền lại Số phiếu chi ở mỗi dòng khoản chi (hoặc để trống — hệ thống tự kéo xuống). Thông tin
 * đầu phiếu (NCC, Loại, Tháng/Ngày, Lý do) lấy từ giá trị KHÔNG rỗng đầu tiên trong nhóm.
 *
 * <p>Cột "Tháng" chỉ dùng khi Loại = "Kỳ"; cột "Ngày chi" chỉ dùng khi Loại = "Ngày" — hai cột
 * này được khoá (chặn gõ) và tô xám theo Loại. Tên NCC / Loại / Tên khoản chi là dropdown.
 * Dòng ví dụ có Số phiếu bắt đầu bằng {@code VD} sẽ bị BỎ QUA khi nhập.
 */
public final class ExpenseVoucherExcel {

    private ExpenseVoucherExcel() {}

    // ── Chỉ số cột ────────────────────────────────────────────────────────────
    public static final int COL_PAYMENT  = 0;   // Số phiếu chi
    public static final int COL_VENDOR   = 1;   // Tên nhà cung cấp
    public static final int COL_TYPE     = 2;   // Loại phiếu chi: Kỳ | Ngày   (cột C)
    public static final int COL_PERIOD   = 3;   // Tháng (khi Kỳ)              (cột D)
    public static final int COL_DATE     = 4;   // Ngày chi (khi Ngày)         (cột E)
    public static final int COL_REASON   = 5;   // Lý do
    public static final int COL_CATEGORY = 6;   // Tên khoản chi (item)
    public static final int COL_AMOUNT   = 7;   // Số tiền (item)
    public static final int COL_COUNT    = 8;

    public static final int HEADER_ROW = 2;
    public static final int DATA_START = 3;
    private static final int VALIDATION_LAST_ROW = 1000;

    /** Cột Loại ở dạng địa chỉ Excel (0→A,1→B,2→C...). Dùng cho công thức validation/định dạng. */
    private static final String TYPE_COL_LETTER = "C";
    /** Dòng neo (1-based) của công thức tương đối = DATA_START+1. */
    private static final int ANCHOR_ROW_1BASED = DATA_START + 1;

    public static final String TYPE_PERIOD = "Kỳ";
    public static final String TYPE_DATE   = "Ngày";

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter D_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final String[] HEADERS = {
            "Số phiếu chi *", "Tên nhà cung cấp *", "Loại phiếu chi *", "Tháng (nếu Kỳ — chọn từ danh sách)",
            "Ngày chi (nếu Ngày, dd/MM/yyyy)", "Lý do *", "Tên khoản chi *", "Số tiền *"
    };

    // ══════════════════════════════════════════════════════════════════════════
    //  DỰNG TEMPLATE
    // ══════════════════════════════════════════════════════════════════════════
    public static byte[] buildTemplate(List<String> vendorNames, List<String> categoryNames) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Phiếu chi");

            // ── Styles ───────────────────────────────────────────────────────
            XSSFCellStyle title = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 14);
            titleFont.setColor(IndexedColors.WHITE.getIndex());
            title.setFont(titleFont);
            title.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 0xC9, (byte) 0xA8, 0x4C}, null));
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            XSSFCellStyle guide = wb.createCellStyle();
            XSSFFont guideFont = wb.createFont();
            guideFont.setItalic(true); guideFont.setFontHeightInPoints((short) 10);
            guideFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            guide.setFont(guideFont);
            guide.setVerticalAlignment(VerticalAlignment.CENTER);
            guide.setWrapText(true);

            XSSFCellStyle header = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(headerFont);
            header.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 0x8E, (byte) 0x88, 0x78}, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            setAllBorders(header, BorderStyle.THIN);

            XSSFCellStyle example = wb.createCellStyle();
            XSSFFont exFont = wb.createFont();
            exFont.setItalic(true); exFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            example.setFont(exFont);
            example.setVerticalAlignment(VerticalAlignment.CENTER);
            setAllBorders(example, BorderStyle.HAIR);

            // ── Dòng 0: tiêu đề ──────────────────────────────────────────────
            Row r0 = sheet.createRow(0);
            r0.setHeightInPoints(28);
            Cell c0 = r0.createCell(0);
            c0.setCellValue("PHIẾU CHI — NHẬP HÀNG LOẠT TỪ EXCEL");
            c0.setCellStyle(title);
            for (int i = 1; i < COL_COUNT; i++) r0.createCell(i).setCellStyle(title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, COL_COUNT - 1));

            // ── Dòng 1: hướng dẫn ────────────────────────────────────────────
            Row r1 = sheet.createRow(1);
            r1.setHeightInPoints(58);
            Cell c1 = r1.createCell(0);
            c1.setCellValue("• Các dòng có CÙNG \"Số phiếu chi\" = MỘT phiếu chi (nhiều khoản chi). KHÔNG cần merge — "
                    + "chỉ cần điền lại \"Số phiếu chi\" cho từng dòng khoản chi thuộc cùng phiếu.  "
                    + "• \"Tên nhà cung cấp\", \"Loại phiếu chi\", \"Tên khoản chi\": bấm vào ô để chọn từ danh sách.  "
                    + "• Loại = \"Kỳ\" → CHỌN tháng ở cột \"Tháng\". Loại = \"Ngày\" → gõ cột \"Ngày chi\" dạng NGÀY/THÁNG/NĂM, vd 12/07/2026 = 12 tháng 7 (để trống = hôm nay). "
                    + "Cột không dùng sẽ bị khoá & tô xám theo Loại.  "
                    + "• XOÁ dòng ví dụ (chữ xám, Số phiếu bắt đầu \"VD\") trước khi nhập.");
            c1.setCellStyle(guide);
            for (int i = 1; i < COL_COUNT; i++) r1.createCell(i).setCellStyle(guide);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, COL_COUNT - 1));

            // ── Dòng 2: header ───────────────────────────────────────────────
            Row rh = sheet.createRow(HEADER_ROW);
            rh.setHeightInPoints(30);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = rh.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(header);
            }

            // ── Ví dụ: 1 phiếu (VD1) có 2 khoản chi — CÙNG số phiếu, KHÔNG merge ──
            String vd1 = safe(vendorNames, 0);
            String cat1 = safe(categoryNames, 0);
            String cat2 = safe(categoryNames, 1);
            String today = java.time.LocalDate.now(VN_ZONE).format(D_SLASH);

            Row e1 = sheet.createRow(DATA_START);
            Row e2 = sheet.createRow(DATA_START + 1);
            e1.setHeightInPoints(20); e2.setHeightInPoints(20);
            for (int i = 0; i < COL_COUNT; i++) { e1.createCell(i).setCellStyle(example); e2.createCell(i).setCellStyle(example); }

            // Ô Ngày chi / Tháng phải là TEXT để Excel không tự đổi 12/07 thành 07/12 (locale Mỹ)
            XSSFCellStyle exampleText = wb.createCellStyle();
            exampleText.cloneStyleFrom(example);
            exampleText.setDataFormat(wb.createDataFormat().getFormat("@"));
            e1.getCell(COL_DATE).setCellStyle(exampleText);
            e1.getCell(COL_PERIOD).setCellStyle(exampleText);
            e2.getCell(COL_DATE).setCellStyle(exampleText);
            e2.getCell(COL_PERIOD).setCellStyle(exampleText);

            // Dòng 1 của phiếu: đầy đủ thông tin + khoản chi 1
            e1.getCell(COL_PAYMENT).setCellValue("VD1");
            e1.getCell(COL_VENDOR).setCellValue(vd1);
            e1.getCell(COL_TYPE).setCellValue(TYPE_DATE);
            e1.getCell(COL_DATE).setCellValue(today);
            e1.getCell(COL_REASON).setCellValue("Chi phí vận hành (ví dụ)");
            e1.getCell(COL_CATEGORY).setCellValue(cat1);
            if (!cat1.isEmpty()) e1.getCell(COL_AMOUNT).setCellValue(500000);
            // Dòng 2 của phiếu: CHỈ cần lặp lại Số phiếu chi + khoản chi 2
            e2.getCell(COL_PAYMENT).setCellValue("VD1");
            e2.getCell(COL_CATEGORY).setCellValue(cat2);
            if (!cat2.isEmpty()) e2.getCell(COL_AMOUNT).setCellValue(300000);

            // ── Sheet ẩn chứa dữ liệu dropdown ───────────────────────────────
            XSSFSheet data = wb.createSheet("DATA");
            int vN = fillColumn(data, 0, "VENDORS", vendorNames);
            int cN = fillColumn(data, 1, "CATEGORIES", categoryNames);
            wb.setSheetHidden(wb.getSheetIndex(data), true);

            // ── Data validation (dropdown) ───────────────────────────────────
            DataValidationHelper dv = sheet.getDataValidationHelper();
            if (vN > 0) addListValidation(sheet, dv, "DATA!$A$2:$A$" + (vN + 1), COL_VENDOR);
            if (cN > 0) addListValidation(sheet, dv, "DATA!$B$2:$B$" + (cN + 1), COL_CATEGORY);
            addInlineValidation(sheet, dv, new String[]{TYPE_PERIOD, TYPE_DATE}, COL_TYPE);

            // Cột "Tháng": dropdown 12 tháng của năm hiện tại (Tháng 1/YYYY … Tháng 12/YYYY).
            // (Excel chỉ cho MỘT ràng buộc mỗi ô nên dùng dropdown thay cho khoá; cột vẫn tô xám khi Loại ≠ "Kỳ".)
            int mN = fillColumn(data, 2, "PERIODS", monthOptionsOfCurrentYear());
            if (mN > 0) addListValidation(sheet, dv, "DATA!$C$2:$C$" + (mN + 1), COL_PERIOD);

            //   Ngày chi nhập được khi Loại KHÁC "Kỳ" (tức "Ngày" hoặc chưa chọn)
            addCustomValidation(sheet, dv, TYPE_COL_LETTER + ANCHOR_ROW_1BASED + "<>\"" + TYPE_PERIOD + "\"",
                    COL_DATE, "Cột \"Ngày chi\" chỉ dùng khi Loại phiếu chi = \"Ngày\".");

            // ── Tô xám cột đang bị khoá theo Loại (conditional formatting) ────
            grayWhen(sheet, TYPE_COL_LETTER + ANCHOR_ROW_1BASED + "<>\"" + TYPE_PERIOD + "\"", COL_PERIOD); // Tháng xám khi ≠ Kỳ
            grayWhen(sheet, TYPE_COL_LETTER + ANCHOR_ROW_1BASED + "=\"" + TYPE_PERIOD + "\"", COL_DATE);   // Ngày xám khi = Kỳ

            // ── Ép cột Ngày chi & Tháng sang định dạng TEXT ──────────────────
            // QUAN TRỌNG: nếu để Excel tự nhận dạng, "12/07/2026" trên máy dùng locale Mỹ sẽ bị
            // hiểu thành 7 tháng 12. Định dạng Text giữ nguyên chuỗi người dùng gõ, backend luôn
            // đọc theo dd/MM/yyyy nên không bao giờ lẫn ngày ↔ tháng.
            CellStyle textCol = wb.createCellStyle();
            textCol.setDataFormat(wb.createDataFormat().getFormat("@"));
            sheet.setDefaultColumnStyle(COL_DATE, textCol);
            sheet.setDefaultColumnStyle(COL_PERIOD, textCol);

            // ── Độ rộng cột & freeze ─────────────────────────────────────────
            int[] widths = {14, 26, 14, 20, 24, 30, 26, 16};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            sheet.createFreezePane(0, HEADER_ROW + 1);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 12 tháng của năm hiện tại: "Tháng 1/2026" … "Tháng 12/2026" (dropdown cột Kỳ). */
    private static List<String> monthOptionsOfCurrentYear() {
        int year = java.time.LocalDate.now(VN_ZONE).getYear();
        List<String> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) months.add("Tháng " + m + "/" + year);
        return months;
    }

    /** Ghi tiêu đề + danh sách vào 1 cột của sheet DATA; trả về SỐ mục dữ liệu. */
    private static int fillColumn(XSSFSheet data, int col, String title, List<String> values) {
        Row head = data.getRow(0);
        if (head == null) head = data.createRow(0);
        head.createCell(col).setCellValue(title);
        int n = 0;
        if (values != null) {
            for (String v : values) {
                if (v == null || v.isBlank()) continue;
                Row row = data.getRow(n + 1);
                if (row == null) row = data.createRow(n + 1);
                row.createCell(col).setCellValue(v);
                n++;
            }
        }
        return n;
    }

    private static void addListValidation(XSSFSheet sheet, DataValidationHelper dv, String formula, int col) {
        DataValidationConstraint c = dv.createFormulaListConstraint(formula);
        CellRangeAddressList addr = new CellRangeAddressList(DATA_START, VALIDATION_LAST_ROW, col, col);
        DataValidation validation = dv.createValidation(c, addr);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Giá trị không hợp lệ", "Vui lòng chọn một giá trị từ danh sách.");
        sheet.addValidationData(validation);
    }

    private static void addInlineValidation(XSSFSheet sheet, DataValidationHelper dv, String[] values, int col) {
        DataValidationConstraint c = dv.createExplicitListConstraint(values);
        CellRangeAddressList addr = new CellRangeAddressList(DATA_START, VALIDATION_LAST_ROW, col, col);
        DataValidation validation = dv.createValidation(c, addr);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    /** Chặn gõ vào ô khi công thức (tương đối theo dòng) không thoả — dùng để "khoá" cột theo Loại. */
    private static void addCustomValidation(XSSFSheet sheet, DataValidationHelper dv,
                                            String formula, int col, String errMsg) {
        DataValidationConstraint c = dv.createCustomConstraint(formula);
        CellRangeAddressList addr = new CellRangeAddressList(DATA_START, VALIDATION_LAST_ROW, col, col);
        DataValidation validation = dv.createValidation(c, addr);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("Cột đang bị khoá", errMsg);
        sheet.addValidationData(validation);
    }

    /** Tô nền xám cho cột khi công thức đúng (cột không dùng theo Loại). */
    private static void grayWhen(XSSFSheet sheet, String formula, int col) {
        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule rule = scf.createConditionalFormattingRule(formula);
        PatternFormatting pf = rule.createPatternFormatting();
        pf.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        pf.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        CellRangeAddress[] regions = { new CellRangeAddress(DATA_START, VALIDATION_LAST_ROW, col, col) };
        scf.addConditionalFormatting(regions, rule);
    }

    private static void setAllBorders(XSSFCellStyle s, BorderStyle b) {
        s.setBorderTop(b); s.setBorderBottom(b); s.setBorderLeft(b); s.setBorderRight(b);
    }

    private static String safe(List<String> list, int i) {
        return (list != null && list.size() > i && list.get(i) != null) ? list.get(i) : "";
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ĐỌC FILE IMPORT → danh sách phiếu
    // ══════════════════════════════════════════════════════════════════════════

    public static final class RawItem {
        public final String categoryName;
        public final java.math.BigDecimal amount;
        RawItem(String categoryName, java.math.BigDecimal amount) {
            this.categoryName = categoryName; this.amount = amount;
        }
    }

    public static final class RawVoucher {
        public String paymentNumber;
        public int firstExcelRow;      // 1-based
        public String vendorName;
        public String voucherTypeRaw;  // "Kỳ" | "Ngày"
        public String periodRaw;       // cột Tháng (đã chuẩn hoá về yyyy-MM khi đọc được)
        public Long expenseDateMs;     // cột Ngày chi (epoch ms) — null nếu trống
        public String reason;
        public final List<RawItem> items = new ArrayList<>();
    }

    public static List<RawVoucher> parse(InputStream in) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheet("Phiếu chi");
            if (sheet == null) sheet = wb.getSheetAt(0);
            List<CellRangeAddress> merges = sheet.getMergedRegions();
            DataFormatter fmt = new DataFormatter();

            Map<String, RawVoucher> byKey = new LinkedHashMap<>();
            String lastPayment = null;

            for (int r = DATA_START; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String payment  = cstr(effCell(sheet, merges, r, COL_PAYMENT), fmt);
                String vendor   = cstr(effCell(sheet, merges, r, COL_VENDOR), fmt);
                String type     = cstr(effCell(sheet, merges, r, COL_TYPE), fmt);
                String period   = periodStr(effCell(sheet, merges, r, COL_PERIOD), fmt);
                Long   dateMs   = dateMs(effCell(sheet, merges, r, COL_DATE), fmt);
                String reason   = cstr(effCell(sheet, merges, r, COL_REASON), fmt);
                String category = cstr(row.getCell(COL_CATEGORY), fmt);           // item-level
                java.math.BigDecimal amount = amount(row.getCell(COL_AMOUNT), fmt);

                boolean allBlank = isBlank(payment) && isBlank(vendor) && isBlank(reason)
                        && isBlank(category) && amount == null;
                if (allBlank) continue;

                // Dòng ví dụ (Số phiếu bắt đầu "VD") → bỏ, và không để "kéo xuống" dòng sau
                if (!isBlank(payment) && payment.trim().toUpperCase().startsWith("VD")) {
                    lastPayment = null;
                    continue;
                }

                if (!isBlank(payment)) lastPayment = payment.trim();
                String key = isBlank(payment) ? lastPayment : payment.trim();
                if (key == null) continue;                                   // chưa có phiếu → bỏ dòng lạc

                RawVoucher v = byKey.get(key);
                if (v == null) {
                    v = new RawVoucher();
                    v.paymentNumber = key;
                    v.firstExcelRow = r + 1;
                    byKey.put(key, v);
                }
                if (isBlank(v.vendorName)     && !isBlank(vendor)) v.vendorName = vendor.trim();
                if (isBlank(v.voucherTypeRaw) && !isBlank(type))   v.voucherTypeRaw = type.trim();
                if (isBlank(v.periodRaw)      && !isBlank(period)) v.periodRaw = period.trim();
                if (v.expenseDateMs == null   && dateMs != null)   v.expenseDateMs = dateMs;
                if (isBlank(v.reason)         && !isBlank(reason)) v.reason = reason.trim();

                if (!isBlank(category) || amount != null) {
                    v.items.add(new RawItem(isBlank(category) ? null : category.trim(), amount));
                }
            }
            return new ArrayList<>(byKey.values());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static Cell effCell(Sheet sheet, List<CellRangeAddress> merges, int r, int c) {
        for (CellRangeAddress m : merges) {
            if (m.isInRange(r, c)) {
                Row tr = sheet.getRow(m.getFirstRow());
                return tr == null ? null : tr.getCell(m.getFirstColumn());
            }
        }
        Row row = sheet.getRow(r);
        return row == null ? null : row.getCell(c);
    }

    private static String cstr(Cell c, DataFormatter fmt) {
        return c == null ? null : fmt.formatCellValue(c);
    }

    private static java.math.BigDecimal amount(Cell c, DataFormatter fmt) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC) {
                return java.math.BigDecimal.valueOf(c.getNumericCellValue());
            }
            if (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC) {
                return java.math.BigDecimal.valueOf(c.getNumericCellValue());
            }
            String s = fmt.formatCellValue(c);
            if (s == null) return null;
            String digits = s.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return null;
            return new java.math.BigDecimal(digits);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long dateMs(Cell c, DataFormatter fmt) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                return c.getDateCellValue().toInstant().atZone(VN_ZONE).toLocalDate()
                        .atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            }
            String s = fmt.formatCellValue(c);
            if (isBlank(s)) return null;
            s = s.trim();
            java.time.LocalDate d;
            if (s.contains("/")) d = java.time.LocalDate.parse(padSlash(s), D_SLASH);
            else if (s.contains("-") && s.length() >= 8) d = java.time.LocalDate.parse(s);
            else return null;
            return d.atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** Đọc cột "Tháng" → chuẩn hoá về "yyyy-MM" khi có thể (chấp nhận ô ngày, "yyyy-MM", "MM/yyyy"). */
    private static String periodStr(Cell c, DataFormatter fmt) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                java.time.LocalDate d = c.getDateCellValue().toInstant().atZone(VN_ZONE).toLocalDate();
                return YearMonth.from(d).format(YM);
            }
        } catch (Exception ignored) {}
        String s = fmt.formatCellValue(c);
        return isBlank(s) ? null : s.trim();
    }

    private static String padSlash(String s) {
        String[] p = s.split("/");
        if (p.length != 3) return s;
        String dd = p[0].length() == 1 ? "0" + p[0] : p[0];
        String mm = p[1].length() == 1 ? "0" + p[1] : p[1];
        return dd + "/" + mm + "/" + p[2];
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}