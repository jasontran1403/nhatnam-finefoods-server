package com.nhatnam.server.service.attendance;

import com.nhatnam.server.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * FILE MẪU IMPORT THƯỞNG & PHỤ CẤP THEO THÁNG.
 *
 * <h3>Vì sao cột đầu là ID nhân viên</h3>
 * Khớp theo HỌ TÊN rất dễ sai: trùng tên, sai dấu, thừa khoảng trắng, viết hoa
 * khác nhau. Cột <b>ID</b> do hệ thống điền sẵn và KHOÁ lại, người nhập chỉ việc
 * điền số tiền. Cột Họ tên đi kèm chỉ để người nhập nhìn cho dễ, backend không
 * dùng để khớp.
 *
 * <h3>Hai file khác nhau ở chỗ nào</h3>
 * <pre>
 *   THƯỞNG   — nhãn dùng CHUNG cho cả file, gõ 1 lần ở ô B2
 *              (VD "Thưởng Tết", "Thưởng Noel"). Mỗi nhân viên 1 dòng, 1 số tiền.
 *
 *   PHỤ CẤP  — mỗi nhân viên có thể có NHIỀU khoản, xếp thành từng cặp
 *              (Nhãn, Số tiền). Ô nhãn là dropdown lấy từ bảng AllowanceLabel.
 * </pre>
 */
@Slf4j
public class PayrollAdjustmentTemplateService {

    private PayrollAdjustmentTemplateService() {}

    /** Số cặp (Nhãn, Số tiền) dựng sẵn cho mỗi nhân viên trong file phụ cấp. */
    public static final int ALLOWANCE_PAIRS = 4;

    /** Dòng chứa nhãn thưởng dùng chung (0-based) trong file thưởng. */
    public static final int BONUS_LABEL_ROW = 1;
    /** Cột chứa nhãn thưởng dùng chung (0-based) — cột B. */
    public static final int BONUS_LABEL_COL = 1;

    /**
     * Dòng đầu tiên (0-based) chứa DATA trong file thưởng — trước đó là:
     *   0 = title "IMPORT THƯỞNG..."
     *   1 = nhãn thưởng dùng chung
     *   2 = guide
     *   3 = header "ID / Họ tên / Số tiền"
     *   4+ = data
     */
    public static final int BONUS_DATA_START_ROW = 4;

    /**
     * Dòng đầu tiên (0-based) chứa DATA trong file phụ cấp — trước đó là:
     *   0 = title
     *   1 = guide
     *   2 = header "ID / Họ tên / Khoản 1 / Số tiền 1 / ..."
     *   3+ = data
     */
    public static final int ALLOWANCE_DATA_START_ROW = 3;

    private static final String LIST_SHEET = "DanhMuc";

    private static final byte[] GOLD  = {(byte) 201, (byte) 168, (byte) 76};
    private static final byte[] DARK  = {(byte) 28,  (byte) 28,  (byte) 30};
    private static final byte[] CREAM = {(byte) 250, (byte) 247, (byte) 242};
    private static final byte[] LOCK  = {(byte) 245, (byte) 245, (byte) 245};

    // ══════════════════════════════════════════════════════════════════════════
    // FILE THƯỞNG
    // ══════════════════════════════════════════════════════════════════════════

    public static byte[] buildBonusTemplate(int month, int year, List<User> employees,
                                             String departmentLabel) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet("Thuong %02d-%d".formatted(month, year));
            Styles st = new Styles(wb);

            String[] headers = {"ID", "Họ tên", "Số tiền thưởng"};
            int[] widths = {2600, 10000, 5200};
            for (int c = 0; c < widths.length; c++) sh.setColumnWidth(c, widths[c]);

            String deptPart = departmentLabel != null && !departmentLabel.isBlank()
                    ? departmentLabel.toUpperCase() + " — " : "";
            title(sh, st, headers.length,
                    "IMPORT THƯỞNG — %sTHÁNG %02d/%d".formatted(deptPart, month, year));

            // Dòng nhãn thưởng dùng chung
            Row lab = sh.createRow(BONUS_LABEL_ROW);
            lab.setHeightInPoints(26);
            Cell k = lab.createCell(0);
            k.setCellValue("Nhãn thưởng:");
            k.setCellStyle(st.keyCell);
            Cell v = lab.createCell(BONUS_LABEL_COL);
            v.setCellStyle(st.inputCell);
            v.setCellValue("Thưởng Tết");
            Cell hint = lab.createCell(2);
            hint.setCellValue("← gõ tên khoản thưởng, áp dụng cho MỌI dòng bên dưới");
            hint.setCellStyle(st.guide);

            guide(sh, st, 2, headers.length,
                    "Chỉ điền cột Số tiền. Cột ID và Họ tên do hệ thống điền sẵn, không sửa. "
                            + "Ai không có thưởng thì để trống hoặc điền 0. "
                            + "Import lại cùng tháng sẽ THAY THẾ toàn bộ thưởng đã nhập trước đó.");

            int firstData = headerRow(sh, st, headers, 3);
            fillEmployees(sh, st, firstData, employees, headers.length, 2);

            sh.createFreezePane(0, firstData);
            return toBytes(wb);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILE PHỤ CẤP
    // ══════════════════════════════════════════════════════════════════════════

    public static byte[] buildAllowanceTemplate(int month, int year,
                                                List<User> employees,
                                                List<String> allowanceLabels,
                                                String departmentLabel) throws IOException {
        // Loại phụ cấp cơm ra khỏi dropdown — cơm do hệ thống tự tính theo số
        // ngày điểm danh, KHÔNG cho user chọn nhầm để tránh trùng khoản.
        List<String> filteredLabels = allowanceLabels == null ? new java.util.ArrayList<>()
                : allowanceLabels.stream()
                    .filter(lbl -> lbl != null && !isMealLabel(lbl))
                    .toList();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet("Phu cap %02d-%d".formatted(month, year));
            Styles st = new Styles(wb);

            int cols = 2 + ALLOWANCE_PAIRS * 2;
            String[] headers = new String[cols];
            int[] widths = new int[cols];
            headers[0] = "ID";        widths[0] = 2600;
            headers[1] = "Họ tên";    widths[1] = 10000;
            for (int i = 0; i < ALLOWANCE_PAIRS; i++) {
                headers[2 + i * 2]     = "Khoản phụ cấp " + (i + 1);
                headers[2 + i * 2 + 1] = "Số tiền " + (i + 1);
                widths[2 + i * 2]      = 7200;
                widths[2 + i * 2 + 1]  = 4600;
            }
            for (int c = 0; c < cols; c++) sh.setColumnWidth(c, widths[c]);

            String labelRef = buildLabelSheet(wb, filteredLabels);

            String deptPart = departmentLabel != null && !departmentLabel.isBlank()
                    ? departmentLabel.toUpperCase() + " — " : "";
            title(sh, st, cols, "IMPORT PHỤ CẤP — %sTHÁNG %02d/%d".formatted(deptPart, month, year));
            guide(sh, st, 1, cols,
                    "Mỗi nhân viên 1 dòng, có thể nhập tối đa %d khoản. Ô \"Khoản phụ cấp\" bấm mũi tên để chọn — "
                            .formatted(ALLOWANCE_PAIRS)
                            + "KHÔNG tự gõ tên mới, nhãn lạ sẽ bị báo lỗi. "
                            + "Phụ cấp cơm KHÔNG nhập ở đây (hệ thống tự tính theo ngày đi làm). "
                            + "Import lại cùng tháng sẽ THAY THẾ toàn bộ phụ cấp đã nhập trước đó.");

            int firstData = headerRow(sh, st, headers, 2);
            int lastData = fillEmployees(sh, st, firstData, employees, cols, -1);

            // Dropdown nhãn cho từng cột "Khoản phụ cấp"
            for (int i = 0; i < ALLOWANCE_PAIRS; i++) {
                dropdown(sh, firstData, lastData, 2 + i * 2, labelRef,
                        "Chọn khoản phụ cấp",
                        filteredLabels.isEmpty()
                                ? "Danh mục phụ cấp đang trống — hãy tạo nhãn ở trang Nhân sự trước."
                                : "Chọn 1 trong %d khoản đã khai báo.".formatted(filteredLabels.size()));
            }

            sh.createFreezePane(2, firstData);
            return toBytes(wb);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH DỰNG SHEET
    // ══════════════════════════════════════════════════════════════════════════

    private static void title(XSSFSheet sh, Styles st, int cols, String text) {
        Row r = sh.createRow(0);
        r.setHeightInPoints(30);
        for (int c = 0; c < cols; c++) r.createCell(c).setCellStyle(st.title);
        r.getCell(0).setCellValue(text);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, cols - 1));
    }

    private static void guide(XSSFSheet sh, Styles st, int rowIdx, int cols, String text) {
        Row r = sh.createRow(rowIdx);
        r.setHeightInPoints(36);
        for (int c = 0; c < cols; c++) r.createCell(c).setCellStyle(st.guide);
        r.getCell(0).setCellValue(text);
        sh.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, cols - 1));
    }

    /** Ghi dòng tiêu đề cột, trả về chỉ số dòng dữ liệu đầu tiên. */
    private static int headerRow(XSSFSheet sh, Styles st, String[] headers, int rowIdx) {
        Row r = sh.createRow(rowIdx);
        r.setHeightInPoints(24);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = r.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(st.header);
        }
        return rowIdx + 1;
    }

    /**
     * Điền sẵn ID + Họ tên cho từng nhân viên.
     *
     * @param moneyCol cột định dạng tiền (-1 = không có cột cố định, dùng cho file
     *                 phụ cấp vì các cột tiền nằm xen kẽ)
     * @return chỉ số dòng cuối cùng
     */
    private static int fillEmployees(XSSFSheet sh, Styles st, int firstData,
                                     List<User> employees, int cols, int moneyCol) {
        int r = firstData;
        for (User u : employees) {
            Row row = sh.createRow(r);
            row.setHeightInPoints(18);

            for (int c = 0; c < cols; c++) {
                Cell cell = row.createCell(c);
                if (c <= 1) cell.setCellStyle(st.locked);
                else if (moneyCol >= 0) cell.setCellStyle(c == moneyCol ? st.money : st.cell);
                else cell.setCellStyle(c % 2 == 1 ? st.money : st.cell);
            }

            row.getCell(0).setCellValue(u.getId());
            row.getCell(1).setCellValue(u.getFullName() != null ? u.getFullName() : "");
            r++;
        }
        return Math.max(firstData, r - 1);
    }

    /** Sheet danh mục ẩn chứa nhãn phụ cấp, trả về tham chiếu vùng cho dropdown. */
    private static String buildLabelSheet(XSSFWorkbook wb, List<String> labels) {
        XSSFSheet sh = wb.createSheet(LIST_SHEET);
        for (int i = 0; i < labels.size(); i++) {
            sh.createRow(i).createCell(0).setCellValue(labels.get(i));
        }
        wb.setSheetHidden(wb.getSheetIndex(sh), true);

        // Danh mục rỗng vẫn phải trả vùng hợp lệ, nếu không Excel báo file hỏng
        int last = Math.max(1, labels.size());
        return "%s!$A$1:$A$%d".formatted(LIST_SHEET, last);
    }

    private static void dropdown(XSSFSheet sh, int firstRow, int lastRow, int col,
                                 String rangeRef, String title, String msg) {
        if (lastRow < firstRow) return;
        DataValidationHelper h = sh.getDataValidationHelper();
        DataValidationConstraint c = h.createFormulaListConstraint(rangeRef);
        DataValidation dv = h.createValidation(c, new CellRangeAddressList(firstRow, lastRow, col, col));
        dv.setShowErrorBox(true);
        dv.setSuppressDropDownArrow(true);
        dv.setEmptyCellAllowed(true);
        dv.setShowPromptBox(true);
        dv.createPromptBox(title, msg);
        dv.createErrorBox("Nhãn không hợp lệ", "Vui lòng chọn từ danh sách có sẵn.");
        sh.addValidationData(dv);
    }

    private static byte[] toBytes(Workbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static String bonusFileName(int month, int year) {
        return "thuong-%02d-%d.xlsx".formatted(month, year);
    }

    public static String allowanceFileName(int month, int year) {
        return "phu-cap-%02d-%d.xlsx".formatted(month, year);
    }

    // ══════════════════════════════════════════════════════════════════════════

    private static class Styles {
        final XSSFCellStyle title, guide, header, cell, money, locked, keyCell, inputCell;

        Styles(XSSFWorkbook wb) {
            XSSFFont fTitle = wb.createFont();
            fTitle.setBold(true); fTitle.setFontHeightInPoints((short) 14);
            fTitle.setColor(new XSSFColor(CREAM, null));

            XSSFFont fHeader = wb.createFont();
            fHeader.setBold(true); fHeader.setFontHeightInPoints((short) 11);
            fHeader.setColor(new XSSFColor(DARK, null));

            XSSFFont fBody = wb.createFont();
            fBody.setFontHeightInPoints((short) 11);

            XSSFFont fSmall = wb.createFont();
            fSmall.setFontHeightInPoints((short) 9);
            fSmall.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            XSSFFont fBold = wb.createFont();
            fBold.setBold(true); fBold.setFontHeightInPoints((short) 11);

            title = wb.createCellStyle();
            title.setFont(fTitle);
            title.setFillForegroundColor(new XSSFColor(DARK, null));
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            guide = wb.createCellStyle();
            guide.setFont(fSmall);
            guide.setFillForegroundColor(new XSSFColor(CREAM, null));
            guide.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            guide.setVerticalAlignment(VerticalAlignment.CENTER);
            guide.setWrapText(true);

            header = wb.createCellStyle();
            header.setFont(fHeader);
            header.setFillForegroundColor(new XSSFColor(GOLD, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            border(header);

            cell = wb.createCellStyle();
            cell.setFont(fBody);
            border(cell);

            money = wb.createCellStyle();
            money.setFont(fBody);
            money.setAlignment(HorizontalAlignment.RIGHT);
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            border(money);

            locked = wb.createCellStyle();
            locked.setFont(fBody);
            locked.setFillForegroundColor(new XSSFColor(LOCK, null));
            locked.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(locked);

            keyCell = wb.createCellStyle();
            keyCell.setFont(fBold);
            keyCell.setAlignment(HorizontalAlignment.RIGHT);
            keyCell.setVerticalAlignment(VerticalAlignment.CENTER);

            inputCell = wb.createCellStyle();
            inputCell.setFont(fBold);
            inputCell.setFillForegroundColor(new XSSFColor(GOLD, null));
            inputCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            inputCell.setVerticalAlignment(VerticalAlignment.CENTER);
            border(inputCell);
        }

        private static void border(XSSFCellStyle s) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
    }

    /**
     * TRUE nếu nhãn phụ cấp là "cơm trưa" (không phân biệt hoa/thường/dấu).
     * Khớp với {@code PayrollAdjustmentService.isMealLabel} — cả 2 đầu (tạo
     * template & lúc import) phải cùng logic, nếu không sẽ có nhãn xuất hiện
     * ở dropdown nhưng bị từ chối khi import.
     */
    private static boolean isMealLabel(String label) {
        if (label == null) return false;
        String norm = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'd')
                .toLowerCase().trim();
        return norm.contains("com");
    }
}