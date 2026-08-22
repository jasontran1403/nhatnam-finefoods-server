package com.nhatnam.server.service;

import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerProductReportService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final Set<String> ALLOWED_CATEGORY_NAMES = Set.of(
            "Non-Dairy Creams",
            "Non-Food"
    );

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductCategoryResolver categoryResolver;
    private final com.nhatnam.server.repository.ProductRepository productRepository;
    private final com.nhatnam.server.repository.CategoryRepository categoryRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public byte[] generateReport(LocalDate from, LocalDate to, Long sellerId) throws IOException {
        return generateReport(from, to, sellerId, null);
    }

    /**
     * @param categoryId nếu != null → chỉ tính sản phẩm thuộc danh mục này;
     *                   nếu null → dùng danh mục mặc định (ProductCategoryResolver).
     */
    public byte[] generateReport(LocalDate from, LocalDate to, Long sellerId, Long categoryId) throws IOException {
        // Nhãn danh mục + tập sản phẩm cho phép
        String categoryLabel = "Mặc định (Non-Dairy Creams, Non-Food)";
        java.util.Set<Long> allowed = null;
        if (categoryId != null) {
            String catName = categoryRepository.findById(categoryId)
                    .map(com.nhatnam.server.entity.Category::getName).orElse(null);
            categoryLabel = catName != null ? catName : ("Danh mục #" + categoryId);
            // Khớp theo ID hoặc theo TÊN (sản phẩm có thể chỉ lưu tên danh mục)
            allowed = new java.util.HashSet<>(
                    productRepository.findIdsByCategoryIdOrName(categoryId, catName != null ? catName : ""));
        }
        final java.util.Set<Long> allowedProductIds = allowed;
        final String catLabel = categoryLabel;
        long fromMs = from.atStartOfDay(VN).toInstant().toEpochMilli();
        long toMs   = to.plusDays(1).atStartOfDay(VN).toInstant().toEpochMilli() - 1;

        // Luôn load ALL customers để detect deleted — không filter theo seller
        Map<Long, Long> customerDeletedAtMap = customerRepository.findAll()
                .stream()
                .collect(Collectors.toMap(
                        Customer::getId,
                        c -> c.getDeletedAt() != null ? c.getDeletedAt() : 0L
                ));

        List<Order> orders = orderRepository.findByCreatedAtBetween(fromMs, toMs)
                .stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED)
                .filter(o -> sellerId == null || (o.getUser() != null && sellerId.equals(o.getUser().getId())))
                .collect(Collectors.toList());

        ReportData data = aggregate(orders, customerDeletedAtMap, allowedProductIds);
        return renderExcel(data, from, to, catLabel);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AGGREGATION
    // ─────────────────────────────────────────────────────────────────────────

    private ReportData aggregate(List<Order> orders, Map<Long, Long> customerDeletedAtMap,
                                 java.util.Set<Long> allowedProductIds) {

        // productId -> productName (giữ thứ tự xuất hiện đầu tiên)
        LinkedHashMap<Long, String> productNames = new LinkedHashMap<>();

        // customerKey -> CustomerRow
        LinkedHashMap<String, CustomerRow> rows = new LinkedHashMap<>();

        for (Order order : orders) {
            if (order.getOrderItems() == null) continue;

            String customerKey  = buildCustomerKey(order);
            String customerName = buildCustomerName(order);

            // Xác định khách đã xóa chưa
            boolean isDeleted = false;
            if (order.getCustomer() != null) {
                Long deletedAt = customerDeletedAtMap.getOrDefault(order.getCustomer().getId(), 0L);
                isDeleted = deletedAt != null && deletedAt > 0;
            }

            final boolean deleted = isDeleted;
            CustomerRow row = rows.computeIfAbsent(customerKey,
                    k -> new CustomerRow(customerName, deleted));

            for (OrderItem item : order.getOrderItems()) {

                // ── Kiểm tra danh mục cho phép ──
                if (!isAllowedProduct(item, allowedProductIds)) continue;

                Long   pid   = item.getProductId();
                String pname = item.getProductName();
                productNames.putIfAbsent(pid, pname);

                // ── Tính số lượng (theo quy cách) ──
                BigDecimal qty = calcQuantity(item);
                row.productQty.merge(pid, qty, BigDecimal::add);
                row.totalQty = row.totalQty.add(qty);

                // ── Tính tiền (sau thuế, không giảm giá, không phụ phí) ──
                BigDecimal lineTotal = calcLineTotalWithVat(item);
                row.totalAmount = row.totalAmount.add(lineTotal);
            }
        }

        // Loại bỏ khách hàng không có sản phẩm nào thuộc danh mục cho phép
        rows.entrySet().removeIf(e -> e.getValue().totalQty.compareTo(BigDecimal.ZERO) == 0);

        return new ReportData(productNames, new ArrayList<>(rows.values()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXCEL RENDERING
    // ─────────────────────────────────────────────────────────────────────────

    private byte[] renderExcel(ReportData data, LocalDate from, LocalDate to, String categoryLabel) throws IOException {

        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet    sh = wb.createSheet("Báo cáo KH × SP");

        // ── Styles ──
        Styles s = new Styles(wb);

        List<Long>   productIds    = new ArrayList<>(data.productNames.keySet());
        List<String> productLabels = productIds.stream()
                .map(data.productNames::get)
                .toList();

        // KH | Tổng SL | [SP × n] | Tổng tiền
        int totalCols = 3 + productIds.size();

        // ── Row 0: Tiêu đề báo cáo ──
        Row titleRow = sh.createRow(0);
        titleRow.setHeight((short) 800);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BÁO CÁO TIÊU THỤ THEO KHÁCH HÀNG & SẢN PHẨM");
        titleCell.setCellStyle(s.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols - 1));

        // ── Row 1: Khoảng thời gian ──
        Row subRow = sh.createRow(1);
        subRow.setHeight((short) 500);
        Cell subCell = subRow.createCell(0);
        subCell.setCellValue("Từ ngày: " + from + "   →   Đến ngày: " + to
                + "     |     Danh mục: " + categoryLabel);
        subCell.setCellStyle(s.subtitle);
        sh.addMergedRegion(new CellRangeAddress(1, 1, 0, totalCols - 1));

        // ── Row 2: Ghi chú highlight ──
        Row noteRow = sh.createRow(2);
        noteRow.setHeight((short) 380);
        Cell noteCell = noteRow.createCell(0);
        noteCell.setCellValue("⚠ Hàng nền cam = khách hàng đã bị xóa khỏi hệ thống");
        noteCell.setCellStyle(s.note);
        sh.addMergedRegion(new CellRangeAddress(2, 2, 0, totalCols - 1));

        // ── Row 3: Header ──
        int headerRowIdx = 3;
        Row header = sh.createRow(headerRowIdx);
        header.setHeight((short) 700);

        createHeaderCell(header, 0, "Khách Hàng",       s.header);
        createHeaderCell(header, 1, "Tổng SL",           s.headerCenter);
        for (int i = 0; i < productLabels.size(); i++) {
            createHeaderCell(header, 2 + i, productLabels.get(i), s.headerProduct);
        }
        createHeaderCell(header, 2 + productIds.size(), "Tổng Tiền (VNĐ)", s.headerMoney);

        // ── Rows: dữ liệu ──
        int dataStartRow = headerRowIdx + 1;
        for (int r = 0; r < data.rows.size(); r++) {
            CustomerRow cr  = data.rows.get(r);
            Row         row = sh.createRow(dataStartRow + r);
            row.setHeight((short) 420);

            boolean del = cr.deleted;

            // Chọn bộ style tương ứng
            XSSFCellStyle rowStyle = del
                    ? ((r % 2 == 0) ? s.dataDelEven : s.dataDelOdd)
                    : ((r % 2 == 0) ? s.dataEven    : s.dataOdd);
            XSSFCellStyle numStyle = del
                    ? ((r % 2 == 0) ? s.numDelEven  : s.numDelOdd)
                    : ((r % 2 == 0) ? s.numEven     : s.numOdd);
            XSSFCellStyle monStyle = del
                    ? ((r % 2 == 0) ? s.monDelEven  : s.monDelOdd)
                    : ((r % 2 == 0) ? s.monEven     : s.monOdd);

            // Col 0: Tên KH (thêm dấu hiệu nếu đã xóa)
            Cell nameCell = row.createCell(0);
            nameCell.setCellValue(del ? cr.customerName + "  ⚠ (đã xóa)" : cr.customerName);
            nameCell.setCellStyle(rowStyle);

            // Col 1: Tổng SL
            Cell totalQtyCell = row.createCell(1);
            totalQtyCell.setCellValue(cr.totalQty.doubleValue());
            totalQtyCell.setCellStyle(numStyle);

            // Col 2..n+1: từng sản phẩm
            for (int i = 0; i < productIds.size(); i++) {
                BigDecimal qty = cr.productQty.getOrDefault(productIds.get(i), BigDecimal.ZERO);
                Cell c = row.createCell(2 + i);
                if (qty.compareTo(BigDecimal.ZERO) == 0) {
                    c.setCellValue("-");
                } else {
                    c.setCellValue(qty.doubleValue());
                }
                c.setCellStyle(numStyle);
            }

            // Col cuối: Tổng tiền
            Cell amtCell = row.createCell(2 + productIds.size());
            amtCell.setCellValue(cr.totalAmount.setScale(0, RoundingMode.HALF_UP).doubleValue());
            amtCell.setCellStyle(monStyle);
        }

        // ── Row tổng cộng ──
        int totalRowIdx = dataStartRow + data.rows.size();
        Row sumRow = sh.createRow(totalRowIdx);
        sumRow.setHeight((short) 500);

        Cell sumLabel = sumRow.createCell(0);
        sumLabel.setCellValue("TỔNG CỘNG");
        sumLabel.setCellStyle(s.totalLabel);

        boolean hasRows = !data.rows.isEmpty();
        int firstDataRow = dataStartRow + 1;      // 1-based
        int lastDataRow  = totalRowIdx;           // 1-based (dataStartRow + n)

        // Tổng SL
        Cell sumQty = sumRow.createCell(1);
        if (hasRows) sumQty.setCellFormula("SUM(B" + firstDataRow + ":B" + lastDataRow + ")");
        else sumQty.setCellValue(0);
        sumQty.setCellStyle(s.totalNum);

        // Tổng từng sản phẩm
        for (int i = 0; i < productIds.size(); i++) {
            int col = 2 + i;
            String colLetter = getCellColumnLetter(col);
            Cell sc = sumRow.createCell(col);
            if (hasRows) sc.setCellFormula("SUM(" + colLetter + firstDataRow + ":" + colLetter + lastDataRow + ")");
            else sc.setCellValue(0);
            sc.setCellStyle(s.totalNum);
        }

        // Tổng tiền
        int    lastCol       = 2 + productIds.size();
        String lastColLetter = getCellColumnLetter(lastCol);
        Cell sumAmt = sumRow.createCell(lastCol);
        if (hasRows) sumAmt.setCellFormula("SUM(" + lastColLetter + firstDataRow + ":" + lastColLetter + lastDataRow + ")");
        else sumAmt.setCellValue(0);
        sumAmt.setCellStyle(s.totalMoney);

        // ── Column widths ──
        sh.setColumnWidth(0, 36 * 256);  // Tên KH (rộng hơn vì có suffix "⚠ (đã xóa)")
        sh.setColumnWidth(1, 12 * 256);  // Tổng SL
        for (int i = 0; i < productIds.size(); i++) {
            int len = Math.max(productLabels.get(i).length(), 6);
            sh.setColumnWidth(2 + i, Math.min(len + 4, 28) * 256);
        }
        sh.setColumnWidth(2 + productIds.size(), 22 * 256); // Tổng tiền

        // ── Freeze panes: cố định header ──
        sh.createFreezePane(0, headerRowIdx + 1);

        // ── Auto filter ──
        sh.setAutoFilter(new CellRangeAddress(
                headerRowIdx, headerRowIdx, 0, totalCols - 1));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return bos.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String buildCustomerKey(Order order) {
        if (order.getCustomer() != null) return "cid_" + order.getCustomer().getId();
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank())
            return "ph_" + order.getCustomerPhone().trim();
        return "nm_" + order.getCustomerName();
    }

    private String buildCustomerName(Order order) {
        if (order.getCompanyName() != null && !order.getCompanyName().isBlank())
            return order.getCompanyName();
        if (order.getCustomerName() != null && !order.getCustomerName().isBlank())
            return order.getCustomerName();
        return "(Không rõ)";
    }

    private boolean isAllowedProduct(OrderItem item, java.util.Set<Long> allowedProductIds) {
        if (allowedProductIds != null) return allowedProductIds.contains(item.getProductId());
        return categoryResolver.isAllowed(item.getProductId());
    }

    private BigDecimal calcQuantity(OrderItem item) {
        if ("BOX".equalsIgnoreCase(item.getSaleType())
                && item.getUnitsPerBox() != null
                && item.getUnitsPerBox() > 0) {
            return item.getQuantity()
                    .multiply(BigDecimal.valueOf(item.getUnitsPerBox()));
        }
        return item.getQuantity();
    }

    private BigDecimal calcLineTotalWithVat(OrderItem item) {
        BigDecimal base = item.getSubtotal() == null ? BigDecimal.ZERO : item.getSubtotal();
        if ("EXCLUSIVE".equalsIgnoreCase(item.getVatMode())) {
            BigDecimal vat = item.getVatAmount() == null ? BigDecimal.ZERO : item.getVatAmount();
            return base.add(vat);
        }
        return base;
    }

    private void createHeaderCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private String getCellColumnLetter(int colIndex) {
        StringBuilder sb = new StringBuilder();
        int col = colIndex;
        while (col >= 0) {
            sb.insert(0, (char) ('A' + col % 26));
            col = col / 26 - 1;
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INNER TYPES
    // ─────────────────────────────────────────────────────────────────────────

    private static class ReportData {
        final LinkedHashMap<Long, String> productNames;
        final List<CustomerRow>           rows;

        ReportData(LinkedHashMap<Long, String> productNames, List<CustomerRow> rows) {
            this.productNames = productNames;
            this.rows         = rows;
        }
    }

    private static class CustomerRow {
        final String  customerName;
        final boolean deleted;
        BigDecimal totalQty    = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        final Map<Long, BigDecimal> productQty = new LinkedHashMap<>();

        CustomerRow(String name, boolean deleted) {
            this.customerName = name;
            this.deleted      = deleted;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STYLES
    // ─────────────────────────────────────────────────────────────────────────

    private static class Styles {

        // ── Bình thường ──
        final XSSFCellStyle title, subtitle, note;
        final XSSFCellStyle header, headerCenter, headerProduct, headerMoney;
        final XSSFCellStyle dataEven,   dataOdd;
        final XSSFCellStyle numEven,    numOdd;
        final XSSFCellStyle monEven,    monOdd;
        // ── Khách đã xóa (cam) ──
        final XSSFCellStyle dataDelEven, dataDelOdd;
        final XSSFCellStyle numDelEven,  numDelOdd;
        final XSSFCellStyle monDelEven,  monDelOdd;
        // ── Tổng cộng ──
        final XSSFCellStyle totalLabel, totalNum, totalMoney;

        // Palette — bình thường
        private static final String COLOR_PRIMARY    = "1A3C6E";
        private static final String COLOR_SECONDARY  = "2E75B6";
        private static final String COLOR_ACCENT     = "D6E4F0"; // xanh nhạt (even)
        private static final String COLOR_WHITE      = "FFFFFF";
        private static final String COLOR_TOTAL_BG   = "FFF2CC";
        private static final String COLOR_TOTAL_FG   = "1A3C6E";
        private static final String COLOR_MONEY_HDR  = "C55A11";
        private static final String COLOR_NOTE_BG    = "FFF3CD";
        private static final String COLOR_NOTE_FG    = "856404";

        // Palette — khách đã xóa
        private static final String COLOR_DEL_EVEN   = "FFE0B2"; // cam đậm hơn
        private static final String COLOR_DEL_ODD    = "FFF3E0"; // cam nhạt

        Styles(XSSFWorkbook wb) {
            // ── Fonts ──
            XSSFFont fontTitle = font(wb, 16, true,  COLOR_PRIMARY, false);
            XSSFFont fontSub   = font(wb, 10, false, "555555",      true);
            XSSFFont fontNote  = font(wb, 9,  false, COLOR_NOTE_FG, true);
            XSSFFont fontHdr   = font(wb, 10, true,  COLOR_WHITE,   false);
            XSSFFont fontHdrMoney = font(wb, 10, true, COLOR_WHITE, false);
            XSSFFont fontData  = font(wb, 10, false, "1C1C1E",      false);
            XSSFFont fontDel   = font(wb, 10, false, "BF360C",      false); // cam đậm cho khách xóa
            XSSFFont fontTotal = font(wb, 10, true,  COLOR_TOTAL_FG, false);

            // ── Title ──
            title = wb.createCellStyle();
            title.setFont(fontTitle);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            // ── Subtitle ──
            subtitle = wb.createCellStyle();
            subtitle.setFont(fontSub);
            subtitle.setAlignment(HorizontalAlignment.CENTER);
            subtitle.setVerticalAlignment(VerticalAlignment.CENTER);

            // ── Note (ghi chú highlight cam) ──
            note = wb.createCellStyle();
            note.setFont(fontNote);
            note.setAlignment(HorizontalAlignment.LEFT);
            note.setVerticalAlignment(VerticalAlignment.CENTER);
            note.setFillForegroundColor(new XSSFColor(hexToRgb(COLOR_NOTE_BG), null));
            note.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // ── Headers ──
            header       = makeHeaderStyle(wb, fontHdr,      COLOR_PRIMARY);
            headerCenter = makeHeaderStyle(wb, fontHdr,      COLOR_PRIMARY);
            headerCenter.setAlignment(HorizontalAlignment.CENTER);
            headerProduct = makeHeaderStyle(wb, fontHdr,     COLOR_SECONDARY);
            headerProduct.setAlignment(HorizontalAlignment.CENTER);
            headerProduct.setWrapText(true);
            headerMoney  = makeHeaderStyle(wb, fontHdrMoney, COLOR_MONEY_HDR);
            headerMoney.setAlignment(HorizontalAlignment.RIGHT);

            // ── Data rows — bình thường ──
            dataEven = makeDataStyle(wb, fontData, COLOR_ACCENT, HorizontalAlignment.LEFT,   null);
            dataOdd  = makeDataStyle(wb, fontData, COLOR_WHITE,  HorizontalAlignment.LEFT,   null);
            numEven  = makeDataStyle(wb, fontData, COLOR_ACCENT, HorizontalAlignment.CENTER, "#,##0.##");
            numOdd   = makeDataStyle(wb, fontData, COLOR_WHITE,  HorizontalAlignment.CENTER, "#,##0.##");
            monEven  = makeDataStyle(wb, fontData, COLOR_ACCENT, HorizontalAlignment.RIGHT,  "#,##0");
            monOdd   = makeDataStyle(wb, fontData, COLOR_WHITE,  HorizontalAlignment.RIGHT,  "#,##0");

            // ── Data rows — khách đã xóa (cam) ──
            dataDelEven = makeDataStyle(wb, fontDel, COLOR_DEL_EVEN, HorizontalAlignment.LEFT,   null);
            dataDelOdd  = makeDataStyle(wb, fontDel, COLOR_DEL_ODD,  HorizontalAlignment.LEFT,   null);
            numDelEven  = makeDataStyle(wb, fontDel, COLOR_DEL_EVEN, HorizontalAlignment.CENTER, "#,##0.##");
            numDelOdd   = makeDataStyle(wb, fontDel, COLOR_DEL_ODD,  HorizontalAlignment.CENTER, "#,##0.##");
            monDelEven  = makeDataStyle(wb, fontDel, COLOR_DEL_EVEN, HorizontalAlignment.RIGHT,  "#,##0");
            monDelOdd   = makeDataStyle(wb, fontDel, COLOR_DEL_ODD,  HorizontalAlignment.RIGHT,  "#,##0");

            // ── Total row ──
            totalLabel = makeTotalStyle(wb, fontTotal, HorizontalAlignment.LEFT,   null);
            totalNum   = makeTotalStyle(wb, fontTotal, HorizontalAlignment.CENTER, "#,##0.##");
            totalMoney = makeTotalStyle(wb, fontTotal, HorizontalAlignment.RIGHT,  "#,##0");
        }

        // ── Factory helpers ──

        private XSSFFont font(XSSFWorkbook wb, int size, boolean bold, String hexColor, boolean italic) {
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setItalic(italic);
            f.setColor(new XSSFColor(hexToRgb(hexColor), null));
            return f;
        }

        private XSSFCellStyle makeHeaderStyle(XSSFWorkbook wb, XSSFFont font, String bgHex) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(new XSSFColor(hexToRgb(bgHex), null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setAlignment(HorizontalAlignment.LEFT);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setBorderBottom(BorderStyle.MEDIUM);
            st.setBorderTop(BorderStyle.THIN);
            st.setBorderLeft(BorderStyle.THIN);
            st.setBorderRight(BorderStyle.THIN);
            return st;
        }

        private XSSFCellStyle makeDataStyle(XSSFWorkbook wb, XSSFFont font,
                                            String bgHex, HorizontalAlignment align, String numFmt) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(new XSSFColor(hexToRgb(bgHex), null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setAlignment(align);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setBorderBottom(BorderStyle.THIN);
            st.setBorderTop(BorderStyle.THIN);
            st.setBorderLeft(BorderStyle.THIN);
            st.setBorderRight(BorderStyle.THIN);
            if (numFmt != null)
                st.setDataFormat(wb.createDataFormat().getFormat(numFmt));
            return st;
        }

        private XSSFCellStyle makeTotalStyle(XSSFWorkbook wb, XSSFFont font,
                                             HorizontalAlignment align, String numFmt) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(new XSSFColor(hexToRgb(COLOR_TOTAL_BG), null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setBorderTop(BorderStyle.MEDIUM);
            st.setBorderBottom(BorderStyle.MEDIUM);
            XSSFColor borderColor = new XSSFColor(hexToRgb(COLOR_PRIMARY), null);
            st.setTopBorderColor(borderColor);
            st.setBottomBorderColor(borderColor);
            st.setAlignment(align);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            if (numFmt != null)
                st.setDataFormat(wb.createDataFormat().getFormat(numFmt));
            return st;
        }

        private static byte[] hexToRgb(String hex) {
            return new byte[]{
                    (byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16),
                    (byte) Integer.parseInt(hex.substring(4, 6), 16)
            };
        }
    }
}