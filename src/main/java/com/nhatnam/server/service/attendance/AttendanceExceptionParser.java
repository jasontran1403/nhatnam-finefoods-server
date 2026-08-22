package com.nhatnam.server.service.attendance;

import com.nhatnam.server.entity.AttendanceLeaveRequest.LeaveStatus;
import com.nhatnam.server.enumtype.AttendanceExceptionType;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ĐỌC 2 FILE NGOẠI LỆ CHẤM CÔNG do người dùng điền theo template:
 * <ul>
 *   <li>Lịch nghỉ / đi trễ / về sớm của CÔNG TY →
 *       {@link #parseExceptions(Workbook, int, java.util.List)}</li>
 *   <li>Đơn xin nghỉ CÁ NHÂN →
 *       {@link #parseLeaveRequests(Workbook, int, java.util.List)}</li>
 * </ul>
 *
 * <p>Cột được dò theo TÊN TIÊU ĐỀ chứ không theo vị trí cố định, nên người dùng
 * có thêm/bớt/đổi thứ tự cột vẫn đọc được, miễn giữ đúng tên cột.
 */
@Slf4j
public class AttendanceExceptionParser {

    /** Số dòng tối đa dò tìm hàng tiêu đề. */
    private static final int MAX_HEADER_SCAN = 20;

    // ══════════════════════════════════════════════════════════════════════════
    // KẾT QUẢ ĐỌC
    // ══════════════════════════════════════════════════════════════════════════

    /** 1 dòng trong file lịch nghỉ của công ty. */
    @Data @Builder
    public static class ExceptionRow {
        private int day;
        private AttendanceExceptionType type;
        private LocalTime timeMark;
        private String note;
        private int excelRow;          // để báo lỗi cho dễ tra
    }

    /** 1 dòng trong file đơn xin nghỉ cá nhân. */
    @Data @Builder
    public static class LeaveRow {
        private String employeeName;
        private int day;
        private AttendanceExceptionType type;
        private LocalTime timeMark;
        private LeaveStatus status;
        private String note;
        private int excelRow;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LỊCH NGHỈ CÔNG TY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Đọc file lịch nghỉ của công ty.
     * Cột cần có: <b>Ngày</b>, <b>Loại</b>. Tuỳ chọn: <b>Mốc thời gian</b>, <b>Ghi chú</b>.
     * Dòng có Loại để trống được bỏ qua (ngày làm việc bình thường).
     */
    public static List<ExceptionRow> parseExceptions(Workbook wb, int lastDayOfMonth, List<String> warnings) {
        Sheet ws = wb.getSheetAt(0);
        if (ws == null) { warnings.add("File không có sheet nào"); return List.of(); }

        DataFormatter fmt = new DataFormatter();
        Header h = findHeader(ws, fmt, new String[]{"ngay", "loai"});
        if (h == null) {
            warnings.add("Không tìm thấy hàng tiêu đề có cột \"Ngày\" và \"Loại\". "
                    + "Hãy tải file mẫu và điền vào đó.");
            return List.of();
        }

        List<ExceptionRow> out = new ArrayList<>();
        Map<Integer, Integer> seenDay = new LinkedHashMap<>();   // ngày → dòng excel đã dùng

        for (int r = h.row + 1; r <= ws.getLastRowNum(); r++) {
            String typeRaw = text(ws, r, h.col("loai"), fmt);
            if (typeRaw.isBlank()) continue;                     // ngày bình thường

            Integer day = readInt(ws, r, h.col("ngay"), fmt);
            if (day == null || day < 1 || day > lastDayOfMonth) {
                warnings.add("Dòng %d: ngày \"%s\" không hợp lệ (phải từ 1 đến %d)"
                        .formatted(r + 1, text(ws, r, h.col("ngay"), fmt), lastDayOfMonth));
                continue;
            }

            AttendanceExceptionType type = AttendanceExceptionType.fromLabel(typeRaw);
            if (type == null) {
                warnings.add("Dòng %d: loại \"%s\" không nhận diện được".formatted(r + 1, typeRaw));
                continue;
            }

            LocalTime mark = readTime(ws, r, h.col("moc"), fmt);
            if (type.isRequiresTime() && mark == null) {
                warnings.add("Dòng %d (ngày %d): loại \"%s\" bắt buộc phải có mốc thời gian"
                        .formatted(r + 1, day, type.getLabel()));
                continue;
            }
            if (!type.isRequiresTime()) mark = null;             // loại nghỉ thì bỏ mốc giờ

            Integer prev = seenDay.put(day, r + 1);
            if (prev != null) {
                warnings.add("Ngày %d bị khai báo nhiều lần (dòng %d và %d) — lấy dòng cuối"
                        .formatted(day, prev, r + 1));
                out.removeIf(x -> x.getDay() == day);
            }

            out.add(ExceptionRow.builder()
                    .day(day).type(type).timeMark(mark)
                    .note(text(ws, r, h.col("ghichu"), fmt))
                    .excelRow(r + 1)
                    .build());
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ĐƠN XIN NGHỈ CÁ NHÂN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Đọc file đơn xin nghỉ cá nhân.
     * Cột cần có: <b>Họ tên</b>, <b>Ngày</b>, <b>Loại</b>.
     * Tuỳ chọn: <b>Mốc thời gian</b>, <b>Trạng thái</b>, <b>Ghi chú</b>.
     * Không có cột Trạng thái thì mặc định là Chờ duyệt (chưa có hiệu lực).
     */
    public static List<LeaveRow> parseLeaveRequests(Workbook wb, int lastDayOfMonth, List<String> warnings) {
        Sheet ws = wb.getSheetAt(0);
        if (ws == null) { warnings.add("File không có sheet nào"); return List.of(); }

        DataFormatter fmt = new DataFormatter();
        Header h = findHeader(ws, fmt, new String[]{"hoten", "ngay", "loai"});
        if (h == null) {
            warnings.add("Không tìm thấy hàng tiêu đề có cột \"Họ tên\", \"Ngày\" và \"Loại\". "
                    + "Hãy tải file mẫu và điền vào đó.");
            return List.of();
        }

        List<LeaveRow> out = new ArrayList<>();
        for (int r = h.row + 1; r <= ws.getLastRowNum(); r++) {
            String name = text(ws, r, h.col("hoten"), fmt).trim();
            String typeRaw = text(ws, r, h.col("loai"), fmt);
            if (name.isBlank() && typeRaw.isBlank()) continue;   // dòng trống

            if (name.isBlank()) {
                warnings.add("Dòng %d: thiếu họ tên".formatted(r + 1));
                continue;
            }

            Integer day = readInt(ws, r, h.col("ngay"), fmt);
            if (day == null || day < 1 || day > lastDayOfMonth) {
                warnings.add("Dòng %d (%s): ngày không hợp lệ".formatted(r + 1, name));
                continue;
            }

            AttendanceExceptionType type = AttendanceExceptionType.fromLabel(typeRaw);
            if (type == null) {
                warnings.add("Dòng %d (%s): loại \"%s\" không nhận diện được"
                        .formatted(r + 1, name, typeRaw));
                continue;
            }

            LocalTime mark = readTime(ws, r, h.col("moc"), fmt);
            if (type.isRequiresTime() && mark == null) {
                warnings.add("Dòng %d (%s): loại \"%s\" bắt buộc phải có mốc thời gian"
                        .formatted(r + 1, name, type.getLabel()));
                continue;
            }
            if (!type.isRequiresTime()) mark = null;

            out.add(LeaveRow.builder()
                    .employeeName(name)
                    .day(day).type(type).timeMark(mark)
                    .status(LeaveStatus.fromLabel(text(ws, r, h.col("trangthai"), fmt)))
                    .note(text(ws, r, h.col("ghichu"), fmt))
                    .excelRow(r + 1)
                    .build());
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DÒ TIÊU ĐỀ
    // ══════════════════════════════════════════════════════════════════════════

    /** Vị trí các cột đã dò được trên hàng tiêu đề. */
    private record Header(int row, Map<String, Integer> cols) {
        int col(String key) { return cols.getOrDefault(key, -1); }
    }

    /**
     * Dò hàng tiêu đề chứa đủ các cột bắt buộc.
     * Tên cột được chuẩn hoá (bỏ dấu, bỏ khoảng trắng) rồi so khớp theo từ khoá,
     * nên "Họ tên", "HO TEN", "Họ và tên" đều nhận được.
     */
    private static Header findHeader(Sheet ws, DataFormatter fmt, String[] required) {
        for (int r = ws.getFirstRowNum(); r <= Math.min(ws.getLastRowNum(), MAX_HEADER_SCAN); r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;

            Map<String, Integer> cols = new LinkedHashMap<>();
            for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                String v = norm(fmt.formatCellValue(row.getCell(c)));
                if (v.isEmpty()) continue;

                if (v.startsWith("hoten") || v.startsWith("hovaten") || v.equals("ten") || v.startsWith("manv"))
                    cols.putIfAbsent("hoten", c);
                else if (v.startsWith("ngay"))                 cols.putIfAbsent("ngay", c);
                else if (v.startsWith("loai"))                 cols.putIfAbsent("loai", c);
                else if (v.startsWith("moc") || v.contains("thoigian"))
                    cols.putIfAbsent("moc", c);
                else if (v.startsWith("trangthai"))            cols.putIfAbsent("trangthai", c);
                else if (v.startsWith("ghichu"))               cols.putIfAbsent("ghichu", c);
            }

            boolean ok = true;
            for (String req : required) if (!cols.containsKey(req)) { ok = false; break; }
            if (ok) return new Header(r, cols);
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ĐỌC Ô
    // ══════════════════════════════════════════════════════════════════════════

    private static String text(Sheet ws, int r, int c, DataFormatter fmt) {
        if (c < 0) return "";
        Row row = ws.getRow(r);
        if (row == null) return "";
        Cell cell = row.getCell(c);
        return cell == null ? "" : fmt.formatCellValue(cell).trim();
    }

    private static Integer readInt(Sheet ws, int r, int c, DataFormatter fmt) {
        String s = text(ws, r, c, fmt);
        if (s.isEmpty()) return null;
        try {
            return (int) Double.parseDouble(s.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ĐỌC MỐC THỜI GIAN từ 1 ô.
     *
     * <p>Ô trong file mẫu được định dạng Text nên thường vào đây dưới dạng chuỗi.
     * Vẫn xử lý cả trường hợp ô là thời gian thật hoặc bị Excel đổi thành số,
     * phòng khi người dùng dán từ file khác sang.
     */
    private static LocalTime readTime(Sheet ws, int r, int c, DataFormatter fmt) {
        if (c < 0) return null;
        Row row = ws.getRow(r);
        if (row == null) return null;
        Cell cell = row.getCell(c);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            // Ô thời gian thật của Excel
            try {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalTime t = cell.getLocalDateTimeCellValue().toLocalTime();
                    return LocalTime.MIDNIGHT.equals(t) ? null : t;
                }
            } catch (Exception ignored) { }

            double d = cell.getNumericCellValue();
            // Phần lẻ của một ngày: 0.34097 ≈ 08:11
            if (d > 0 && d < 1) return LocalTime.ofSecondOfDay(Math.round(d * 86400));
            // Số nguyên kiểu HHMM hoặc chỉ có giờ → để parseTimeText xử lý chung
            if (d == Math.floor(d)) return parseTimeText(String.valueOf((long) d));
            // Có phần thập phân: 8.11 nghĩa là 8 giờ 11 phút (không phải 8,11 giờ)
            int hh = (int) Math.floor(d);
            int mm = (int) Math.round((d - hh) * 100);
            if (hh > 23 || mm > 59) return null;
            return LocalTime.of(hh, mm);
        }

        return parseTimeText(fmt.formatCellValue(cell));
    }

    /**
     * PHÂN TÍCH CHUỖI MỐC THỜI GIAN — chấp nhận mọi kiểu phân cách người dùng gõ.
     *
     * <pre>
     *   8:11 · 8,11 · 8.11 · 8"11 · 8'11 · 8g11 · 8h11 · 8H11 · 8 11
     *   08:11 · 0811 · 811                        → 08:11
     *
     *   14:11 · 14.11 · 14,11 · 14"11 · 14'11
     *   14g11 · 14h11 · 1411                      → 14:11
     *
     *   8 · 8h · 08 · 8:00 · 8g                   → 08:00
     * </pre>
     *
     * <p>Cách làm: bỏ hết ký tự không phải chữ số, gom các cụm số còn lại.
     * Nhờ vậy không cần liệt kê từng dấu phân cách, người dùng gõ dấu lạ vẫn hiểu.
     *
     * <p>Dùng giờ 24h — 2 giờ 11 phút chiều phải gõ là {@code 14:11}, không phải
     * {@code 2:11}. Chuỗi không hợp lệ (giờ > 23, phút > 59, không có số) trả
     * {@code null} để bên gọi ghi cảnh báo.
     */
    static LocalTime parseTimeText(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Tách thành các cụm chữ số, bỏ qua mọi ký tự ngăn cách
        String[] parts = s.split("[^0-9]+");
        List<String> nums = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) nums.add(p);
        if (nums.isEmpty()) return null;

        int hh, mm;
        try {
            if (nums.size() == 1) {
                String n = nums.get(0);
                switch (n.length()) {
                    case 1, 2 -> { hh = Integer.parseInt(n); mm = 0; }              // "8" · "08"
                    case 3    -> { hh = n.charAt(0) - '0';                          // "811"
                        mm = Integer.parseInt(n.substring(1)); }
                    case 4    -> { hh = Integer.parseInt(n.substring(0, 2));        // "0811" · "1411"
                        mm = Integer.parseInt(n.substring(2)); }
                    default   -> { return null; }
                }
            } else {
                hh = Integer.parseInt(nums.get(0));
                mm = Integer.parseInt(nums.get(1));
            }
        } catch (NumberFormatException e) {
            return null;
        }

        if (hh > 23 || mm > 59) return null;
        return LocalTime.of(hh, mm);
    }

    /** Bỏ dấu, bỏ khoảng trắng, lowercase — để so khớp tên cột. */
    private static String norm(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]", "");
    }
}