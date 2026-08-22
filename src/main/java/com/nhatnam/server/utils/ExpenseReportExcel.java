package com.nhatnam.server.utils;

import com.nhatnam.server.entity.ExpenseItem;
import com.nhatnam.server.entity.ExpenseVoucher;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
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

public final class ExpenseReportExcel {

    private ExpenseReportExcel() {}

    private static final ZoneId TZ = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final DateTimeFormatter D  = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final byte[] WHITE  = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    private static final byte[] ALT_BG = {(byte) 0xFA, (byte) 0xF7, (byte) 0xF2};
    private static final byte[] NAVY   = {(byte) 0x1A, (byte) 0x1A, (byte) 0x2E};
    private static final byte[] GOLD   = {(byte) 0xC9, (byte) 0xA8, (byte) 0x4C};

    public static byte[] buildReport(List<ExpenseVoucher> vouchers, long from, long to,
                                     String exportedBy, boolean showPaymentCol,
                                     String methodLabel) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Phiếu chi");
            DataFormat df = wb.createDataFormat();

            // Sắp xếp phiếu chi theo số phiếu chi tăng dần
            List<ExpenseVoucher> sortedVouchers = new ArrayList<>(vouchers);
            sortedVouchers.sort((v1, v2) -> {
                String num1 = v1.getPaymentNumber() != null ? v1.getPaymentNumber() : v1.getVoucherCode();
                String num2 = v2.getPaymentNumber() != null ? v2.getPaymentNumber() : v2.getVoucherCode();

                String digits1 = num1.replaceAll("[^0-9]", "");
                String digits2 = num2.replaceAll("[^0-9]", "");

                if (!digits1.isEmpty() && !digits2.isEmpty()) {
                    try {
                        long n1 = Long.parseLong(digits1);
                        long n2 = Long.parseLong(digits2);
                        return Long.compare(n1, n2);
                    } catch (NumberFormatException e) {
                        return num1.compareTo(num2);
                    }
                }
                return num1.compareTo(num2);
            });

            // Chỉ số cột - Thứ tự mới: Thời gian, Số phiếu chi, Nhà cung cấp, Nội dung, PTTT, Tổng tiền
            int c = 0;
            final int PERIOD = c++;         // Thời gian/Kỳ - cột A
            final int VOUCHER_NUMBER = c++; // Số phiếu chi - cột B
            final int VENDOR = c++;         // Nhà cung cấp - cột C
            final int REASON = c++;         // Nội dung - cột D
            final int PAYCOL = showPaymentCol ? c++ : -1; // Phương thức thanh toán - cột E (nếu có)
            final int TOTAL = c++;          // Tổng tiền phiếu - cột cuối
            final int lastCol = TOTAL;      // Cột cuối cùng

            // Styles với font nhỏ cho A4
            CellStyle title = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 12);
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle meta = wb.createCellStyle();
            XSSFFont metaFont = wb.createFont();
            metaFont.setItalic(true);
            metaFont.setFontHeightInPoints((short) 9);
            meta.setFont(metaFont);
            meta.setAlignment(HorizontalAlignment.CENTER);
            meta.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle header = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 9);
            headerFont.setColor(new XSSFColor(WHITE, null));
            header.setFont(headerFont);
            header.setFillForegroundColor(new XSSFColor(NAVY, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            header.setBorderBottom(BorderStyle.THIN);
            header.setBorderLeft(BorderStyle.THIN);
            header.setBorderRight(BorderStyle.THIN);

            // Text hiển thị với căn giữa
            CellStyle dataN = text(wb, null, true);
            CellStyle dataA = text(wb, ALT_BG, true);
            CellStyle reasonN = text(wb, null, true);
            CellStyle reasonA = text(wb, ALT_BG, true);

            // Text ẩn (chữ trùng màu nền)
            CellStyle hidN = hiddenText(wb, null);
            CellStyle hidA = hiddenText(wb, ALT_BG);

            // Style cho ô "Tổng tiền phiếu"
            CellStyle totalPerVoucherN = wb.createCellStyle();
            XSSFFont totalFont = wb.createFont();
            totalFont.setFontHeightInPoints((short) 9);
            totalPerVoucherN.setFont(totalFont);
            totalPerVoucherN.setAlignment(HorizontalAlignment.CENTER);
            totalPerVoucherN.setVerticalAlignment(VerticalAlignment.CENTER);
            totalPerVoucherN.setDataFormat(df.getFormat("#,##0"));
            totalPerVoucherN.setBorderBottom(BorderStyle.THIN);
            totalPerVoucherN.setBorderLeft(BorderStyle.THIN);
            totalPerVoucherN.setBorderRight(BorderStyle.THIN);

            CellStyle totalPerVoucherA = wb.createCellStyle();
            totalPerVoucherA.setFont(totalFont);
            totalPerVoucherA.setAlignment(HorizontalAlignment.CENTER);
            totalPerVoucherA.setVerticalAlignment(VerticalAlignment.CENTER);
            totalPerVoucherA.setDataFormat(df.getFormat("#,##0"));
            totalPerVoucherA.setFillForegroundColor(new XSSFColor(ALT_BG, null));
            totalPerVoucherA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalPerVoucherA.setBorderBottom(BorderStyle.THIN);
            totalPerVoucherA.setBorderLeft(BorderStyle.THIN);
            totalPerVoucherA.setBorderRight(BorderStyle.THIN);

            int rowIdx = 0;

            // Row 0: Tiêu đề
            Row titleRow = sheet.createRow(rowIdx++);
            titleRow.setHeightInPoints(22);
            Cell tc = titleRow.createCell(0);
            tc.setCellValue("BÁO CÁO PHIẾU CHI");
            tc.setCellStyle(title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));

            // Row 1: Meta
            String fromStr = Instant.ofEpochMilli(from).atZone(TZ).format(D);
            String toStr   = Instant.ofEpochMilli(to).atZone(TZ).format(D);
            String exportedAt = java.time.LocalDateTime.now(TZ).format(DT);
            Row metaRow = sheet.createRow(rowIdx++);
            metaRow.setHeightInPoints(16);
            Cell mc = metaRow.createCell(0);
            mc.setCellValue("Từ " + fromStr + " đến " + toStr
                    + " | PTTT: " + methodLabel
                    + " | Xuất: " + exportedAt
                    + " | Người xuất: " + (exportedBy != null ? exportedBy : ""));
            mc.setCellStyle(meta);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));

            // Row 2: trống
            sheet.createRow(rowIdx++);

            // Row 3: Header
            Row hRow = sheet.createRow(rowIdx++);
            hRow.setHeightInPoints(22);
            String[] labels = new String[lastCol + 1];
            labels[PERIOD] = "Thời gian";
            labels[VOUCHER_NUMBER] = "Số PC";
            labels[VENDOR] = "Người nhận";
            labels[REASON] = "Nội dung";
            if (PAYCOL >= 0) labels[PAYCOL] = "PTTT";
            labels[TOTAL] = "Tổng tiền";

            for (int i = 0; i <= lastCol; i++) {
                Cell hc = hRow.createCell(i);
                hc.setCellValue(labels[i]);
                hc.setCellStyle(header);
            }

            final int dataStart = rowIdx;

            // Data - Tính tổng tiền của từng phiếu
            BigDecimal grandTotal = BigDecimal.ZERO;

            for (int vi = 0; vi < sortedVouchers.size(); vi++) {
                ExpenseVoucher v = sortedVouchers.get(vi);
                boolean alt = (vi % 2 == 1);

                List<ExpenseItem> items = v.getItems() == null ? new ArrayList<>() : new ArrayList<>(v.getItems());

                // Tính tổng tiền của phiếu
                BigDecimal total = items.stream().filter(Objects::nonNull)
                        .map(ExpenseItem::getAmount).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                grandTotal = grandTotal.add(total);

                String voucherNumber = v.getPaymentNumber() != null ? v.getPaymentNumber() : v.getVoucherCode();
                String periodStr   = timeOrPeriod(v);
                String vendorStr   = nz(v.getVendorName());
                String reasonStr   = nz(v.getReason());
                String payStr      = v.getPaymentType() == ExpenseVoucher.PaymentType.BANK_TRANSFER ? "CK" : "TM";

                // Mỗi phiếu là 1 dòng
                Row row = sheet.createRow(rowIdx++);

                // Tính số dòng cần thiết cho wrap text
                int maxLines = 1;
                maxLines = Math.max(maxLines, countLines(periodStr, 12));
                maxLines = Math.max(maxLines, countLines(voucherNumber, 10));
                maxLines = Math.max(maxLines, countLines(vendorStr, 20));
                maxLines = Math.max(maxLines, countLines(reasonStr, 25));
                if (PAYCOL >= 0) {
                    maxLines = Math.max(maxLines, countLines(payStr, 8));
                }

                float height = Math.max(18, Math.min(maxLines * 12, 50));
                row.setHeightInPoints(height);

                // Thứ tự mới: Period -> VoucherNumber -> Vendor -> Reason -> PayCol -> Total
                putText(row, PERIOD, periodStr, true, alt, dataN, dataA, hidN, hidA);
                putText(row, VOUCHER_NUMBER, voucherNumber, true, alt, dataN, dataA, hidN, hidA);
                putText(row, VENDOR, vendorStr, true, alt, dataN, dataA, hidN, hidA);
                putText(row, REASON, reasonStr, true, alt, reasonN, reasonA, hidN, hidA);
                if (PAYCOL >= 0) putText(row, PAYCOL, payStr, true, alt, dataN, dataA, hidN, hidA);

                // Cột Tổng tiền phiếu
                Cell totalCell = row.createCell(TOTAL);
                totalCell.setCellValue(total.doubleValue());
                totalCell.setCellStyle(alt ? totalPerVoucherA : totalPerVoucherN);
            }

            // Độ rộng cột cho A4
            sheet.setColumnWidth(PERIOD, 12 * 256);         // Thời gian
            sheet.setColumnWidth(VOUCHER_NUMBER, 10 * 256); // Số PC
            sheet.setColumnWidth(VENDOR, 15 * 256);         // Người nhận
            sheet.setColumnWidth(REASON, 23 * 256);         // Nội dung
            if (PAYCOL >= 0) sheet.setColumnWidth(PAYCOL, 8 * 256);  // PTTT
            sheet.setColumnWidth(TOTAL, 16 * 256);          // Tổng tiền
            sheet.createFreezePane(0, dataStart);

            // Dòng TỔNG CỘNG
            int totalRowIdx = rowIdx;
            Row totalRow = sheet.createRow(totalRowIdx);
            totalRow.setHeightInPoints(20);

            CellStyle totalLabelStyle = wb.createCellStyle();
            XSSFFont tfBold = wb.createFont();
            tfBold.setBold(true);
            tfBold.setFontHeightInPoints((short) 9);
            totalLabelStyle.setFont(tfBold);
            totalLabelStyle.setAlignment(HorizontalAlignment.RIGHT);
            totalLabelStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            totalLabelStyle.setFillForegroundColor(new XSSFColor(GOLD, null));
            totalLabelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalLabelStyle.setBorderBottom(BorderStyle.THIN);
            totalLabelStyle.setBorderLeft(BorderStyle.THIN);
            totalLabelStyle.setBorderRight(BorderStyle.THIN);

            CellStyle totalSumStyle = wb.createCellStyle();
            totalSumStyle.setFont(tfBold);
            totalSumStyle.setAlignment(HorizontalAlignment.CENTER);
            totalSumStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            totalSumStyle.setDataFormat(df.getFormat("#,##0"));
            totalSumStyle.setFillForegroundColor(new XSSFColor(GOLD, null));
            totalSumStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalSumStyle.setBorderBottom(BorderStyle.THIN);
            totalSumStyle.setBorderLeft(BorderStyle.THIN);
            totalSumStyle.setBorderRight(BorderStyle.THIN);

            // Merge từ cột 0 đến cột TOTAL-1 cho nhãn TỔNG CỘNG
            Cell lblCell = totalRow.createCell(0);
            lblCell.setCellValue("TỔNG CỘNG (" + sortedVouchers.size() + " phiếu)");
            lblCell.setCellStyle(totalLabelStyle);

            if (TOTAL - 1 >= 0) {
                sheet.addMergedRegion(new CellRangeAddress(totalRow.getRowNum(),
                        totalRow.getRowNum(),
                        0, TOTAL - 1));
            }

            // Ô tổng tiền
            Cell grandTotalCell = totalRow.createCell(TOTAL);
            grandTotalCell.setCellValue(grandTotal.doubleValue());
            grandTotalCell.setCellStyle(totalSumStyle);

            // Set print area
            sheet.setDisplayGridlines(false);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static int countLines(String text, int columnWidthInChars) {
        if (text == null || text.isEmpty()) return 1;
        int charsPerLine = Math.max(5, columnWidthInChars);

        int lines = 1;
        int currentLineLength = 0;
        String[] words = text.split(" ");
        for (String word : words) {
            int wordLength = word.length();
            if (currentLineLength + wordLength + 1 > charsPerLine) {
                lines++;
                currentLineLength = wordLength;
            } else {
                currentLineLength += wordLength + 1;
            }
        }
        return Math.max(1, lines);
    }

    private static CellStyle text(XSSFWorkbook wb, byte[] bg, boolean wrap) {
        CellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 9);
        s.setFont(f);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(wrap);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setIndention((short) 1);
        if (bg != null) {
            s.setFillForegroundColor(new XSSFColor(bg, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private static CellStyle hiddenText(XSSFWorkbook wb, byte[] bg) {
        CellStyle s = wb.createCellStyle();
        XSSFFont f = wb.createFont();
        f.setFontHeightInPoints((short) 9);
        f.setColor(new XSSFColor(bg != null ? bg : WHITE, null));
        s.setFont(f);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setIndention((short) 1);
        if (bg != null) {
            s.setFillForegroundColor(new XSSFColor(bg, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return s;
    }

    private static void putText(Row row, int col, String value, boolean first, boolean alt,
                                CellStyle nStyle, CellStyle aStyle, CellStyle hidN, CellStyle hidA) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (first) cell.setCellStyle(alt ? aStyle : nStyle);
        else       cell.setCellStyle(alt ? hidA : hidN);
    }

    private static String timeOrPeriod(ExpenseVoucher v) {
        if (v.getExpensePeriod() != null && !v.getExpensePeriod().isBlank()) {
            String[] parts = v.getExpensePeriod().split("-");
            if (parts.length == 2) {
                return "T" + Integer.parseInt(parts[1]) + "/" + parts[0].substring(2);
            }
            return v.getExpensePeriod();
        }
        if (v.getExpenseDate() != null) {
            return Instant.ofEpochMilli(v.getExpenseDate()).atZone(TZ).format(D);
        }
        return "";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}