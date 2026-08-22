package com.nhatnam.server.service.attendance;

import com.nhatnam.server.entity.AttendanceLeaveRequest.LeaveStatus;
import com.nhatnam.server.enumtype.AttendanceExceptionType;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * SINH FILE EXCEL MẪU cho 2 loại dữ liệu ngoại lệ chấm công.
 *
 * <h3>Ràng buộc nhập liệu</h3>
 * Mọi ô đều là DROPDOWN, không gõ tay được:
 * <ul>
 *   <li><b>Loại</b> — 5 lựa chọn cố định</li>
 *   <li><b>Ngày</b> — từ 1 đến ngày cuối của tháng</li>
 *   <li><b>Nhân viên</b> (file đơn xin nghỉ) — danh sách nhân sự xưởng</li>
 *   <li><b>Mốc thời gian</b> — bước 10 phút, từ 08:00 đến 17:00.
 *       Dropdown này <b>phụ thuộc cột Loại</b>: chỉ có lựa chọn khi Loại là
 *       "Đi trễ" hoặc "Về sớm", các loại nghỉ thì danh sách rỗng nên không
 *       điền được.</li>
 * </ul>
 *
 * <h3>Vì sao cần sheet danh mục ẩn</h3>
 * Excel giới hạn danh sách nhập trực tiếp ở 255 ký tự. Riêng danh sách giờ đã
 * 55 mục ≈ 329 ký tự, chưa kể danh sách nhân viên. Nên tất cả danh mục được đặt
 * ở sheet {@value #LIST_SHEET} (ẩn đi) rồi tham chiếu tới.
 */
@Slf4j
public class AttendanceTemplateService {

    /** Tên sheet chứa danh mục cho các dropdown. Sheet này được ẩn. */
    private static final String LIST_SHEET = "DanhMuc";

    private static final String[] WEEKDAY = {"Hai", "Ba", "Tư", "Năm", "Sáu", "Bảy", "CN"};

    /** Vị trí cột "Mốc thời gian" — giống nhau ở cả 2 template. */
    private static final int TIME_COL = 3;

    /** Bước nhảy của danh sách mốc thời gian (phút). */
    private static final int TIME_STEP_MINUTES = 10;
    private static final int TIME_FROM_HOUR = 8;
    private static final int TIME_TO_HOUR = 17;

    // Bảng màu đồng bộ với giao diện web
    private static final byte[] GOLD      = {(byte) 201, (byte) 168, (byte) 76};
    private static final byte[] DARK      = {(byte) 28,  (byte) 28,  (byte) 30};
    private static final byte[] CREAM     = {(byte) 250, (byte) 247, (byte) 242};
    private static final byte[] SUNDAY_BG = {(byte) 253, (byte) 235, (byte) 235};
    private static final byte[] LOCKED_BG = {(byte) 245, (byte) 245, (byte) 245};

    // ══════════════════════════════════════════════════════════════════════════
    // 1. TEMPLATE LỊCH NGHỈ / ĐI TRỄ / VỀ SỚM CỦA CÔNG TY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * File mẫu áp dụng cho TOÀN CÔNG TY.
     * Cột: Ngày · Thứ · Loại · Mốc thời gian · Ghi chú.
     * Mỗi ngày trong tháng là 1 dòng sẵn, chỉ cần chọn Loại ở dòng cần khai báo.
     */
    public static byte[] buildExceptionTemplate(int month, int year) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet("Lich nghi %02d-%d".formatted(month, year));
            Styles st = new Styles(wb);
            int lastDay = YearMonth.of(year, month).lengthOfMonth();

            Lists lists = buildListSheet(wb, lastDay, List.of());

            String[] headers = {"Ngày", "Thứ", "Loại", "Mốc thời gian", "Ghi chú"};
            int[] widths = {3200, 2600, 8800, 5000, 11000};

            int firstData = writeHeader(sh, st, headers, widths,
                    "LỊCH NGHỈ / ĐI TRỄ / VỀ SỚM — THÁNG %02d/%d".formatted(month, year),
                    "Chỉ chọn ở những ngày CÓ ngoại lệ, ngày bình thường để trống. "
                            + "Tất cả các cột đều chọn từ danh sách sẵn có. "
                            + "Riêng Mốc thời gian thì TỰ GÕ, chỉ điền khi Loại là \"Đi trễ\" "
                            + "hoặc \"Về sớm\" — ô tự chuyển ĐỎ nếu thừa, VÀNG nếu còn thiếu.");

            int lastData = writeDayRows(sh, st, firstData, month, year, headers.length);

            // Loại (cột C = 2) · Mốc thời gian (cột D = 3) phụ thuộc cột C
            listFrom(sh, firstData, lastData, 2, lists.typeRef(),
                    "Chọn loại", "Bấm mũi tên để chọn 1 trong 5 loại.");
            timeHint(sh, firstData, lastData, 3);
            highlightTimeIssues(sh, firstData, lastData, 'C', 'D');

            writeLegend(sh, st, lastData + 2, headers.length - 1, false);
            sh.createFreezePane(0, firstData);
            return toBytes(wb);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. TEMPLATE ĐƠN XIN ĐI TRỄ / VỀ SỚM / NGHỈ PHÉP CÁ NHÂN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * File mẫu cho ĐƠN CÁ NHÂN — mỗi dòng là 1 phiếu nghỉ của 1 người.
     * Cột: Nhân viên · Ngày · Loại · Mốc thời gian · Trạng thái · Ghi chú.
     *
     * @param employeeNames họ tên nhân sự xưởng (role {@code FACTORY_*}),
     *                      dùng làm danh sách chọn ở cột Nhân viên
     */
    public static byte[] buildLeaveRequestTemplate(int month, int year,
                                                   List<String> employeeNames) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet("Don xin nghi %02d-%d".formatted(month, year));
            Styles st = new Styles(wb);
            int lastDay = YearMonth.of(year, month).lengthOfMonth();

            Lists lists = buildListSheet(wb, lastDay, employeeNames);

            String[] headers = {"Nhân viên", "Ngày", "Loại", "Mốc thời gian", "Trạng thái", "Ghi chú"};
            int[] widths = {9000, 3000, 8800, 5000, 4800, 9000};

            int firstData = writeHeader(sh, st, headers, widths,
                    "ĐƠN XIN ĐI TRỄ / VỀ SỚM / NGHỈ PHÉP — THÁNG %02d/%d".formatted(month, year),
                    "Mỗi dòng là 1 phiếu nghỉ. Tất cả các cột đều chọn từ danh sách sẵn có. "
                            + "CHỈ đơn ở trạng thái \"Đã duyệt\" mới được tính đủ công. "
                            + "Ô Mốc thời gian tự chuyển ĐỎ nếu điền thừa, VÀNG nếu còn thiếu.");

            int lastData = firstData + 199;      // 200 dòng trống sẵn
            for (int r = firstData; r <= lastData; r++) {
                Row rr = sh.createRow(r);
                rr.setHeightInPoints(18);
                for (int c = 0; c < headers.length; c++)
                    rr.createCell(c).setCellStyle(c == TIME_COL ? st.textCell : st.cell);
            }

            listFrom(sh, firstData, lastData, 0, lists.employeeRef(),
                    "Chọn nhân viên",
                    employeeNames.isEmpty()
                            ? "Chưa có nhân sự xưởng nào trong hệ thống."
                            : "Danh sách nhân sự xưởng (role FACTORY_*).");
            listFrom(sh, firstData, lastData, 1, lists.dayRef(),
                    "Chọn ngày", "Ngày trong tháng, từ 1 đến %d.".formatted(lastDay));
            listFrom(sh, firstData, lastData, 2, lists.typeRef(),
                    "Chọn loại", "Bấm mũi tên để chọn 1 trong 5 loại.");
            timeHint(sh, firstData, lastData, 3);
            highlightTimeIssues(sh, firstData, lastData, 'C', 'D');
            listFrom(sh, firstData, lastData, 4, lists.statusRef(),
                    "Chọn trạng thái", "Chỉ \"Đã duyệt\" mới được tính đủ công.");

            writeLegend(sh, st, lastData + 2, headers.length - 1, true);
            sh.createFreezePane(0, firstData);
            return toBytes(wb);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHEET DANH MỤC (ẨN)
    // ══════════════════════════════════════════════════════════════════════════

    /** Địa chỉ các vùng danh mục để tham chiếu trong data validation. */
    private record Lists(String timeRef, String blankRef, String dayRef,
                         String employeeRef, String typeRef, String statusRef) {}

    /**
     * Dựng sheet danh mục rồi ẩn đi.
     * <pre>
     *   Cột A — mốc thời gian 08:00 … 17:00, bước 10 phút (55 mục)
     *   Cột B — ô TRỐNG, dùng làm nguồn khi dropdown cần rỗng
     *   Cột C — ngày 1 … ngày cuối tháng
     *   Cột D — họ tên nhân sự xưởng
     *   Cột E — 5 loại ngoại lệ
     *   Cột F — 3 trạng thái duyệt
     * </pre>
     */
    private static Lists buildListSheet(XSSFWorkbook wb, int lastDay, List<String> employees) {
        XSSFSheet ls = wb.createSheet(LIST_SHEET);

        List<String> times = timeOptions();
        String[] types = new String[AttendanceExceptionType.values().length];
        for (int i = 0; i < types.length; i++) types[i] = AttendanceExceptionType.values()[i].getLabel();
        String[] statuses = {LeaveStatus.APPROVED.getLabel(),
                LeaveStatus.REJECTED.getLabel(),
                LeaveStatus.PENDING.getLabel()};

        int rows = Math.max(times.size(),
                Math.max(lastDay, Math.max(employees.size(), Math.max(types.length, statuses.length))));

        // Hàng 0 là tiêu đề cột danh mục cho dễ đọc khi bỏ ẩn sheet
        Row h = ls.createRow(0);
        String[] titles = {"Gio", "Trong", "Ngay", "NhanVien", "Loai", "TrangThai"};
        for (int c = 0; c < titles.length; c++) h.createCell(c).setCellValue(titles[c]);

        for (int i = 0; i < rows; i++) {
            Row r = ls.createRow(i + 1);
            if (i < times.size())     r.createCell(0).setCellValue(times.get(i));
            if (i < lastDay)          r.createCell(2).setCellValue(i + 1);
            if (i < employees.size()) r.createCell(3).setCellValue(employees.get(i));
            if (i < types.length)     r.createCell(4).setCellValue(types[i]);
            if (i < statuses.length)  r.createCell(5).setCellValue(statuses[i]);
        }
        // Ô trống cố định ở B2 — nguồn cho dropdown rỗng
        ls.getRow(1).createCell(1).setCellValue("");

        wb.setSheetHidden(wb.getSheetIndex(ls), true);

        return new Lists(
                "%s!$A$2:$A$%d".formatted(LIST_SHEET, times.size() + 1),
                "%s!$B$2".formatted(LIST_SHEET),
                "%s!$C$2:$C$%d".formatted(LIST_SHEET, lastDay + 1),
                employees.isEmpty() ? "%s!$D$2".formatted(LIST_SHEET)
                        : "%s!$D$2:$D$%d".formatted(LIST_SHEET, employees.size() + 1),
                "%s!$E$2:$E$%d".formatted(LIST_SHEET, types.length + 1),
                "%s!$F$2:$F$%d".formatted(LIST_SHEET, statuses.length + 1));
    }

    /** Danh sách mốc giờ: 08:00, 08:10, … 16:50, 17:00. */
    static List<String> timeOptions() {
        List<String> out = new ArrayList<>();
        for (int h = TIME_FROM_HOUR; h < TIME_TO_HOUR; h++) {
            for (int m = 0; m < 60; m += TIME_STEP_MINUTES) {
                out.add("%02d:%02d".formatted(h, m));
            }
        }
        out.add("%02d:00".formatted(TIME_TO_HOUR));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RÀNG BUỘC NHẬP LIỆU
    // ══════════════════════════════════════════════════════════════════════════

    /** Dropdown lấy nguồn từ một vùng trên sheet danh mục. */
    private static void listFrom(XSSFSheet sh, int firstRow, int lastRow, int col,
                                 String rangeRef, String title, String msg) {
        DataValidationHelper h = sh.getDataValidationHelper();
        DataValidationConstraint c = h.createFormulaListConstraint(rangeRef);
        DataValidation dv = h.createValidation(c, new CellRangeAddressList(firstRow, lastRow, col, col));
        dv.setShowErrorBox(true);
        dv.setSuppressDropDownArrow(true);
        dv.setEmptyCellAllowed(true);
        dv.setShowPromptBox(true);
        dv.createPromptBox(title, msg);
        dv.createErrorBox("Giá trị không hợp lệ", "Vui lòng chọn từ danh sách có sẵn.");
        sh.addValidationData(dv);
    }

    /**
     * GHI CHÚ HƯỚNG DẪN cho ô Mốc thời gian.
     *
     * <p>Cột này KHÔNG dùng dropdown — người dùng tự gõ. Ô được định dạng
     * <b>Text</b> ({@code @}) nên Excel giữ nguyên chuỗi gõ vào, không tự đổi
     * {@code 8.11} thành số 8,11 hay {@code 8,11} thành 811 theo locale.
     *
     * <p>Backend nhận mọi kiểu phân cách, xem
     * {@code AttendanceExceptionParser.parseTimeText}:
     * <pre>
     *   8:11 · 8,11 · 8.11 · 8"11 · 8'11 · 8g11 · 8h11 · 8 11 · 0811 · 811
     *   đều được hiểu là 08:11
     * </pre>
     */
    private static void timeHint(XSSFSheet sh, int firstRow, int lastRow, int col) {
        DataValidationHelper h = sh.getDataValidationHelper();
        // Ràng buộc "luôn hợp lệ" — chỉ để gắn được hộp gợi ý lên ô
        DataValidationConstraint c = h.createCustomConstraint("TRUE");
        DataValidation dv = h.createValidation(c, new CellRangeAddressList(firstRow, lastRow, col, col));
        dv.setShowErrorBox(false);
        dv.setEmptyCellAllowed(true);
        dv.setShowPromptBox(true);
        dv.createPromptBox("Mốc thời gian",
                "Chỉ điền khi Loại là \"" + AttendanceExceptionType.LATE_ARRIVAL.getLabel()
                        + "\" hoặc \"" + AttendanceExceptionType.EARLY_LEAVE.getLabel() + "\".\n"
                        + "Gõ kiểu nào cũng được: 8:11 · 8h11 · 8g11 · 8.11 · 8,11 · 8'11 · 8\"11\n"
                        + "Buổi chiều dùng giờ 24h: 2 giờ 11 chiều gõ là 14:11.");
        sh.addValidationData(dv);
    }

    /**
     * TÔ MÀU CẢNH BÁO cho cột Mốc thời gian, thay cho việc khoá ô:
     * <ul>
     *   <li><b>Đỏ</b> — điền mốc giờ nhưng Loại là loại nghỉ (thừa, sẽ bị bỏ qua)</li>
     *   <li><b>Vàng</b> — Loại là Đi trễ / Về sớm nhưng chưa chọn mốc giờ (thiếu,
     *       dòng này sẽ bị loại khi import)</li>
     * </ul>
     * Định dạng có điều kiện được hỗ trợ rộng hơn nhiều so với data validation
     * dạng công thức, nên chạy được cả trên Numbers.
     *
     * @param typeCol chữ cái cột Loại  @param timeCol chữ cái cột Mốc thời gian
     */
    private static void highlightTimeIssues(XSSFSheet sh, int firstRow, int lastRow,
                                            char typeCol, char timeCol) {
        int r = firstRow + 1;                       // POI 0-based → Excel 1-based
        String late  = AttendanceExceptionType.LATE_ARRIVAL.getLabel();
        String early = AttendanceExceptionType.EARLY_LEAVE.getLabel();
        String needsTime = "OR($%c%d=\"%s\",$%c%d=\"%s\")".formatted(typeCol, r, late, typeCol, r, early);

        SheetConditionalFormatting cf = sh.getSheetConditionalFormatting();
        CellRangeAddress[] region = {
                new CellRangeAddress(firstRow, lastRow,
                        timeCol - 'A', timeCol - 'A')
        };

        // Điền thừa → đỏ
        ConditionalFormattingRule tooMuch = cf.createConditionalFormattingRule(
                "AND($%c%d<>\"\",$%c%d<>\"\",NOT(%s))".formatted(timeCol, r, typeCol, r, needsTime));
        PatternFormatting red = tooMuch.createPatternFormatting();
        red.setFillBackgroundColor(IndexedColors.ROSE.getIndex());
        red.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

        // Thiếu bắt buộc → vàng
        ConditionalFormattingRule missing = cf.createConditionalFormattingRule(
                "AND($%c%d=\"\",%s)".formatted(timeCol, r, needsTime));
        PatternFormatting amber = missing.createPatternFormatting();
        amber.setFillBackgroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        amber.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

        cf.addConditionalFormatting(region, tooMuch, missing);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DỰNG KHUNG
    // ══════════════════════════════════════════════════════════════════════════

    /** Vẽ tiêu đề + dòng hướng dẫn + hàng header. Trả về chỉ số dòng dữ liệu đầu tiên. */
    private static int writeHeader(XSSFSheet sh, Styles st, String[] headers, int[] widths,
                                   String title, String guide) {
        for (int c = 0; c < widths.length; c++) sh.setColumnWidth(c, widths[c]);

        Row r0 = sh.createRow(0);
        r0.setHeightInPoints(30);
        for (int c = 0; c < headers.length; c++) r0.createCell(c).setCellStyle(st.title);
        r0.getCell(0).setCellValue(title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));

        Row r1 = sh.createRow(1);
        r1.setHeightInPoints(34);
        for (int c = 0; c < headers.length; c++) r1.createCell(c).setCellStyle(st.guide);
        r1.getCell(0).setCellValue(guide);
        sh.addMergedRegion(new CellRangeAddress(1, 1, 0, headers.length - 1));

        sh.createRow(2).setHeightInPoints(6);

        Row r3 = sh.createRow(3);
        r3.setHeightInPoints(24);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = r3.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(st.header);
        }
        return 4;
    }

    /** Kê sẵn mỗi ngày trong tháng thành 1 dòng. Trả về chỉ số dòng cuối. */
    private static int writeDayRows(XSSFSheet sh, Styles st, int startRow, int month, int year, int cols) {
        YearMonth ym = YearMonth.of(year, month);
        int r = startRow;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = LocalDate.of(year, month, d);
            boolean sunday = date.getDayOfWeek().getValue() == 7;

            Row row = sh.createRow(r++);
            row.setHeightInPoints(18);

            // Cột Ngày và Thứ là dữ liệu có sẵn, tô xám để phân biệt vùng nhập
            Cell c0 = row.createCell(0);
            c0.setCellValue(d);
            c0.setCellStyle(sunday ? st.sundayCenter : st.lockedCenter);

            Cell c1 = row.createCell(1);
            c1.setCellValue(WEEKDAY[date.getDayOfWeek().getValue() - 1]);
            c1.setCellStyle(sunday ? st.sundayCenter : st.lockedCenter);

            for (int c = 2; c < cols; c++) {
                // Cột 3 = Mốc thời gian → định dạng Text để giữ nguyên chuỗi gõ vào
                XSSFCellStyle style = c == TIME_COL
                        ? (sunday ? st.sundayTextCell : st.textCell)
                        : (sunday ? st.sundayCell : st.cell);
                row.createCell(c).setCellStyle(style);
            }
        }
        return r - 1;
    }

    /** Chú thích ý nghĩa từng loại, đặt dưới bảng. */
    private static void writeLegend(XSSFSheet sh, Styles st, int startRow, int lastCol, boolean withStatus) {
        List<String> lines = new ArrayList<>(List.of(
                "HƯỚNG DẪN",
                "• Nghỉ cả ngày — không điền mốc giờ. Chấm công hay không vẫn tính đủ 1 công.",
                "• Nghỉ nửa ngày - Sáng — không điền mốc giờ. Ca làm còn 13:30 – 17:00.",
                "• Nghỉ nửa ngày - Chiều — không điền mốc giờ. Ca làm còn 08:00 – 12:00.",
                "• Đi trễ — chọn mốc giờ vào ca mới. VD 10:00 thì vào lúc 10:00 là đủ công, 10:01 tính trễ 1 phút.",
                "• Về sớm — chọn mốc giờ tan ca mới. VD 14:00 thì ra lúc 14:00 là đủ công, 13:59 tính sớm 1 phút.",
                "• Mốc thời gian — tự gõ, kiểu nào cũng được: 8:11 · 8h11 · 8g11 · 8.11 · 8,11 · 8'11 · 8\"11",
                "   Buổi chiều dùng giờ 24h — 2 giờ 11 chiều gõ là 14:11.",
                "   Ô chuyển ĐỎ = điền thừa (Loại là loại nghỉ) — hệ thống sẽ bỏ qua giá trị này.",
                "   Ô chuyển VÀNG = còn thiếu (Loại là Đi trễ/Về sớm) — dòng này sẽ bị loại khi import."
        ));
        if (withStatus) {
            lines.add("• Trạng thái — chỉ \"Đã duyệt\" mới được tính đủ công. "
                    + "\"Từ chối\" và \"Chờ duyệt\" đều bị trừ công theo quy tắc thường.");
        }

        int r = startRow;
        for (int i = 0; i < lines.size(); i++) {
            Row row = sh.createRow(r++);
            Cell c = row.createCell(0);
            c.setCellValue(lines.get(i));
            c.setCellStyle(i == 0 ? st.legendTitle : st.legend);
            sh.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, lastCol));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ĐỊNH DẠNG
    // ══════════════════════════════════════════════════════════════════════════

    /** Gom toàn bộ CellStyle dùng trong file — tạo 1 lần rồi tái sử dụng. */
    private static class Styles {
        final XSSFCellStyle title, guide, header, cell, lockedCenter,
                sundayCenter, sundayCell, legend, legendTitle,
                textCell, sundayTextCell;

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

            XSSFFont fSmallBold = wb.createFont();
            fSmallBold.setBold(true); fSmallBold.setFontHeightInPoints((short) 9);

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
            cell.setVerticalAlignment(VerticalAlignment.CENTER);
            border(cell);

            lockedCenter = wb.createCellStyle();
            lockedCenter.setFont(fBody);
            lockedCenter.setAlignment(HorizontalAlignment.CENTER);
            lockedCenter.setVerticalAlignment(VerticalAlignment.CENTER);
            lockedCenter.setFillForegroundColor(new XSSFColor(LOCKED_BG, null));
            lockedCenter.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(lockedCenter);

            sundayCenter = wb.createCellStyle();
            sundayCenter.cloneStyleFrom(lockedCenter);
            sundayCenter.setFillForegroundColor(new XSSFColor(SUNDAY_BG, null));
            sundayCenter.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            sundayCell = wb.createCellStyle();
            sundayCell.cloneStyleFrom(cell);
            sundayCell.setFillForegroundColor(new XSSFColor(SUNDAY_BG, null));
            sundayCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            legend = wb.createCellStyle();
            legend.setFont(fSmall);

            legendTitle = wb.createCellStyle();
            legendTitle.setFont(fSmallBold);

            // Ô Mốc thời gian — định dạng Text để Excel không tự đổi
            // "8.11" thành số 8,11 hay "8,11" thành 811 theo locale máy
            short textFmt = wb.createDataFormat().getFormat("@");

            textCell = wb.createCellStyle();
            textCell.cloneStyleFrom(cell);
            textCell.setAlignment(HorizontalAlignment.CENTER);
            textCell.setDataFormat(textFmt);

            sundayTextCell = wb.createCellStyle();
            sundayTextCell.cloneStyleFrom(sundayCell);
            sundayTextCell.setAlignment(HorizontalAlignment.CENTER);
            sundayTextCell.setDataFormat(textFmt);
        }

        private void border(XSSFCellStyle s) {
            s.setBorderTop(BorderStyle.THIN);    s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);   s.setBorderRight(BorderStyle.THIN);
            s.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            s.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }

    private static byte[] toBytes(Workbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Tên file gợi ý khi tải về. */
    public static String exceptionFileName(int month, int year) {
        return "lich-nghi-%02d-%d.xlsx".formatted(month, year);
    }

    public static String leaveFileName(int month, int year) {
        return "don-xin-nghi-%02d-%d.xlsx".formatted(month, year);
    }
}