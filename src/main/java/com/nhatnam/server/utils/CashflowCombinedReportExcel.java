package com.nhatnam.server.utils;

import com.nhatnam.server.dto.cashflow.CashflowFlowDto;
import com.nhatnam.server.dto.cashflow.CashflowSummaryDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Excel GỘP Phiếu thu + Phiếu chi — thay cho 2 báo cáo rời. Mỗi dòng là 1 phiếu,
 * có cột "Loại" (Phiếu thu / Phiếu chi) và sắp xếp theo thời gian tạo tăng dần.
 *
 * <p>Cấu trúc:
 * <pre>
 *   Dòng đầu kỳ      : "Số dư đầu kỳ"      | 0 (hoặc opening nếu truyền vào)
 *   Các dòng phát sinh: theo thứ tự thời gian tạo
 *   Dòng cuối kỳ     : "Số dư cuối kỳ"     | tổng thu − tổng chi (+ opening nếu có)
 * </pre>
 *
 * <p>Cột "Số phiếu" (trước đây "Số PC") rộng 20% + thêm tiền tố loại:
 * {@code "Phiếu Thu - 00001"} hoặc {@code "Phiếu chi - 00001"}.
 */
public final class CashflowCombinedReportExcel {

    private CashflowCombinedReportExcel() {}

    private static final ZoneId TZ = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final DateTimeFormatter D  = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final byte[] WHITE  = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    private static final byte[] ALT_BG = {(byte) 0xFA, (byte) 0xF7, (byte) 0xF2};
    private static final byte[] NAVY   = {(byte) 0x1A, (byte) 0x1A, (byte) 0x2E};
    private static final byte[] GOLD   = {(byte) 0xC9, (byte) 0xA8, (byte) 0x4C};
    private static final byte[] GREEN_BG = {(byte) 0xEC, (byte) 0xFD, (byte) 0xF5};
    private static final byte[] RED_BG   = {(byte) 0xFE, (byte) 0xF2, (byte) 0xF2};

    /**
     * {@code paymentType}: null / "ALL" / "" → cả 2; "CASH" chỉ tiền mặt;
     * "BANK_TRANSFER" chỉ chuyển khoản. Lọc áp dụng CHO các dòng thu/chi, không
     * ảnh hưởng tiêu đề và dòng đầu/cuối kỳ.
     */
    public static byte[] build(CashflowSummaryDto s, String exportedBy, String paymentType) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Báo cáo dòng tiền");
            DataFormat df = wb.createDataFormat();

            // ── Cột (5 cột — cột Loại đã bỏ vì tiền tố "Phiếu Thu/Phiếu chi"
            //    đã kèm trong cột Số phiếu). Cột Số phiếu ~20% bề rộng. ──
            final int COL_TIME    = 0; // Thời gian tạo
            final int COL_NUMBER  = 1; // Số phiếu (20% width, có tiền tố loại)
            final int COL_REASON  = 2; // Lý do
            final int COL_METHOD  = 3; // Hình thức
            final int COL_AMOUNT  = 4; // Số tiền (+ thu / − chi)
            final int LAST_COL    = COL_AMOUNT;

            // ── Styles ────────────────────────────────────────────────────────
            CellStyle titleStyle = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle metaStyle = wb.createCellStyle();
            XSSFFont metaFont = wb.createFont();
            metaFont.setItalic(true); metaFont.setFontHeightInPoints((short) 10);
            metaStyle.setFont(metaFont);
            metaStyle.setAlignment(HorizontalAlignment.CENTER);
            metaStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle header = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true); headerFont.setFontHeightInPoints((short) 10);
            headerFont.setColor(new XSSFColor(WHITE, null));
            header.setFont(headerFont);
            header.setFillForegroundColor(new XSSFColor(NAVY, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setBorderBottom(BorderStyle.THIN);
            header.setBorderLeft(BorderStyle.THIN);
            header.setBorderRight(BorderStyle.THIN);
            header.setWrapText(true);

            CellStyle textN  = text(wb, null);
            CellStyle textA  = text(wb, ALT_BG);
            CellStyle amtN   = amount(wb, df, null);
            CellStyle amtA   = amount(wb, df, ALT_BG);
            CellStyle amtIn  = amount(wb, df, GREEN_BG);
            CellStyle amtEx  = amount(wb, df, RED_BG);

            // Style dòng đầu/cuối kỳ: tô vàng đậm
            CellStyle balanceLabel = wb.createCellStyle();
            balanceLabel.cloneStyleFrom(header);
            balanceLabel.setFillForegroundColor(new XSSFColor(GOLD, null));
            balanceLabel.setAlignment(HorizontalAlignment.RIGHT);
            // Ô giá trị đầu/cuối kỳ: căn phải, in đậm (clone từ balanceLabel đã
            // dùng headerFont bold rồi), nền vàng đồng bộ với dòng đầu/cuối kỳ.
            CellStyle balanceValue = wb.createCellStyle();
            balanceValue.cloneStyleFrom(balanceLabel);
            balanceValue.setAlignment(HorizontalAlignment.RIGHT);
            balanceValue.setDataFormat(df.getFormat("#,##0"));

            int rowIdx = 0;

            // ── Tiêu đề ────────────────────────────────────────────────────────
            Row titleRow = sheet.createRow(rowIdx++);
            titleRow.setHeightInPoints(26);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue("BÁO CÁO DÒNG TIỀN");
            tc.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, LAST_COL));

            // ── Meta ──────────────────────────────────────────────────────────
            String fromStr = Instant.ofEpochMilli(s.getFrom()).atZone(TZ).format(D);
            String toStr   = Instant.ofEpochMilli(s.getTo()).atZone(TZ).format(D);
            String exportedAt = java.time.LocalDateTime.now(TZ).format(DT);
            Row metaRow = sheet.createRow(rowIdx++);
            metaRow.setHeightInPoints(18);
            Cell mc = metaRow.createCell(0);
            mc.setCellValue("Từ " + fromStr + " đến " + toStr
                    + "  |  Xuất: " + exportedAt
                    + "  |  Người xuất: " + (exportedBy != null ? exportedBy : ""));
            mc.setCellStyle(metaStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, LAST_COL));

            // Dòng trống
            sheet.createRow(rowIdx++);

            // ── Header ────────────────────────────────────────────────────────
            Row hRow = sheet.createRow(rowIdx++);
            hRow.setHeightInPoints(22);
            String[] labels = new String[LAST_COL + 1];
            labels[COL_TIME]   = "Thời gian tạo";
            labels[COL_NUMBER] = "Số phiếu";
            labels[COL_REASON] = "Lý do";
            labels[COL_METHOD] = "Hình thức";
            labels[COL_AMOUNT] = "Số tiền";
            for (int i = 0; i <= LAST_COL; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(labels[i]);
                c.setCellStyle(header);
            }

            // ── Gộp 2 danh sách rồi sort theo at tăng dần ─────────────────────
            List<CashflowFlowDto> all = new ArrayList<>();
            if (s.getIncomes()  != null) all.addAll(s.getIncomes());
            if (s.getExpenses() != null) all.addAll(s.getExpenses());

            // Lọc theo hình thức thanh toán (nếu không "ALL")
            String pt = paymentType == null ? "" : paymentType.trim().toUpperCase();
            final boolean filterAll = pt.isEmpty() || "ALL".equals(pt) || "BOTH".equals(pt);
            if (!filterAll) {
                final String keep = pt;
                all.removeIf(f -> {
                    String p = f.getPaymentType() == null ? "CASH" : f.getPaymentType();
                    return !p.equalsIgnoreCase(keep);
                });
            }

            all.sort(Comparator.comparingLong(f -> f.getAt() == null ? 0L : f.getAt()));

            // ── Dòng đầu kỳ ───────────────────────────────────────────────────
            BigDecimal opening = openingTotal(s);
            Row openRow = sheet.createRow(rowIdx++);
            openRow.setHeightInPoints(20);
            Cell lblOpen = openRow.createCell(0);
            lblOpen.setCellValue("SỐ DƯ ĐẦU KỲ");
            lblOpen.setCellStyle(balanceLabel);
            sheet.addMergedRegion(new CellRangeAddress(openRow.getRowNum(),
                    openRow.getRowNum(), 0, COL_AMOUNT - 1));
            Cell openVal = openRow.createCell(COL_AMOUNT);
            openVal.setCellValue(opening.doubleValue());
            openVal.setCellStyle(balanceValue);

            // ── Data ──────────────────────────────────────────────────────────
            BigDecimal totalIn  = BigDecimal.ZERO;
            BigDecimal totalOut = BigDecimal.ZERO;

            // Số ký tự ước lượng mỗi cột có thể chứa (dùng để wrap xuống dòng)
            final int CHARS_TIME   = 14;
            final int CHARS_NUMBER = 22;
            final int CHARS_REASON = 30;
            final int CHARS_METHOD = 12;

            for (int i = 0; i < all.size(); i++) {
                CashflowFlowDto f = all.get(i);
                boolean income = "INCOME".equalsIgnoreCase(f.getKind());
                boolean alt = (i % 2 == 1);
                CellStyle sText = alt ? textA : textN;

                Row row = sheet.createRow(rowIdx++);

                // Thời gian
                Cell c0 = row.createCell(COL_TIME);
                c0.setCellValue(f.getAt() != null
                        ? Instant.ofEpochMilli(f.getAt()).atZone(TZ).format(DT) : "");
                c0.setCellStyle(sText);

                // Số phiếu (có tiền tố loại) — ví dụ: "Phiếu Thu - 00001"
                String raw = f.getNumber() != null ? f.getNumber() : f.getVoucherCode();
                if (raw == null) raw = "";
                String prefixed = (income ? "Phiếu Thu" : "Phiếu Chi") + " - " + raw;
                Cell c2 = row.createCell(COL_NUMBER);
                c2.setCellValue(prefixed);
                c2.setCellStyle(sText);

                // Lý do
                Cell c3 = row.createCell(COL_REASON);
                c3.setCellValue(nz(f.getReason()));
                c3.setCellStyle(sText);

                // Hình thức
                Cell c4 = row.createCell(COL_METHOD);
                String method = "BANK_TRANSFER".equalsIgnoreCase(f.getPaymentType())
                        ? ("CK" + (f.getBankName() != null ? " · " + f.getBankName() : ""))
                        : "Tiền mặt";
                c4.setCellValue(method);
                c4.setCellStyle(sText);

                // Số tiền (+/-)
                BigDecimal amt = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();
                BigDecimal signed = income ? amt : amt.negate();
                Cell c5 = row.createCell(COL_AMOUNT);
                c5.setCellValue(signed.doubleValue());
                c5.setCellStyle(income ? amtIn : amtEx);

                if (income) totalIn  = totalIn.add(amt);
                else        totalOut = totalOut.add(amt);

                // ── Auto-fit chiều cao dòng dựa trên nội dung dài nhất ──
                // Excel không tự tính height khi có wrapText + merged cells, nên
                // ta tự ước lượng số dòng ngắt từ độ dài chuỗi so với bề rộng cột
                // (chars/dòng). Mỗi dòng ≈ 14 points với font 10pt.
                int lines = 1;
                lines = Math.max(lines, countLines(c0.getStringCellValue(), CHARS_TIME));
                lines = Math.max(lines, countLines(prefixed, CHARS_NUMBER));
                lines = Math.max(lines, countLines(nz(f.getReason()), CHARS_REASON));
                lines = Math.max(lines, countLines(method, CHARS_METHOD));
                row.setHeightInPoints(Math.max(18f, Math.min(lines * 14f + 4f, 160f)));
            }

            // ── Dòng cuối kỳ ──────────────────────────────────────────────────
            BigDecimal closing = opening.add(totalIn).subtract(totalOut);
            Row closeRow = sheet.createRow(rowIdx++);
            closeRow.setHeightInPoints(20);
            Cell lblClose = closeRow.createCell(0);
            lblClose.setCellValue("SỐ DƯ CUỐI KỲ (= đầu kỳ + tổng thu − tổng chi)");
            lblClose.setCellStyle(balanceLabel);
            sheet.addMergedRegion(new CellRangeAddress(closeRow.getRowNum(),
                    closeRow.getRowNum(), 0, COL_AMOUNT - 1));
            Cell closeVal = closeRow.createCell(COL_AMOUNT);
            closeVal.setCellValue(closing.doubleValue());
            closeVal.setCellStyle(balanceValue);

            // ── Column widths ──────────────────────────────────────────────────
            // 5 cột (đã bỏ cột Loại). Tổng ~96 ký tự. Số phiếu rộng hơn (22) để
            // tiền tố "Phiếu Thu - 00001" đọc trọn trong 1 dòng.
            sheet.setColumnWidth(COL_TIME,   16 * 256);  // Thời gian tạo
            sheet.setColumnWidth(COL_NUMBER, 22 * 256);  // Số phiếu (có tiền tố)
            sheet.setColumnWidth(COL_REASON, 36 * 256);  // Lý do
            sheet.setColumnWidth(COL_METHOD, 14 * 256);  // Hình thức
            sheet.setColumnWidth(COL_AMOUNT, 16 * 256);  // Số tiền
            sheet.createFreezePane(0, 4);
            sheet.setDisplayGridlines(false);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Tổng số dư đầu kỳ = tiền mặt + tổng ngân hàng. Null-safe. Nếu BE không trả về
     * opening (dự liệu lỗi), rơi về 0 — đúng với yêu cầu "đầu kỳ thường là 0".
     */
    private static BigDecimal openingTotal(CashflowSummaryDto s) {
        if (s.getOpening() == null) return BigDecimal.ZERO;
        BigDecimal cash = s.getOpening().getCash() == null ? BigDecimal.ZERO : s.getOpening().getCash();
        BigDecimal bank = s.getOpening().getBankTotal() == null ? BigDecimal.ZERO : s.getOpening().getBankTotal();
        return cash.add(bank);
    }

    private static CellStyle text(XSSFWorkbook wb, byte[] bg) {
        CellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 10);
        s.setFont(f);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setIndention((short) 1);
        s.setWrapText(true);
        if (bg != null) {
            s.setFillForegroundColor(new XSSFColor(bg, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private static CellStyle amount(XSSFWorkbook wb, DataFormat df, byte[] bg) {
        CellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 10);
        f.setBold(true);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.RIGHT);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setDataFormat(df.getFormat("#,##0"));
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        if (bg != null) {
            s.setFillForegroundColor(new XSSFColor(bg, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private static String nz(String s) { return s == null ? "" : s; }
    @SuppressWarnings("unused")
    private static boolean isTrue(Boolean b) { return Objects.equals(b, Boolean.TRUE); }

    /**
     * Ước lượng số dòng wrap của 1 chuỗi khi cell rộng {@code charsPerLine} ký tự.
     * Chia theo khoảng trắng (không ngắt giữa từ) + đếm cả '\n' do người dùng nhập.
     * Dùng để tự tính row height khi POI không auto-fit được dòng merged + wrapText.
     */
    private static int countLines(String text, int charsPerLine) {
        if (text == null || text.isEmpty()) return 1;
        if (charsPerLine < 5) charsPerLine = 5;
        int total = 0;
        for (String para : text.split("\n", -1)) {
            if (para.isEmpty()) { total += 1; continue; }
            int lines = 1, cur = 0;
            for (String w : para.split(" ")) {
                int wl = w.length();
                if (cur == 0) { cur = wl; }
                else if (cur + 1 + wl > charsPerLine) { lines++; cur = wl; }
                else { cur += 1 + wl; }
                // Từ cực dài → chiếm nhiều dòng
                while (cur > charsPerLine) { lines++; cur -= charsPerLine; }
            }
            total += lines;
        }
        return Math.max(1, total);
    }
}