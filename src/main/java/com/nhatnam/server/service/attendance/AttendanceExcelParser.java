package com.nhatnam.server.service.attendance;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ĐỌC FILE EXCEL "BẢNG CHI TIẾT CHẤM CÔNG" xuất từ máy chấm công.
 *
 * <h3>Cấu trúc file</h3>
 * Mỗi nhân viên là 1 BLOCK độc lập, lặp lại liên tiếp trong cùng 1 sheet:
 * <pre>
 *   BẢNG CHI TIẾT CHẤM CÔNG                                    ← dòng tiêu đề
 *   Mã nhân viên: 01002   Tên nhân viên: Lê Thị ÚT   Phòng ban: -------
 *   Tổng giờ | 12027 |     | Số lần trễ | 21 | Số phút trễ | 572
 *   Tổng công| 1302  |     | Số lần sớm | 15 | Số phút sớm | 1234
 *   Tăng ca  | 0     |     | Vắng KP    | 4  | Vắng CP     | 0
 *   Chi tiết
 *   Ngày | Thứ | 1     | 2     | 3     | Trễ | Sớm | Về trễ | Giờ | Công | T.Ca1 | T.Ca2 | Ký hiệu
 *        |     | Vào Ra| Vào Ra| Vào Ra|
 *   01/06| Hai | 08:02 17:00 |   |   | 2 | 0 | 0 | 797 | 1 | 0 | 0 | X
 *   02/06| Ba  | 08:05 16:24 |   |   | 6 | 35| 0 | 732 | 92| 0 | 0 | X
 *   ...  (1 dòng cho mỗi ngày trong tháng)
 * </pre>
 *
 * <h3>Ký hiệu ngày</h3>
 * <ul>
 *   <li>{@code X} — có chấm công đủ vào/ra</li>
 *   <li>{@code V} — vắng, không có dữ liệu chấm công</li>
 *   <li>{@code O} — chấm công thiếu (chỉ có giờ vào hoặc chỉ có giờ ra)</li>
 * </ul>
 *
 * <p><b>Lưu ý:</b> class này CHỈ đọc file thành cấu trúc dữ liệu thô, KHÔNG
 * quyết định ngày đó được bao nhiêu công. Việc quy đổi ra số công nằm ở
 * {@code FactoryPayrollService} để sau này thay đổi chính sách tính công không
 * phải sửa parser.
 */
@Slf4j
public class AttendanceExcelParser {

    /** Không dò quá số dòng này để tìm header trong 1 block (tránh loop vô hạn). */
    private static final int MAX_HEADER_SCAN = 15;

    // ══════════════════════════════════════════════════════════════════════════
    // KẾT QUẢ ĐỌC
    // ══════════════════════════════════════════════════════════════════════════

    /** Một lượt chấm công trong ngày (1 cặp Vào–Ra). */
    @Data @Builder
    public static class Session {
        /** Giờ vào (null nếu quên chấm) */
        private LocalTime in;
        /** Giờ ra (null nếu quên chấm) */
        private LocalTime out;
    }

    /** Dữ liệu chấm công của 1 NGÀY. */
    @Data @Builder
    public static class DayRecord {
        private LocalDate date;
        /** Thứ theo file: Hai, Ba, Tư, Năm, Sáu, Bảy, CN */
        private String weekdayLabel;
        /** Các lượt vào/ra trong ngày (file hiện hỗ trợ tối đa 3 lượt) */
        private List<Session> sessions;
        /** Ký hiệu gốc: X | V | O | … */
        private String symbol;
        /** Số phút đi trễ */
        private Integer lateMinutes;
        /** Số phút về sớm */
        private Integer earlyMinutes;
        /** Số phút về trễ */
        private Integer overtimeMinutes;
        /** Cột "Giờ" trong file (đơn vị theo máy chấm công) */
        private Integer rawHours;
        /** Cột "Công" trong file (đơn vị theo máy chấm công) */
        private Integer rawWorkUnits;

        /** Có ít nhất 1 lần quẹt thẻ trong ngày. */
        public boolean hasPunch() {
            if (sessions == null) return false;
            return sessions.stream().anyMatch(s -> s.getIn() != null || s.getOut() != null);
        }

        /** Giờ vào SỚM NHẤT trong ngày. */
        public LocalTime firstIn() {
            if (sessions == null) return null;
            return sessions.stream().map(Session::getIn).filter(java.util.Objects::nonNull)
                    .min(LocalTime::compareTo).orElse(null);
        }

        /** Giờ ra MUỘN NHẤT trong ngày. */
        public LocalTime lastOut() {
            if (sessions == null) return null;
            return sessions.stream().map(Session::getOut).filter(java.util.Objects::nonNull)
                    .max(LocalTime::compareTo).orElse(null);
        }
    }

    /** Toàn bộ dữ liệu chấm công của 1 NHÂN VIÊN trong file. */
    @Data @Builder
    public static class EmployeeBlock {
        /** Mã nhân viên trên máy chấm công (VD: "01002") — KHÁC id trong hệ thống. */
        private String employeeCode;
        /** Tên nhân viên đọc từ file */
        private String employeeName;
        /** Phòng ban đọc từ file (thường là "--------") */
        private String department;

        /** Các số tổng hợp máy chấm công tự tính (đọc để tham khảo/đối chiếu) */
        private Integer totalHours;
        private Integer totalWorkUnits;
        private Integer lateCount;
        private Integer lateMinutes;
        private Integer earlyCount;
        private Integer earlyMinutes;
        private Integer absentUnpaid;
        private Integer absentPaid;

        private List<DayRecord> days;

        /** Dòng bắt đầu block trong file — dùng để báo lỗi cho dễ tra. */
        private int startRow;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ĐỌC FILE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Đọc toàn bộ sheet đầu tiên thành danh sách block nhân viên.
     * File hỏng 1 block sẽ bỏ qua block đó và tiếp tục, không làm chết cả lần import.
     */
    public static List<EmployeeBlock> parse(Workbook wb, Integer expectedYear, List<String> warnings) {
        Sheet ws = wb.getSheetAt(0);
        if (ws == null) {
            warnings.add("File không có sheet nào");
            return List.of();
        }

        DataFormatter fmt = new DataFormatter();
        List<EmployeeBlock> blocks = new ArrayList<>();

        for (int r = ws.getFirstRowNum(); r <= ws.getLastRowNum(); r++) {
            String a = cellText(ws, r, 0, fmt);
            if (!a.contains("Mã nhân viên")) continue;

            try {
                EmployeeBlock block = parseBlock(ws, r, fmt, expectedYear, warnings);
                if (block != null) blocks.add(block);
            } catch (Exception e) {
                warnings.add("Dòng %d: lỗi đọc block nhân viên — %s".formatted(r + 1, e.getMessage()));
                log.warn("[AttendanceParser] Lỗi block tại dòng {}", r + 1, e);
            }
        }

        return blocks;
    }

    /** Đọc 1 block bắt đầu từ dòng "Mã nhân viên: …". */
    private static EmployeeBlock parseBlock(Sheet ws, int infoRow, DataFormatter fmt,
                                            Integer expectedYear, List<String> warnings) {
        // ── 1. Dòng thông tin nhân viên ──────────────────────────────────────
        String info = cellText(ws, infoRow, 0, fmt);
        String code = extractField(info, "Mã nhân viên");
        String name = extractField(info, "Tên nhân viên");
        String dept = extractField(info, "Phòng ban");

        if (name.isBlank()) {
            warnings.add("Dòng %d: không đọc được tên nhân viên".formatted(infoRow + 1));
            return null;
        }

        // ── 2. Các dòng tổng hợp (Tổng giờ / Tổng công / Tăng ca …) ──────────
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (int r = infoRow + 1; r <= Math.min(infoRow + 5, ws.getLastRowNum()); r++) {
            readStatPairs(ws, r, fmt, stats);
        }

        // ── 3. Tìm dòng header "Ngày | Thứ | …" ──────────────────────────────
        int headerRow = -1;
        for (int r = infoRow + 1; r <= Math.min(infoRow + MAX_HEADER_SCAN, ws.getLastRowNum()); r++) {
            if (normalize(cellText(ws, r, 0, fmt)).equals("ngay")) { headerRow = r; break; }
        }
        if (headerRow < 0) {
            warnings.add("Dòng %d: không tìm thấy bảng chi tiết ngày công".formatted(infoRow + 1));
            return null;
        }

        // ── 4. Dòng sub-header "Vào | Ra" → xác định cột từng lượt ───────────
        int subRow = headerRow + 1;
        List<int[]> sessionCols = new ArrayList<>();     // [cột Vào, cột Ra]
        int lastCol = ws.getRow(headerRow) != null ? ws.getRow(headerRow).getLastCellNum() : 20;

        for (int c = 0; c < lastCol; c++) {
            if (normalize(cellText(ws, subRow, c, fmt)).equals("vao")) {
                int outCol = (c + 1 < lastCol && normalize(cellText(ws, subRow, c + 1, fmt)).equals("ra"))
                        ? c + 1 : -1;
                sessionCols.add(new int[]{c, outCol});
            }
        }
        if (sessionCols.isEmpty()) sessionCols.add(new int[]{2, 3});   // fallback theo file mẫu

        // ── 5. Vị trí các cột số liệu trên dòng header ───────────────────────
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int c = 0; c < lastCol; c++) {
            String h = normalize(cellText(ws, headerRow, c, fmt));
            switch (h) {
                case "tre"     -> col.put("late", c);
                case "som"     -> col.put("early", c);
                case "ve tre"  -> col.put("overtime", c);
                case "gio"     -> col.put("hours", c);
                case "cong"    -> col.put("work", c);
                case "ky hieu" -> col.put("symbol", c);
                default -> { }
            }
        }

        // ── 6. Đọc các dòng ngày — dừng khi cột A không còn là ngày ──────────
        List<DayRecord> days = new ArrayList<>();
        for (int r = subRow + 1; r <= ws.getLastRowNum(); r++) {
            LocalDate date = readDate(ws, r, 0, expectedYear);
            if (date == null) {
                // Cho phép 1 dòng trống chen giữa, quá 1 dòng thì kết thúc block
                if (isRowEmpty(ws, r, fmt, lastCol)) continue;
                break;
            }

            List<Session> sessions = new ArrayList<>();
            for (int[] sc : sessionCols) {
                LocalTime in  = readTime(ws, r, sc[0]);
                LocalTime out = sc[1] >= 0 ? readTime(ws, r, sc[1]) : null;
                if (in != null || out != null) sessions.add(Session.builder().in(in).out(out).build());
            }

            days.add(DayRecord.builder()
                    .date(date)
                    .weekdayLabel(cellText(ws, r, 1, fmt).trim())
                    .sessions(sessions)
                    .symbol(col.containsKey("symbol") ? cellText(ws, r, col.get("symbol"), fmt).trim() : "")
                    .lateMinutes(readInt(ws, r, col.get("late")))
                    .earlyMinutes(readInt(ws, r, col.get("early")))
                    .overtimeMinutes(readInt(ws, r, col.get("overtime")))
                    .rawHours(readInt(ws, r, col.get("hours")))
                    .rawWorkUnits(readInt(ws, r, col.get("work")))
                    .build());
        }

        if (days.isEmpty()) {
            warnings.add("Nhân viên \"%s\" (dòng %d): không có dòng ngày nào".formatted(name, infoRow + 1));
            return null;
        }

        return EmployeeBlock.builder()
                .employeeCode(code)
                .employeeName(name)
                .department("--------".equals(dept) ? null : dept)
                .totalHours(stats.get("tong gio"))
                .totalWorkUnits(stats.get("tong cong"))
                .lateCount(stats.get("so lan tre"))
                .lateMinutes(stats.get("so phut tre"))
                .earlyCount(stats.get("so lan som"))
                .earlyMinutes(stats.get("so phut som"))
                .absentUnpaid(stats.get("vang kp"))
                .absentPaid(stats.get("vang cp"))
                .days(days)
                .startRow(infoRow + 1)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH ĐỌC Ô
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Đọc các cặp "nhãn | … | giá trị" nằm rải trên 1 dòng thống kê.
     * Với dòng {@code Tổng giờ | | 12027 | | | Số lần trễ | | 21 | …} sẽ lấy được
     * {@code {"tong gio": 12027, "so lan tre": 21}}.
     */
    private static void readStatPairs(Sheet ws, int r, DataFormatter fmt, Map<String, Integer> out) {
        Row row = ws.getRow(r);
        if (row == null) return;
        int last = row.getLastCellNum();

        for (int c = 0; c < last; c++) {
            String label = normalize(cellText(ws, r, c, fmt));
            if (label.isEmpty() || label.matches("-?\\d+")) continue;

            // Giá trị = ô SỐ đầu tiên nằm bên phải nhãn, trong phạm vi 4 ô
            for (int k = c + 1; k < Math.min(c + 5, last); k++) {
                Integer v = readInt(ws, r, k);
                if (v != null) { out.putIfAbsent(label, v); break; }
                // Gặp nhãn chữ khác thì dừng, tránh lấy nhầm giá trị của nhãn sau
                String next = normalize(cellText(ws, r, k, fmt));
                if (!next.isEmpty() && !next.matches("-?\\d+")) break;
            }
        }
    }

    /** Tách giá trị của 1 trường trong chuỗi "Mã nhân viên: X   Tên nhân viên: Y   Phòng ban: Z". */
    private static String extractField(String text, String label) {
        int i = text.indexOf(label);
        if (i < 0) return "";
        int start = text.indexOf(':', i);
        if (start < 0) return "";
        start++;

        // Kết thúc tại nhãn kế tiếp (nếu có)
        int end = text.length();
        for (String other : new String[]{"Mã nhân viên", "Tên nhân viên", "Phòng ban"}) {
            if (other.equals(label)) continue;
            int j = text.indexOf(other, start);
            if (j > 0 && j < end) end = j;
        }
        return text.substring(start, end).trim();
    }

    private static String cellText(Sheet ws, int r, int c, DataFormatter fmt) {
        if (c < 0) return "";
        Row row = ws.getRow(r);
        if (row == null) return "";
        Cell cell = row.getCell(c);
        return cell == null ? "" : fmt.formatCellValue(cell);
    }

    private static boolean isRowEmpty(Sheet ws, int r, DataFormatter fmt, int lastCol) {
        for (int c = 0; c < lastCol; c++) {
            if (!cellText(ws, r, c, fmt).trim().isEmpty()) return false;
        }
        return true;
    }

    /**
     * Đọc ô ngày. File mẫu dùng ô NGÀY THẬT của Excel, nhưng một số bản xuất
     * lại để dạng chuỗi {@code dd/MM} hoặc {@code dd/MM/yyyy} — hỗ trợ cả hai.
     *
     * @param fallbackYear năm dùng khi chuỗi chỉ có ngày/tháng
     */
    private static LocalDate readDate(Sheet ws, int r, int c, Integer fallbackYear) {
        Row row = ws.getRow(r);
        if (row == null) return null;
        Cell cell = row.getCell(c);
        if (cell == null) return null;

        // Dạng ngày thật của Excel
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            } catch (Exception ignored) { }
        }

        // Dạng chuỗi "dd/MM" hoặc "dd/MM/yyyy"
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue().trim();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?$").matcher(s);
            if (m.matches()) {
                try {
                    int day = Integer.parseInt(m.group(1));
                    int mon = Integer.parseInt(m.group(2));
                    int yr;
                    if (m.group(3) != null) {
                        yr = Integer.parseInt(m.group(3));
                        if (yr < 100) yr += 2000;
                    } else if (fallbackYear != null) {
                        yr = fallbackYear;
                    } else {
                        return null;
                    }
                    return LocalDate.of(yr, mon, day);
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /**
     * Đọc 1 ô giờ chấm. CHẤP NHẬN cả 2 cách lưu trong file:
     * <ul>
     *   <li>Ô NUMERIC format {@code h:mm} — máy chấm công xuất thẳng sẽ như vậy.</li>
     *   <li>Ô STRING chứa chuỗi {@code "07:50"} / {@code "7:50"} / {@code "07:50:12"} —
     *       khi file đi qua Google Sheets / LibreOffice / được dán tay, cột format
     *       "General" sẽ chuyển time về chuỗi. Trước đây parser ÉP cứng {@code
     *       CellType.NUMERIC} nên các khối này bị trả null hết → nhân viên có
     *       0 công dù file có đủ dữ liệu (triệu chứng "chỉ xưởng sản xuất lên
     *       được công", chuyên cần dòng trống).</li>
     * </ul>
     *
     * <p>Trả {@code null} cho mọi ô không parse được hoặc đúng 00:00 (quy ước:
     * 00:00 là ô trống, không phải chấm công lúc nửa đêm).
     */
    private static LocalTime readTime(Sheet ws, int r, int c) {
        if (c < 0) return null;
        Row row = ws.getRow(r);
        if (row == null) return null;
        Cell cell = row.getCell(c);
        if (cell == null) return null;

        // Nhánh 1: ô number đúng dạng — đọc trực tiếp.
        if (cell.getCellType() == CellType.NUMERIC) {
            try {
                LocalTime t = cell.getLocalDateTimeCellValue().toLocalTime();
                return LocalTime.MIDNIGHT.equals(t) ? null : t;
            } catch (Exception e) {
                return null;
            }
        }

        // Nhánh 2: ô chuỗi "HH:mm" (hoặc "H:mm", kèm giây hoặc không).
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue();
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").matcher(s);
            if (!m.matches()) return null;
            try {
                int h = Integer.parseInt(m.group(1));
                int mi = Integer.parseInt(m.group(2));
                int sec = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
                if (h < 0 || h > 23 || mi < 0 || mi > 59 || sec < 0 || sec > 59) return null;
                LocalTime t = LocalTime.of(h, mi, sec);
                return LocalTime.MIDNIGHT.equals(t) ? null : t;
            } catch (Exception e) {
                return null;
            }
        }

        // FORMULA và các kiểu khác: thử ép qua chuỗi.
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                    LocalTime t = cell.getLocalDateTimeCellValue().toLocalTime();
                    return LocalTime.MIDNIGHT.equals(t) ? null : t;
                }
                if (cell.getCachedFormulaResultType() == CellType.STRING) {
                    String s = cell.getStringCellValue();
                    if (s == null || s.trim().isEmpty()) return null;
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").matcher(s.trim());
                    if (m.matches()) {
                        int h = Integer.parseInt(m.group(1));
                        int mi = Integer.parseInt(m.group(2));
                        int sec = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
                        if (h < 24 && mi < 60 && sec < 60) {
                            LocalTime t = LocalTime.of(h, mi, sec);
                            return LocalTime.MIDNIGHT.equals(t) ? null : t;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        return null;
    }

    private static Integer readInt(Sheet ws, int r, Integer c) {
        if (c == null || c < 0) return null;
        Row row = ws.getRow(r);
        if (row == null) return null;
        Cell cell = row.getCell(c);
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) return (int) cell.getNumericCellValue();
            String s = cell.getStringCellValue().trim().replace(".", "").replace(",", "");
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Bỏ dấu + lowercase + gom khoảng trắng — để so khớp nhãn tiếng Việt. */
    private static String normalize(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replaceAll("\\s+", " ")
                .trim();
    }
}