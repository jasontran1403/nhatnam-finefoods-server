package com.nhatnam.server.service;

import com.nhatnam.server.service.serviceimpl.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Xuất phiếu kiểm kho dạng Excel.
 *
 * Cột:
 *  1  STT
 *  2  Tên nguyên liệu
 *  3  Quy cách / Đơn vị tính  (wrap 2 dòng)
 *  4  SL Tồn
 *  5  Ngày sản xuất / Hạn sử dụng  (wrap 2 dòng)
 *  6  SL Thực tế  (ô trống để user nhập)
 *
 * Cuối bảng: placeholder ký xác nhận (Người kiểm kho | Quản lý kho)
 */
@Service
@RequiredArgsConstructor
public class WarehouseInventoryExportService {

    private static final DateTimeFormatter VN_DATETIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    // ─── public API ──────────────────────────────────────────────────────────

    /**
     * @param warehouseName  Tên kho
     * @param checkDateTime  Ngày giờ kiểm kho
     * @param items          Danh sách nguyên liệu (đã lọc theo danh mục FE chọn)
     */
    public byte[] generate(String warehouseName, LocalDateTime checkDateTime,
                           List<InventoryItem> items) throws IOException {

        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet    sh = wb.createSheet("Phiếu kiểm kho");

        sh.setDefaultColumnWidth(18);
        sh.setPrintGridlines(false);

        Styles s = new Styles(wb);

        int totalCols = 6;
        int rowIdx    = 0;

        // ── Row 0: Tiêu đề "PHIẾU KIỂM KHO" ─────────────────────────────────
        Row titleRow = sh.createRow(rowIdx++);
        titleRow.setHeightInPoints(52);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("PHIẾU KIỂM KHO");
        titleCell.setCellStyle(s.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols - 1));

        // ── Row 1: Kho hàng ──────────────────────────────────────────────────
        Row warehouseRow = sh.createRow(rowIdx++);
        warehouseRow.setHeightInPoints(30);
        Cell whCell = warehouseRow.createCell(0);
        whCell.setCellValue("Kho hàng: " + (warehouseName != null ? warehouseName : ""));
        whCell.setCellStyle(s.metaLeft);
        sh.addMergedRegion(new CellRangeAddress(1, 1, 0, totalCols - 1));

        // ── Row 2: Ngày kiểm kho ─────────────────────────────────────────────
        Row dateRow = sh.createRow(rowIdx++);
        dateRow.setHeightInPoints(30);
        Cell dateCell = dateRow.createCell(0);
        dateCell.setCellValue("Ngày kiểm kho: "
                + (checkDateTime != null ? checkDateTime.format(VN_DATETIME) : LocalDateTime.now().format(VN_DATETIME)));
        dateCell.setCellStyle(s.metaLeft);
        sh.addMergedRegion(new CellRangeAddress(2, 2, 0, totalCols - 1));

        // ── Row 3: spacer ────────────────────────────────────────────────────
        sh.createRow(rowIdx++).setHeightInPoints(10);

        // ── Row 4: Header bảng ───────────────────────────────────────────────
        int headerRowIdx = rowIdx;
        Row header = sh.createRow(rowIdx++);
        header.setHeightInPoints(56);

        String[] headers = {
                "STT",
                "Tên nguyên liệu",
                "ĐVT",
                "SL Tồn",
                "Hạn sử dụng",
                "SL Thực tế"
        };
        for (int c = 0; c < headers.length; c++) {
            Cell hc = header.createCell(c);
            hc.setCellValue(headers[c]);
            hc.setCellStyle(s.headerCell);
        }

        // ── Rows: dữ liệu — nhóm theo Danh mục cha › Danh mục con ──────────────
        //
        // Layout:
        //   [Danh mục cha]                (dòng merge, in đậm, canh trái)
        //     [Danh mục con]              (dòng merge, in nghiêng, canh trái) — nếu có
        //        nguyên liệu thuộc danh mục con ...
        //     nguyên liệu có danh mục cha nhưng CHƯA gán danh mục con ...
        //   [Chưa phân loại]              (nguyên liệu không có danh mục cha)
        //
        List<InventoryItem> safeItems = (items != null) ? items : List.of();

        // Gom theo danh mục cha, giữ thứ tự xuất hiện; nhóm "chưa phân loại" để cuối.
        LinkedHashMap<Long, List<InventoryItem>> byCategory = new LinkedHashMap<>();
        List<InventoryItem> noCategory = new ArrayList<>();
        for (InventoryItem it : safeItems) {
            if (it.categoryId() == null) noCategory.add(it);
            else byCategory.computeIfAbsent(it.categoryId(), k -> new ArrayList<>()).add(it);
        }

        Counter stt = new Counter(); // STT tăng theo nguyên liệu, không theo lô/tiêu đề

        // Duyệt từng danh mục cha
        for (Map.Entry<Long, List<InventoryItem>> catEntry : byCategory.entrySet()) {
            List<InventoryItem> catItems = catEntry.getValue();
            String catName = catItems.stream()
                    .map(InventoryItem::categoryName)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst()
                    .orElse("Danh mục #" + catEntry.getKey());

            // Dòng tiêu đề danh mục cha (in đậm)
            rowIdx = writeSectionRow(sh, rowIdx, totalCols, catName, s.catHeader, 26);

            // Trong danh mục cha: gom theo danh mục con (giữ thứ tự), phần chưa gán để cuối
            LinkedHashMap<Long, List<InventoryItem>> bySub = new LinkedHashMap<>();
            List<InventoryItem> noSub = new ArrayList<>();
            for (InventoryItem it : catItems) {
                if (it.subCategoryId() == null) noSub.add(it);
                else bySub.computeIfAbsent(it.subCategoryId(), k -> new ArrayList<>()).add(it);
            }

            // Các danh mục con + nguyên liệu bên trong
            for (Map.Entry<Long, List<InventoryItem>> subEntry : bySub.entrySet()) {
                List<InventoryItem> subItems = subEntry.getValue();
                String subName = subItems.stream()
                        .map(InventoryItem::subCategoryName)
                        .filter(n -> n != null && !n.isBlank())
                        .findFirst()
                        .orElse("Danh mục con #" + subEntry.getKey());

                // Dòng tiêu đề danh mục con (in nghiêng)
                rowIdx = writeSectionRow(sh, rowIdx, totalCols, "     " + subName, s.subHeader, 22);

                for (InventoryItem it : subItems) {
                    rowIdx = writeItemRows(sh, s, it, stt, rowIdx);
                }
            }

            // Nguyên liệu có danh mục cha nhưng chưa gán danh mục con
            for (InventoryItem it : noSub) {
                rowIdx = writeItemRows(sh, s, it, stt, rowIdx);
            }
        }

        // Nhóm chưa phân loại (không có danh mục cha) — để cuối cùng
        if (!noCategory.isEmpty()) {
            rowIdx = writeSectionRow(sh, rowIdx, totalCols, "Chưa phân loại", s.catHeader, 26);
            for (InventoryItem it : noCategory) {
                rowIdx = writeItemRows(sh, s, it, stt, rowIdx);
            }
        }

        // ── Row tổng / spacer ─────────────────────────────────────────────────
        sh.createRow(rowIdx++).setHeightInPoints(14);

        // ── Ký xác nhận ───────────────────────────────────────────────────────
        // "Người kiểm kho" bên trái, "Quản lý kho" bên phải
        Row signHeaderRow = sh.createRow(rowIdx++);
        signHeaderRow.setHeightInPoints(30);

        Cell signLeft = signHeaderRow.createCell(0);
        signLeft.setCellValue("Người kiểm kho");
        signLeft.setCellStyle(s.signLabel);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 2));

        Cell signRight = signHeaderRow.createCell(3);
        signRight.setCellValue("Quản lý kho");
        signRight.setCellStyle(s.signLabel);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 3, 5));

        // Ghi chú nhỏ
        Row signNoteRow = sh.createRow(rowIdx++);
        signNoteRow.setHeightInPoints(22);
        Cell noteLeft = signNoteRow.createCell(0);
        noteLeft.setCellValue("(Ký, ghi rõ họ tên)");
        noteLeft.setCellStyle(s.signNote);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 2));

        Cell noteRight = signNoteRow.createCell(3);
        noteRight.setCellValue("(Ký, ghi rõ họ tên)");
        noteRight.setCellStyle(s.signNote);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 3, 5));

        // Khoảng trống để ký
        for (int blank = 0; blank < 4; blank++) {
            Row blankRow = sh.createRow(rowIdx++);
            blankRow.setHeightInPoints(26);
        }

        // Dòng gạch tên
        Row nameLineRow = sh.createRow(rowIdx++);
        nameLineRow.setHeightInPoints(24);
        Cell nameLeft = nameLineRow.createCell(0);
        nameLeft.setCellValue("Họ tên: ___________________________");
        nameLeft.setCellStyle(s.signName);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 2));

        Cell nameRight = nameLineRow.createCell(3);
        nameRight.setCellValue("Họ tên: ___________________________");
        nameRight.setCellStyle(s.signName);
        sh.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 3, 5));

        // ── Column widths ─────────────────────────────────────────────────────
        sh.setColumnWidth(0,  6  * 256);   // STT
        sh.setColumnWidth(1,  32 * 256);   // Tên NL
        sh.setColumnWidth(2,  18 * 256);   // Quy cách / ĐVT
        sh.setColumnWidth(3,  12 * 256);   // SL Tồn
        sh.setColumnWidth(4,  28 * 256);   // NSX / HSD
        sh.setColumnWidth(5,  14 * 256);   // SL Thực tế

        // ── Freeze header ────────────────────────────────────────────────────
        sh.createFreezePane(0, headerRowIdx + 1);

        // ── Print setup ──────────────────────────────────────────────────────
        sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sh.getPrintSetup().setLandscape(true);
        sh.setFitToPage(true);
        sh.getPrintSetup().setFitWidth((short) 1);
        sh.getPrintSetup().setFitHeight((short) 0);

        // ── Số trang dạng "1/3", "2/3", "3/3" ──────────────────────────────
        // &P = số trang hiện tại, &N = tổng số trang
        sh.getFooter().setCenter("Trang &P/&N");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return bos.toByteArray();
    }

    // ─── Helpers render ────────────────────────────────────────────────────────

    /** Bộ đếm STT dùng chung xuyên suốt các nhóm danh mục. */
    private static final class Counter {
        int value = 0;
        int next() { return ++value; }
    }

    /**
     * Ghi 1 dòng tiêu đề (danh mục cha / danh mục con): merge toàn bộ chiều ngang,
     * canh trái. Style quyết định in đậm (danh mục cha) hay in nghiêng (danh mục con).
     *
     * @return chỉ số dòng kế tiếp
     */
    private int writeSectionRow(XSSFSheet sh, int rowIdx, int totalCols,
                                String text, XSSFCellStyle style, int heightPt) {
        Row r = sh.createRow(rowIdx);
        r.setHeightInPoints(heightPt);
        // Áp style cho tất cả các ô trong dải merge để viền/nền hiển thị đủ
        for (int c = 0; c < totalCols; c++) {
            Cell cell = r.createCell(c);
            if (c == 0) cell.setCellValue(text != null ? text : "");
            cell.setCellStyle(style);
        }
        sh.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, totalCols - 1));
        return rowIdx + 1;
    }

    /**
     * Ghi các dòng của 1 nguyên liệu (mỗi lô 1 dòng, merge STT/Tên/ĐVT nếu nhiều lô).
     *
     * @return chỉ số dòng kế tiếp
     */
    private int writeItemRows(XSSFSheet sh, Styles s, InventoryItem item,
                              Counter stt, int rowIdx) {
        int sttValue = stt.next();

        // Danh sách lô — nếu không có lô thì tạo 1 dòng trống
        List<ExpiryEntry> lots = (item.expiryList() != null && !item.expiryList().isEmpty())
                ? item.expiryList()
                : List.of(new ExpiryEntry(null, null, null));

        int lotCount    = lots.size();
        int firstRowIdx = rowIdx;

        // Kẻ sọc xen kẽ theo nguyên liệu (dựa trên STT) để dễ đọc
        boolean even = (sttValue % 2 == 1); // STT lẻ = hàng "even" (bắt đầu bằng nền nhạt)
        XSSFCellStyle rowBg   = even ? s.dataEven  : s.dataOdd;
        XSSFCellStyle numBg   = even ? s.numEven   : s.numOdd;
        XSSFCellStyle wrapBg  = even ? s.wrapEven  : s.wrapOdd;
        XSSFCellStyle inputBg = even ? s.inputEven : s.inputOdd;

        for (int l = 0; l < lotCount; l++) {
            ExpiryEntry lot = lots.get(l);
            Row row = sh.createRow(rowIdx++);
            row.setHeightInPoints(38);

            // Col 0: STT — chỉ ghi ở dòng đầu (sẽ merge nếu nhiều lô)
            Cell sttCell = row.createCell(0);
            if (l == 0) sttCell.setCellValue(sttValue);
            else        sttCell.setCellValue("");
            sttCell.setCellStyle(numBg);

            // Col 1: Tên nguyên liệu — chỉ ghi ở dòng đầu
            Cell nameCell = row.createCell(1);
            if (l == 0) nameCell.setCellValue(item.ingredientName());
            else        nameCell.setCellValue("");
            nameCell.setCellStyle(rowBg);

            // Col 2: ĐVT — chỉ ghi ở dòng đầu
            Cell unitCell = row.createCell(2);
            if (l == 0) unitCell.setCellValue(item.unit() != null ? item.unit() : "");
            else        unitCell.setCellValue("");
            unitCell.setCellStyle(wrapBg);

            // Col 3: SL lô này (không phải tổng tồn)
            Cell qtyCell = row.createCell(3);
            if (lot.quantity() != null) {
                qtyCell.setCellValue(lot.quantity());
            } else if (l == 0 && item.stockQuantity() != null) {
                qtyCell.setCellValue(item.stockQuantity());
            } else {
                qtyCell.setCellValue("");
            }
            qtyCell.setCellStyle(numBg);

            // Col 4: Hạn sử dụng của lô này
            Cell expiryCell = row.createCell(4);
            expiryCell.setCellValue(lot.expiryDate() != null ? lot.expiryDate() : "—");
            expiryCell.setCellStyle(wrapBg);

            // Col 5: SL Thực tế — để trống cho từng lô
            Cell actualCell = row.createCell(5);
            actualCell.setCellValue("");
            actualCell.setCellStyle(inputBg);
        }

        // Merge STT / Tên / ĐVT theo chiều dọc nếu có nhiều lô
        if (lotCount > 1) {
            int lastRowIdx = rowIdx - 1;
            sh.addMergedRegion(new CellRangeAddress(firstRowIdx, lastRowIdx, 0, 0)); // STT
            sh.addMergedRegion(new CellRangeAddress(firstRowIdx, lastRowIdx, 1, 1)); // Tên
            sh.addMergedRegion(new CellRangeAddress(firstRowIdx, lastRowIdx, 2, 2)); // ĐVT
        }

        return rowIdx;
    }

    // ─── DTOs ────────────────────────────────────────────────────────────────

    public record InventoryItem(
            String ingredientName,
            String spec,          // quy cách (VD: "Hộp 24 gói")
            String unit,          // đơn vị tính (VD: "Hộp")
            Double stockQuantity,
            List<ExpiryEntry> expiryList,
            Long   categoryId,       // danh mục cha — null nếu chưa phân loại
            String categoryName,     // tên danh mục cha
            Long   subCategoryId,    // danh mục con — null nếu chưa gán
            String subCategoryName   // tên danh mục con
    ) {
        // Constructor tương thích ngược (không có danh mục)
        public InventoryItem(String ingredientName, String spec, String unit,
                             Double stockQuantity, List<ExpiryEntry> expiryList) {
            this(ingredientName, spec, unit, stockQuantity, expiryList,
                    null, null, null, null);
        }
    }

    public record ExpiryEntry(
            String manufacturingDate,  // "hh:mm dd/MM/yyyy" hoặc null
            String expiryDate,         // "hh:mm dd/MM/yyyy" hoặc null
            Double quantity            // SL của lô này
    ) {}

    // ─── Styles ───────────────────────────────────────────────────────────────

    private static class Styles {

        // Header
        final XSSFCellStyle title, metaLeft;
        final XSSFCellStyle headerCell;
        // Tiêu đề nhóm (danh mục cha / danh mục con)
        final XSSFCellStyle catHeader, subHeader;
        // Data — even / odd
        final XSSFCellStyle dataEven, dataOdd;
        final XSSFCellStyle numEven,  numOdd;
        final XSSFCellStyle wrapEven, wrapOdd;
        final XSSFCellStyle inputEven, inputOdd;
        // Ký tên
        final XSSFCellStyle signLabel, signNote, signName;

        // Palette
        private static final String C_NAVY    = "1A3C6E";
        private static final String C_BLUE    = "2E75B6";
        private static final String C_ACCENT  = "D6E4F0"; // xanh nhạt even
        private static final String C_WHITE   = "FFFFFF";
        private static final String C_INPUT   = "FFFDE7"; // vàng nhạt — cột nhập
        private static final String C_INPUT2  = "FFFBCC";
        private static final String C_MUTED   = "5C5C5C";
        private static final String C_GRAY    = "F5F5F5";

        Styles(XSSFWorkbook wb) {

            // ── Fonts ──────────────────────────────────────────────────────
            XSSFFont fTitle  = fnt(wb, 26, true,  C_WHITE, false);
            XSSFFont fMeta   = fnt(wb, 16, false, C_NAVY,  false);
            XSSFFont fHdr    = fnt(wb, 16, true,  C_WHITE, false);
            XSSFFont fData   = fnt(wb, 14, false, "1C1C1E",false);
            XSSFFont fNum    = fnt(wb, 14, true,  C_NAVY,  false);
            XSSFFont fInput  = fnt(wb, 14, false, "33691E",false);
            XSSFFont fSign   = fnt(wb, 14, true,  C_NAVY,  false);
            XSSFFont fNote   = fnt(wb, 12, false, C_MUTED, true);
            XSSFFont fLine   = fnt(wb, 13, false, C_MUTED, false);

            // ── Title ──────────────────────────────────────────────────────
            title = wb.createCellStyle();
            title.setFont(fTitle);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);
            title.setFillForegroundColor(new XSSFColor(hex(C_NAVY), null));
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // ── Meta (kho hàng / ngày) ──────────────────────────────────
            metaLeft = wb.createCellStyle();
            metaLeft.setFont(fMeta);
            metaLeft.setAlignment(HorizontalAlignment.LEFT);
            metaLeft.setVerticalAlignment(VerticalAlignment.CENTER);
            metaLeft.setFillForegroundColor(new XSSFColor(hex("EBF3FB"), null));
            metaLeft.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            metaLeft.setLeftBorderColor(new XSSFColor(hex(C_NAVY), null));
            metaLeft.setBorderLeft(BorderStyle.THICK);

            // ── Header bảng ─────────────────────────────────────────────
            headerCell = wb.createCellStyle();
            headerCell.setFont(fHdr);
            headerCell.setFillForegroundColor(new XSSFColor(hex(C_NAVY), null));
            headerCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerCell.setAlignment(HorizontalAlignment.CENTER);
            headerCell.setVerticalAlignment(VerticalAlignment.CENTER);
            headerCell.setWrapText(true);
            border(headerCell, BorderStyle.MEDIUM, C_BLUE);

            // ── Tiêu đề danh mục cha (in đậm, canh trái, dải nền xanh đậm) ──
            XSSFFont fCat = fnt(wb, 15, true, C_WHITE, false);
            catHeader = wb.createCellStyle();
            catHeader.setFont(fCat);
            catHeader.setAlignment(HorizontalAlignment.LEFT);
            catHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            catHeader.setFillForegroundColor(new XSSFColor(hex(C_NAVY), null));
            catHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            catHeader.setIndention((short) 1);
            border(catHeader, BorderStyle.MEDIUM, C_NAVY);

            // ── Tiêu đề danh mục con (in nghiêng, canh trái, dải nền xanh nhạt) ──
            XSSFFont fSub = fnt(wb, 14, true, C_NAVY, true); // bold + italic cho nổi
            subHeader = wb.createCellStyle();
            subHeader.setFont(fSub);
            subHeader.setAlignment(HorizontalAlignment.LEFT);
            subHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            subHeader.setFillForegroundColor(new XSSFColor(hex(C_ACCENT), null));
            subHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subHeader.setIndention((short) 2);
            border(subHeader, BorderStyle.THIN, C_BLUE);

            // ── Data rows ────────────────────────────────────────────────
            dataEven = dataStyle(wb, fData, C_ACCENT, false, false);
            dataOdd  = dataStyle(wb, fData, C_WHITE,  false, false);
            numEven  = dataStyle(wb, fNum,  C_ACCENT, true,  false);
            numOdd   = dataStyle(wb, fNum,  C_WHITE,  true,  false);
            wrapEven = dataStyle(wb, fData, C_ACCENT, false, true);
            wrapOdd  = dataStyle(wb, fData, C_WHITE,  false, true);
            inputEven= dataStyle(wb, fInput,C_INPUT,  true,  false);
            inputOdd = dataStyle(wb, fInput,C_INPUT2, true,  false);

            // Thêm đường border bên trái đậm cho cột SL Thực tế để phân biệt
            inputEven.setBorderLeft(BorderStyle.MEDIUM);
            inputEven.setLeftBorderColor(new XSSFColor(hex(C_NAVY), null));
            inputOdd.setBorderLeft(BorderStyle.MEDIUM);
            inputOdd.setLeftBorderColor(new XSSFColor(hex(C_NAVY), null));

            // ── Ký tên ───────────────────────────────────────────────────
            signLabel = wb.createCellStyle();
            signLabel.setFont(fSign);
            signLabel.setAlignment(HorizontalAlignment.CENTER);
            signLabel.setVerticalAlignment(VerticalAlignment.CENTER);

            signNote = wb.createCellStyle();
            signNote.setFont(fNote);
            signNote.setAlignment(HorizontalAlignment.CENTER);
            signNote.setVerticalAlignment(VerticalAlignment.CENTER);

            signName = wb.createCellStyle();
            signName.setFont(fLine);
            signName.setAlignment(HorizontalAlignment.CENTER);
            signName.setVerticalAlignment(VerticalAlignment.CENTER);
        }

        private XSSFCellStyle dataStyle(XSSFWorkbook wb, XSSFFont font,
                                        String bgHex, boolean centerAlign, boolean wrap) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(new XSSFColor(hex(bgHex), null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setAlignment(centerAlign ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setWrapText(wrap);
            border(st, BorderStyle.THIN, "BDBDBD");
            return st;
        }

        private static void border(XSSFCellStyle st, BorderStyle bs, String hexColor) {
            XSSFColor c = new XSSFColor(hex(hexColor), null);
            st.setBorderTop(bs);    st.setTopBorderColor(c);
            st.setBorderBottom(bs); st.setBottomBorderColor(c);
            st.setBorderLeft(bs);   st.setLeftBorderColor(c);
            st.setBorderRight(bs);  st.setRightBorderColor(c);
        }

        private static XSSFFont fnt(XSSFWorkbook wb, int pt, boolean bold,
                                    String hexColor, boolean italic) {
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) pt);
            f.setBold(bold);
            f.setItalic(italic);
            f.setColor(new XSSFColor(hex(hexColor), null));
            return f;
        }

        private static byte[] hex(String h) {
            return new byte[]{
                    (byte) Integer.parseInt(h.substring(0, 2), 16),
                    (byte) Integer.parseInt(h.substring(2, 4), 16),
                    (byte) Integer.parseInt(h.substring(4, 6), 16)
            };
        }
    }
}