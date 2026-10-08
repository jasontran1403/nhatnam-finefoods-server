package com.nhatnam.server.service.hr;

import com.nhatnam.server.dto.hr.HolidayDtos.*;
import com.nhatnam.server.entity.Holiday;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CRUD + import Excel cho {@link Holiday}.
 *
 * <p>Phase 1 (10/2026): thay thế toàn bộ hardcode ngày lễ bằng dữ liệu động.
 * File Excel chỉ có 2 cột: {@code Ngày (dd/MM/yyyy)} và {@code Tên ngày lễ}.
 *
 * <h3>Match ngày</h3>
 * Parse theo thứ tự: ô Excel kiểu Date native → dd/MM/yyyy → yyyy-MM-dd → d/M/yyyy.
 * Hàng nào không parse được sẽ bị bỏ qua và được ghi vào trường {@code errors}
 * của kết quả import — không fail cả file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepo;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    // ── ĐỌC ───────────────────────────────────────────────────────────────────

    /** Toàn bộ ngày lễ của 1 năm (xếp tăng dần). */
    public List<HolidayDto> listByYear(int year) {
        return holidayRepo.findByYearOrderByDateAsc(year).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Ngày lễ trong 1 tháng — service tính lương sẽ gọi cái này.
     *
     * <p>FIX (10/2026): nếu bảng {@code holiday} trong DB chưa có record cho
     * tháng này, fallback về {@link com.nhatnam.server.utils.VietnameseHolidays}
     * (hiện có 1/9 và 2/9/2026). Trước đây DB rỗng → trả Set rỗng → trang
     * Chuyên cần không tô xanh ngày lễ, và lương không trừ ngày lễ ra khỏi
     * phụ cấp cơm → sai số. Có bất kỳ dòng nào trong DB cho tháng này thì
     * tôn trọng DB (OWNER có thể đã khai lịch nghỉ chính thức).
     */
    public Set<LocalDate> datesOfMonth(int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        Set<LocalDate> s = new LinkedHashSet<>();
        for (Holiday h : holidayRepo.findInRange(from, to)) {
            s.add(h.getDate());
        }
        if (!s.isEmpty()) return s;

        // Fallback khi DB chưa khai: lấy từ VietnameseHolidays để Chuyên cần
        // vẫn tô màu đúng và lương vẫn chốt đúng cho các ngày lễ cố định.
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = LocalDate.of(year, month, d);
            if (com.nhatnam.server.utils.VietnameseHolidays.isHoliday(date)) s.add(date);
        }
        return s;
    }

    /** Trả object {@link Holiday} đầy đủ cho các màn hình cần hiển thị tên. */
    public List<Holiday> entitiesOfMonth(int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        return holidayRepo.findInRange(ym.atDay(1), ym.atEndOfMonth());
    }

    // ── GHI ───────────────────────────────────────────────────────────────────

    @Transactional
    public HolidayDto create(CreateHolidayRequest req, User actor) {
        if (req.getDate() == null) throw new IllegalArgumentException("Thiếu ngày lễ.");
        Holiday h = holidayRepo.findByDate(req.getDate())
                .orElseGet(() -> Holiday.builder()
                        .date(req.getDate())
                        .year(req.getDate().getYear())
                        .createdAt(System.currentTimeMillis())
                        .createdByName(actor != null ? actor.getFullName() : null)
                        .build());
        h.setName(req.getName());
        h.setYear(req.getDate().getYear());
        return toDto(holidayRepo.save(h));
    }

    @Transactional
    public void delete(Long id) {
        holidayRepo.deleteById(id);
    }

    @Transactional
    public long deleteYear(int year) {
        return holidayRepo.deleteByYear(year);
    }

    // ── IMPORT EXCEL ──────────────────────────────────────────────────────────

    /**
     * Đọc file Excel .xlsx. Hàng 1 là header, dữ liệu bắt đầu từ hàng 2.
     *
     * @param replaceYear Nếu không null, xoá sạch ngày lễ của năm đó trước khi import.
     *                    Dùng khi OWNER muốn "nhập lại từ đầu" cho 1 năm.
     */
    @Transactional
    public ImportHolidayResult importExcel(MultipartFile file, Integer replaceYear, User actor) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn file.");

        if (replaceYear != null) {
            long removed = holidayRepo.deleteByYear(replaceYear);
            log.info("[Holiday] Xoá {} ngày lễ của năm {} trước khi import", removed, replaceYear);
        }

        List<String> errors = new ArrayList<>();
        List<HolidayDto> saved = new ArrayList<>();
        int total = 0, skipped = 0;

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getBytes()))) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IllegalArgumentException("File rỗng.");

            int last = sheet.getLastRowNum();
            for (int r = 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                total++;

                LocalDate date = readDate(row.getCell(0));
                if (date == null) {
                    skipped++;
                    errors.add("Dòng " + (r + 1) + ": không đọc được ngày.");
                    continue;
                }
                String name = readString(row.getCell(1));

                Holiday h = holidayRepo.findByDate(date).orElseGet(() -> Holiday.builder()
                        .date(date)
                        .year(date.getYear())
                        .createdAt(System.currentTimeMillis())
                        .createdByName(actor != null ? actor.getFullName() : null)
                        .build());
                h.setName(name);
                h.setYear(date.getYear());
                saved.add(toDto(holidayRepo.save(h)));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Holiday] Lỗi đọc file", e);
            throw new IllegalArgumentException("Không đọc được file Excel: " + e.getMessage());
        }

        return ImportHolidayResult.builder()
                .totalRows(total)
                .saved(saved.size())
                .skipped(skipped)
                .items(saved)
                .errors(errors)
                .build();
    }

    /**
     * Tạo file template Excel 2 cột để OWNER tải về điền.
     * Dữ liệu rỗng — chỉ có header và 10 dòng trống làm mẫu.
     */
    public byte[] buildTemplate(int year) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Ngày lễ " + year);

            // Header style — in đậm
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            Row header = sh.createRow(0);
            Cell c0 = header.createCell(0); c0.setCellValue("Ngày (dd/MM/yyyy)"); c0.setCellStyle(headerStyle);
            Cell c1 = header.createCell(1); c1.setCellValue("Tên ngày lễ");       c1.setCellStyle(headerStyle);

            sh.setColumnWidth(0, 20 * 256);
            sh.setColumnWidth(1, 40 * 256);

            // Ví dụ 2 dòng dữ liệu
            Row ex1 = sh.createRow(1);
            ex1.createCell(0).setCellValue("01/01/" + year);
            ex1.createCell(1).setCellValue("Tết Dương lịch");

            Row ex2 = sh.createRow(2);
            ex2.createCell(0).setCellValue("02/09/" + year);
            ex2.createCell(1).setCellValue("Quốc khánh");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private LocalDate readDate(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String s = readString(cell);
            if (s == null || s.isBlank()) return null;
            s = s.trim();
            for (DateTimeFormatter f : DATE_FORMATS) {
                try { return LocalDate.parse(s, f); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String readString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield DateTimeFormatter.ofPattern("dd/MM/yyyy")
                            .format(cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
                }
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> null;
        };
    }

    private HolidayDto toDto(Holiday h) {
        return HolidayDto.builder()
                .id(h.getId())
                .date(h.getDate())
                .year(h.getYear())
                .name(h.getName())
                .createdAt(h.getCreatedAt())
                .createdByName(h.getCreatedByName())
                .build();
    }
}