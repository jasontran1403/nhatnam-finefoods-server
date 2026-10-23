package com.nhatnam.server.utils;

import com.nhatnam.server.dto.cashflow.BankBalanceDto;
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

/**
 * Excel "SỔ QUỸ" — thay cho báo cáo gộp cũ. Dạng sổ quỹ ngân hàng:
 *
 * <pre>
 *   Dòng 1 (A:G merge, center) : SỔ QUỸ
 *   Dòng 2 (A:G merge, center) : TÀI KHOẢN: 111 - TIỀN MẶT | 112 - CHUYỂN KHOẢN
 *   Dòng 3 (A:G merge, center) : TỪ NGÀY dd/mm/yyyy ĐẾN NGÀY dd/mm/yyyy
 *   Dòng 4 (A:F label right, G=amount) : SỐ TỒN ĐẦU
 *
 *   Dòng 5-6: Header 2 tầng
 *     A (merge 5+6)     : Ngày tháng chứng từ
 *     B+C (merge dòng 5): Số hiệu Chứng từ    → dòng 6: B=Thu, C=Chi
 *     D (merge 5+6)     : Diễn giải
 *     E+F+G (merge dòng 5): Số tiền            → dòng 6: E=Thu, F=Chi, G=Tồn
 *
 *   Dòng 7+: Data
 *     - Phiếu thu   : B = "Phiếu Thu - 00001", E = amount, F = "", G = running
 *     - Phiếu chi   : C = "Phiếu chi - 00001", E = "", F = amount (DƯƠNG), G = running
 *
 *   Dòng cuối (A:F label right, G=amount) : SỐ TỒN CUỐI
 * </pre>
 *
 * <p>Chú ý: cột F (chi) KHÔNG hiển thị dấu trừ — luôn là số dương.
 */
public final class CashflowCombinedReportExcel {

    private CashflowCombinedReportExcel() {}

    private static final ZoneId TZ = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final byte[] WHITE  = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    private static final byte[] NAVY   = {(byte) 0x1A, (byte) 0x1A, (byte) 0x2E};
    private static final byte[] GOLD   = {(byte) 0xC9, (byte) 0xA8, (byte) 0x4C};
    private static final byte[] ALT_BG = {(byte) 0xFA, (byte) 0xF7, (byte) 0xF2};

    /**
     * {@code paymentType}: "CASH" → sổ quỹ tài khoản 111 - Tiền mặt;
     * "BANK_TRANSFER" → sổ ngân hàng tài khoản 112 - Ngân hàng. Mặc định (null/empty) là CASH.
     *
     * <p><b>Quy tắc phân sổ (giống nhau cho THU và CHI):</b>
     * <ul>
     *   <li>paymentType = BANK_TRANSFER <b>và</b> bank là <b>Vietinbank</b> → sổ ngân hàng.</li>
     *   <li>Mọi trường hợp còn lại (tiền mặt, hoặc CK từ ngân hàng khác) → sổ quỹ.</li>
     * </ul>
     */
    public static byte[] build(CashflowSummaryDto s, String exportedBy, String paymentType) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("So quy");
            DataFormat df = wb.createDataFormat();

            // ── Hằng cột (0..6 ↔ A..G) ──────────────────────────────────────
            final int COL_DATE   = 0; // A: Ngày tháng chứng từ
            final int COL_THU_NO = 1; // B: Số hiệu phiếu thu
            final int COL_CHI_NO = 2; // C: Số hiệu phiếu chi
            final int COL_DESC   = 3; // D: Diễn giải
            final int COL_THU    = 4; // E: Số tiền Thu
            final int COL_CHI    = 5; // F: Số tiền Chi (dương)
            final int COL_TON    = 6; // G: Tồn (running balance)
            final int LAST_COL   = COL_TON;

            // ── Payment type → tên tài khoản ────────────────────────────────
            String pt = paymentType == null ? "" : paymentType.trim().toUpperCase();
            boolean isBank = "BANK_TRANSFER".equals(pt) || "BANK".equals(pt);
            String sheetTitle   = isBank ? "SỔ NGÂN HÀNG" : "SỔ QUỸ";
            String accountLabel = isBank
                    ? "TÀI KHOẢN: 112 - NGÂN HÀNG"
                    : "TÀI KHOẢN: 111 - TIỀN MẶT";

            // ── Styles ──────────────────────────────────────────────────────
            CellStyle title = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true); titleFont.setFontHeightInPoints((short) 16);
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle subTitle = wb.createCellStyle();
            XSSFFont subFont = wb.createFont();
            subFont.setBold(true); subFont.setFontHeightInPoints((short) 11);
            subTitle.setFont(subFont);
            subTitle.setAlignment(HorizontalAlignment.CENTER);
            subTitle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle period = wb.createCellStyle();
            XSSFFont pf = wb.createFont();
            pf.setItalic(true); pf.setFontHeightInPoints((short) 10);
            period.setFont(pf);
            period.setAlignment(HorizontalAlignment.CENTER);
            period.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle balanceLabel = wb.createCellStyle();
            XSSFFont blf = wb.createFont();
            blf.setBold(true); blf.setFontHeightInPoints((short) 10);
            balanceLabel.setFont(blf);
            balanceLabel.setAlignment(HorizontalAlignment.RIGHT);
            balanceLabel.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle balanceAmt = wb.createCellStyle();
            balanceAmt.setFont(blf);
            balanceAmt.setAlignment(HorizontalAlignment.RIGHT);
            balanceAmt.setVerticalAlignment(VerticalAlignment.CENTER);
            balanceAmt.setDataFormat(df.getFormat("#,##0"));
            balanceAmt.setBorderTop(BorderStyle.THIN);
            balanceAmt.setBorderBottom(BorderStyle.THIN);
            balanceAmt.setBorderLeft(BorderStyle.THIN);
            balanceAmt.setBorderRight(BorderStyle.THIN);

            CellStyle header = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true); headerFont.setFontHeightInPoints((short) 10);
            headerFont.setColor(new XSSFColor(WHITE, null));
            header.setFont(headerFont);
            header.setFillForegroundColor(new XSSFColor(NAVY, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setBorderTop(BorderStyle.THIN);
            header.setBorderBottom(BorderStyle.THIN);
            header.setBorderLeft(BorderStyle.THIN);
            header.setBorderRight(BorderStyle.THIN);
            header.setWrapText(true);

            CellStyle textCenter = textStyle(wb, HorizontalAlignment.CENTER, null, true);
            CellStyle textCenterAlt = textStyle(wb, HorizontalAlignment.CENTER, ALT_BG, true);
            CellStyle textLeft = textStyle(wb, HorizontalAlignment.LEFT, null, true);
            CellStyle textLeftAlt = textStyle(wb, HorizontalAlignment.LEFT, ALT_BG, true);
            CellStyle amount = amountStyle(wb, df, null);
            CellStyle amountAlt = amountStyle(wb, df, ALT_BG);

            int rowIdx = 0;

            // ── Dòng 1: SỔ QUỸ ──────────────────────────────────────────────
            Row rTitle = sheet.createRow(rowIdx++);
            rTitle.setHeightInPoints(28);
            Cell c = rTitle.createCell(0);
            c.setCellValue(sheetTitle);
            c.setCellStyle(title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, LAST_COL));

            // ── Dòng 2: TÀI KHOẢN ──────────────────────────────────────────
            Row rAcc = sheet.createRow(rowIdx++);
            rAcc.setHeightInPoints(20);
            c = rAcc.createCell(0);
            c.setCellValue(accountLabel);
            c.setCellStyle(subTitle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, LAST_COL));

            // ── Dòng 3: Khoảng thời gian ────────────────────────────────────
            String fromStr = Instant.ofEpochMilli(s.getFrom()).atZone(TZ).format(D);
            String toStr   = Instant.ofEpochMilli(s.getTo()).atZone(TZ).format(D);
            Row rPeriod = sheet.createRow(rowIdx++);
            rPeriod.setHeightInPoints(18);
            c = rPeriod.createCell(0);
            c.setCellValue("TỪ NGÀY " + fromStr + " ĐẾN NGÀY " + toStr);
            c.setCellStyle(period);
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, LAST_COL));

            // ── Dòng 4: SỐ TỒN ĐẦU (nhãn A..F căn phải, tiền ở G) ──────────
            BigDecimal opening = openingTotal(s, isBank);
            Row rOpen = sheet.createRow(rowIdx++);
            rOpen.setHeightInPoints(20);
            c = rOpen.createCell(0);
            c.setCellValue("SỐ TỒN ĐẦU");
            c.setCellStyle(balanceLabel);
            sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, COL_TON - 1));
            Cell cOpen = rOpen.createCell(COL_TON);
            cOpen.setCellValue(opening.doubleValue());
            cOpen.setCellStyle(balanceAmt);

            // ── Dòng 5-6: Header 2 tầng ─────────────────────────────────────
            final int HEADER_TOP = rowIdx;    // row 5 (0-indexed 4)
            final int HEADER_BOT = rowIdx + 1;
            Row rHeadTop = sheet.createRow(rowIdx++);
            Row rHeadBot = sheet.createRow(rowIdx++);
            rHeadTop.setHeightInPoints(22);
            rHeadBot.setHeightInPoints(20);

            // A (merge top+bot)
            Cell hA = rHeadTop.createCell(COL_DATE);
            hA.setCellValue("Ngày tháng chứng từ");
            hA.setCellStyle(header);
            rHeadBot.createCell(COL_DATE).setCellStyle(header);
            sheet.addMergedRegion(new CellRangeAddress(HEADER_TOP, HEADER_BOT, COL_DATE, COL_DATE));

            // B+C: top merged = "Số hiệu Chứng từ"
            Cell hBC = rHeadTop.createCell(COL_THU_NO);
            hBC.setCellValue("Số hiệu Chứng từ");
            hBC.setCellStyle(header);
            rHeadTop.createCell(COL_CHI_NO).setCellStyle(header);
            sheet.addMergedRegion(new CellRangeAddress(HEADER_TOP, HEADER_TOP, COL_THU_NO, COL_CHI_NO));
            // Bottom: B = Thu, C = Chi
            Cell hThuNo = rHeadBot.createCell(COL_THU_NO);
            hThuNo.setCellValue("Thu"); hThuNo.setCellStyle(header);
            Cell hChiNo = rHeadBot.createCell(COL_CHI_NO);
            hChiNo.setCellValue("Chi"); hChiNo.setCellStyle(header);

            // D (merge top+bot)
            Cell hD = rHeadTop.createCell(COL_DESC);
            hD.setCellValue("Diễn giải");
            hD.setCellStyle(header);
            rHeadBot.createCell(COL_DESC).setCellStyle(header);
            sheet.addMergedRegion(new CellRangeAddress(HEADER_TOP, HEADER_BOT, COL_DESC, COL_DESC));

            // E+F+G: top merged = "Số tiền"
            Cell hEFG = rHeadTop.createCell(COL_THU);
            hEFG.setCellValue("Số tiền");
            hEFG.setCellStyle(header);
            rHeadTop.createCell(COL_CHI).setCellStyle(header);
            rHeadTop.createCell(COL_TON).setCellStyle(header);
            sheet.addMergedRegion(new CellRangeAddress(HEADER_TOP, HEADER_TOP, COL_THU, COL_TON));
            // Bottom: E=Thu, F=Chi, G=Tồn
            Cell hThu = rHeadBot.createCell(COL_THU);
            hThu.setCellValue("Thu"); hThu.setCellStyle(header);
            Cell hChi = rHeadBot.createCell(COL_CHI);
            hChi.setCellValue("Chi"); hChi.setCellStyle(header);
            Cell hTon = rHeadBot.createCell(COL_TON);
            hTon.setCellValue("Tồn"); hTon.setCellStyle(header);

            // ── Gộp + lọc flows ─────────────────────────────────────────────
            // Cả thu và chi dùng CHUNG quy tắc:
            //   · paymentType = BANK_TRANSFER + bank là Vietinbank → sổ ngân hàng.
            //   · Còn lại → sổ quỹ.
            List<CashflowFlowDto> all = new ArrayList<>();
            if (s.getIncomes() != null) {
                for (CashflowFlowDto f : s.getIncomes()) {
                    if (isBankTransferVietin(f) == isBank) all.add(f);
                }
            }
            if (s.getExpenses() != null) {
                for (CashflowFlowDto f : s.getExpenses()) {
                    if (isBankTransferVietin(f) == isBank) all.add(f);
                }
            }
            all.sort(Comparator.comparingLong(f -> f.getAt() == null ? 0L : f.getAt()));

            // ── Data rows ───────────────────────────────────────────────────
            BigDecimal running = opening;
            final int CHARS_DESC = 44;

            for (int i = 0; i < all.size(); i++) {
                CashflowFlowDto f = all.get(i);
                boolean income = "INCOME".equalsIgnoreCase(f.getKind());
                boolean alt = (i % 2 == 1);
                CellStyle txtC = alt ? textCenterAlt : textCenter;
                CellStyle txtL = alt ? textLeftAlt : textLeft;
                CellStyle amt  = alt ? amountAlt : amount;

                Row row = sheet.createRow(rowIdx++);

                // A: ngày
                Cell cDate = row.createCell(COL_DATE);
                cDate.setCellValue(f.getAt() != null
                        ? Instant.ofEpochMilli(f.getAt()).atZone(TZ).format(D) : "");
                cDate.setCellStyle(txtC);

                // Số hiệu — chỉ 1 trong B/C có giá trị
                String raw = f.getNumber() != null ? f.getNumber() : f.getVoucherCode();
                if (raw == null) raw = "";
                String prefixed = (income ? "Phiếu Thu - " : "Phiếu Chi - ") + raw;

                Cell cThuNo = row.createCell(COL_THU_NO);
                cThuNo.setCellValue(income ? prefixed : "");
                cThuNo.setCellStyle(txtC);

                Cell cChiNo = row.createCell(COL_CHI_NO);
                cChiNo.setCellValue(income ? "" : prefixed);
                cChiNo.setCellStyle(txtC);

                // D: diễn giải
                Cell cDesc = row.createCell(COL_DESC);
                cDesc.setCellValue(nz(f.getReason()));
                cDesc.setCellStyle(txtL);

                // E/F: số tiền
                BigDecimal money = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();
                Cell cThu = row.createCell(COL_THU);
                Cell cChi = row.createCell(COL_CHI);
                if (income) {
                    cThu.setCellValue(money.doubleValue()); cThu.setCellStyle(amt);
                    cChi.setBlank(); cChi.setCellStyle(amt);
                    running = running.add(money);
                } else {
                    cThu.setBlank(); cThu.setCellStyle(amt);
                    // CHI: hiển thị DƯƠNG, không có dấu trừ
                    cChi.setCellValue(money.doubleValue()); cChi.setCellStyle(amt);
                    running = running.subtract(money);
                }

                // G: Tồn
                Cell cTon = row.createCell(COL_TON);
                cTon.setCellValue(running.doubleValue());
                cTon.setCellStyle(amt);

                // Row height auto theo "Diễn giải"
                int lines = Math.max(1, countLines(nz(f.getReason()), CHARS_DESC));
                row.setHeightInPoints(Math.max(18f, Math.min(lines * 14f + 4f, 160f)));
            }

            // ── Dòng SỐ TỒN CUỐI ──────────────────────────────────────────
            Row rClose = sheet.createRow(rowIdx++);
            rClose.setHeightInPoints(22);
            c = rClose.createCell(0);
            c.setCellValue("SỐ TỒN CUỐI");
            c.setCellStyle(balanceLabel);
            sheet.addMergedRegion(new CellRangeAddress(rClose.getRowNum(),
                    rClose.getRowNum(), 0, COL_TON - 1));
            Cell cClose = rClose.createCell(COL_TON);
            cClose.setCellValue(running.doubleValue());
            cClose.setCellStyle(balanceAmt);

            // ── Width (A4: tổng ~110 ký tự) ─────────────────────────────────
            sheet.setColumnWidth(COL_DATE,   14 * 256);
            sheet.setColumnWidth(COL_THU_NO, 16 * 256);
            sheet.setColumnWidth(COL_CHI_NO, 16 * 256);
            sheet.setColumnWidth(COL_DESC,   44 * 256); // Diễn giải rộng, wrap text
            sheet.setColumnWidth(COL_THU,    14 * 256);
            sheet.setColumnWidth(COL_CHI,    14 * 256);
            sheet.setColumnWidth(COL_TON,    16 * 256);
            sheet.createFreezePane(0, 6);
            sheet.setDisplayGridlines(false);

            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Tồn đầu kỳ cho từng tài khoản. Với CASH → opening.cash; với BANK_TRANSFER →
     * opening.bankTotal. Null → 0.
     */
    private static BigDecimal openingTotal(CashflowSummaryDto s, boolean isBank) {
        if (s.getOpening() == null) return BigDecimal.ZERO;
        if (isBank) {
            BigDecimal b = s.getOpening().getBankTotal();
            if (b != null) return b;
            // Fallback: cộng tay nếu bankTotal null nhưng có list banks.
            List<BankBalanceDto> list = s.getOpening().getBanks();
            if (list == null || list.isEmpty()) return BigDecimal.ZERO;
            BigDecimal sum = BigDecimal.ZERO;
            for (BankBalanceDto x : list) {
                if (x != null && x.getBalance() != null) sum = sum.add(x.getBalance());
            }
            return sum;
        }
        BigDecimal cash = s.getOpening().getCash();
        return cash == null ? BigDecimal.ZERO : cash;
    }

    private static CellStyle textStyle(XSSFWorkbook wb, HorizontalAlignment align, byte[] bg, boolean wrap) {
        CellStyle st = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 10);
        st.setFont(f);
        st.setAlignment(align);
        st.setVerticalAlignment(VerticalAlignment.CENTER);
        st.setBorderTop(BorderStyle.THIN);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBorderLeft(BorderStyle.THIN);
        st.setBorderRight(BorderStyle.THIN);
        st.setWrapText(wrap);
        if (align == HorizontalAlignment.LEFT) st.setIndention((short) 1);
        if (bg != null) {
            st.setFillForegroundColor(new XSSFColor(bg, null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return st;
    }

    private static CellStyle amountStyle(XSSFWorkbook wb, DataFormat df, byte[] bg) {
        CellStyle st = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 10);
        st.setFont(f);
        st.setAlignment(HorizontalAlignment.RIGHT);
        st.setVerticalAlignment(VerticalAlignment.CENTER);
        st.setDataFormat(df.getFormat("#,##0"));
        st.setBorderTop(BorderStyle.THIN);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBorderLeft(BorderStyle.THIN);
        st.setBorderRight(BorderStyle.THIN);
        if (bg != null) {
            st.setFillForegroundColor(new XSSFColor(bg, null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return st;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Quy tắc chung cho cả phiếu thu và phiếu chi:
     * paymentType = BANK_TRANSFER <b>và</b> bank là Vietinbank → sổ ngân hàng.
     * Mọi trường hợp còn lại (tiền mặt, hoặc CK từ ngân hàng khác) → sổ quỹ.
     *
     * <p>Nhận diện Vietin bằng cách bỏ dấu + lowercase rồi tìm chuỗi con "vietin"
     * — khớp "VietinBank", "Vietin Bank", "VIETIN", "Ngân hàng Vietin"...
     */
    private static boolean isBankTransferVietin(CashflowFlowDto f) {
        if (f == null) return false;
        String pt = f.getPaymentType() == null ? "CASH" : f.getPaymentType();
        if (!"BANK_TRANSFER".equalsIgnoreCase(pt)) return false;
        String bank = f.getBankName();
        if (bank == null || bank.isBlank()) return false;
        String norm = java.text.Normalizer
                .normalize(bank.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd');
        return norm.contains("vietin");
    }

    /**
     * Ước lượng số dòng wrap (POI không auto-fit cho wrapText khi merge).
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
                while (cur > charsPerLine) { lines++; cur -= charsPerLine; }
            }
            total += lines;
        }
        return Math.max(1, total);
    }
}