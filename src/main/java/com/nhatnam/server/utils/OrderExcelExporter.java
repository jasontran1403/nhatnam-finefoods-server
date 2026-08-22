package com.nhatnam.server.utils;

import com.nhatnam.server.entity.IncomeVoucher;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.repository.IncomeVoucherRepository;
import lombok.extern.log4j.Log4j2;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nhatnam.server.repository.OrderLogRepository;

@Component
@Log4j2
public class OrderExcelExporter {

    private static final ZoneId TZ = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final OrderLogRepository orderLogRepository;
    private final IncomeVoucherRepository incomeVoucherRepository;

    public OrderExcelExporter(OrderLogRepository orderLogRepository,
                              IncomeVoucherRepository incomeVoucherRepository) {
        this.orderLogRepository = orderLogRepository;
        this.incomeVoucherRepository = incomeVoucherRepository;
    }

    private static final float LINE_HEIGHT_PT = 15f;
    private static final float ROW_PADDING_PT = 10f;

    private static final String C_PRIMARY    = "1A1A2E";
    private static final String C_ACCENT     = "C9A84C";
    private static final String C_WHITE      = "FFFFFF";
    private static final String C_BORDER     = "E0E0E0";
    private static final String C_GRAY_ROW   = "FAFAFA";
    private static final String C_CANCEL_ROW = "F9FAFB";

    private static final Map<String, String[]> STATUS_COLORS = Map.of(
            "COMPLETED",       new String[]{"D1FAE5","065F46"},
            "CANCELLED",       new String[]{"F3F4F6","6B7280"},
            "PENDING_PAYMENT", new String[]{"FEF3C7","92400E"},
            "DELIVERING",      new String[]{"EDE9FE","5B21B6"},
            "PREPARING",       new String[]{"DBEAFE","1E40AF"},
            "READY",           new String[]{"E0E7FF","3730A3"},
            "PENDING",         new String[]{"F3F4F6","374151"}
    );
    private static final Map<String, String[]> PAY_STATUS_COLORS = Map.of(
            "PAID",    new String[]{"D1FAE5","065F46"},
            "UNPAID",  new String[]{"FEE2E2","991B1B"},
            "PARTIAL", new String[]{"DBEAFE","1E40AF"}
    );
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "PENDING","Chờ xử lý","CONFIRMED","Đã xác nhận","PREPARING","Đang chuẩn bị",
            "READY","Sẵn sàng","DELIVERING","Đang giao","PENDING_PAYMENT","Chờ thanh toán",
            "COMPLETED","Hoàn thành","CANCELLED","Đã huỷ"
    );
    private static final Map<String, String> PAY_STATUS_LABELS = Map.of(
            "PAID","Đã thanh toán","UNPAID","Chưa thanh toán","PARTIAL","TT một phần"
    );
    private static final Map<String, String> PAY_METHOD_LABELS = Map.of(
            "CASH","Tiền mặt","COD","Tiền mặt (COD)","TRANSFER","Chuyển khoản",
            "BANK_TRANSFER","Chuyển khoản","DEBT","Công nợ","OTHER","Công nợ"
    );

    // ════════════════════════════════════════════════════════════════
    // STYLE CACHE
    //
    // Nguyên nhân lỗi "maximum number of Cell Styles was exceeded (64000)":
    // trước đây mỗi ô đều gọi wb.createCellStyle() → export nhiều đơn là nổ.
    // StyleCache tạo MỘT style cho mỗi tổ hợp thuộc tính (màu nền, màu chữ,
    // đậm, cỡ, canh lề, định dạng số, có/không border trên/dưới) rồi tái sử
    // dụng. Số style giảm từ hàng triệu xuống còn vài trăm.
    //
    // LƯU Ý: style được cache là DÙNG CHUNG nên TUYỆT ĐỐI không mutate nó
    // (không setDataFormat / setBorder... sau khi lấy về). Mọi thuộc tính
    // phải truyền vào ngay lúc get(). Một cache được tạo mới cho mỗi workbook
    // (mỗi lần export) — style thuộc về workbook nào chỉ dùng trong workbook đó.
    // ════════════════════════════════════════════════════════════════
    private static final class StyleCache {
        private final XSSFWorkbook wb;
        private final Map<String, XSSFCellStyle> styles = new HashMap<>();
        private final Map<String, XSSFFont> fonts = new HashMap<>();
        private final XSSFColor borderColor;

        StyleCache(XSSFWorkbook wb) {
            this.wb = wb;
            this.borderColor = new XSSFColor(hexToBytes(C_BORDER), null);
        }

        private XSSFFont font(String fgHex, boolean bold, int size) {
            String key = fgHex + '|' + bold + '|' + size;
            XSSFFont f = fonts.get(key);
            if (f == null) {
                f = wb.createFont();
                f.setFontName("Arial");
                f.setBold(bold);
                f.setFontHeightInPoints((short) size);
                f.setColor(new XSSFColor(hexToBytes(fgHex), null));
                fonts.put(key, f);
            }
            return f;
        }

        /** Full control. Border trái/phải luôn THIN; trên/dưới tuỳ tham số. */
        XSSFCellStyle get(String bgHex, String fgHex, boolean bold, int size,
                          HorizontalAlignment align, short dataFmt,
                          boolean borderTop, boolean borderBottom) {
            String key = bgHex + '|' + fgHex + '|' + bold + '|' + size + '|' + align
                    + '|' + dataFmt + '|' + borderTop + '|' + borderBottom;
            XSSFCellStyle cs = styles.get(key);
            if (cs != null) return cs;

            cs = wb.createCellStyle();
            cs.setFillForegroundColor(new XSSFColor(hexToBytes(bgHex), null));
            cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cs.setAlignment(align);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            cs.setWrapText(true);
            cs.setDataFormat(dataFmt);
            cs.setBorderLeft(BorderStyle.THIN);
            cs.setBorderColor(XSSFCellBorder.BorderSide.LEFT, borderColor);
            cs.setBorderRight(BorderStyle.THIN);
            cs.setBorderColor(XSSFCellBorder.BorderSide.RIGHT, borderColor);
            if (borderTop) {
                cs.setBorderTop(BorderStyle.THIN);
                cs.setBorderColor(XSSFCellBorder.BorderSide.TOP, borderColor);
            }
            if (borderBottom) {
                cs.setBorderBottom(BorderStyle.THIN);
                cs.setBorderColor(XSSFCellBorder.BorderSide.BOTTOM, borderColor);
            }
            cs.setFont(font(fgHex, bold, size));
            styles.put(key, cs);
            return cs;
        }

        // Convenience: border trên + dưới, format General
        XSSFCellStyle get(String bgHex, String fgHex, boolean bold, int size, HorizontalAlignment align) {
            return get(bgHex, fgHex, bold, size, align, (short) 0, true, true);
        }

        // Convenience: có format số, border trên + dưới
        XSSFCellStyle get(String bgHex, String fgHex, boolean bold, int size,
                          HorizontalAlignment align, short dataFmt) {
            return get(bgHex, fgHex, bold, size, align, dataFmt, true, true);
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────
    public byte[] export(List<Order> orders, String title, String exportedBy) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            StyleCache sc = new StyleCache(wb);
            buildSummarySheet(sc, wb, orders, title, exportedBy);
            buildDetailSheet(sc, wb, orders, true);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SHEET 1 — SUMMARY (bản thường, không có cột phiếu thu)
    // ════════════════════════════════════════════════════════════════
    private void buildSummarySheet(StyleCache sc, XSSFWorkbook wb, List<Order> orders,
                                   String title, String exportedBy) {
        XSSFSheet ws = wb.createSheet("Đơn hàng");
        ws.setDisplayGridlines(false);

        DataFormat fmt = wb.createDataFormat();
        short vndFormat = fmt.getFormat("#,##0");

        Row r1 = ws.createRow(0); r1.setHeightInPoints(36);
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, 17));
        Cell t = r1.createCell(0);
        t.setCellValue(title.toUpperCase());
        t.setCellStyle(sc.get(C_PRIMARY, C_ACCENT, true, 16, HorizontalAlignment.LEFT));

        Row r2 = ws.createRow(1); r2.setHeightInPoints(20);
        ws.addMergedRegion(new CellRangeAddress(1, 1, 0, 17));
        Cell m = r2.createCell(0);
        m.setCellValue("Xuất lúc: " + LocalDateTime.now(TZ).format(DT_FMT)
                + "   |   Người xuất: " + exportedBy
                + "   |   Tổng đơn: " + orders.size());
        m.setCellStyle(sc.get("F9F9F9", "888888", false, 9, HorizontalAlignment.LEFT));

        String[] headers = {
                "STT","Mã đơn","Ngày tạo","Khách hàng","SĐT","Loại KH","Kho",
                "Người tạo đơn","Trạng thái đơn","Phương thức TT","TT thanh toán",
                "Tạm tính (đ)","Giảm giá (đ)","Phụ phí (đ)","VAT (đ)",
                "Tổng tiền (đ)","Đã thu (đ)","Còn nợ (đ)"
        };
        int[] colWidths = {5,16,18,30,14,9,18,20,18,18,16,16,14,13,12,16,14,14};

        Row hdrRow = ws.createRow(2); hdrRow.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hdrRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.CENTER));
            ws.setColumnWidth(i, colWidths[i] * 256);
        }
        ws.createFreezePane(0, 3);

        int rowNum = 3;
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            boolean cancelled = "CANCELLED".equals(o.getStatus().name());
            String rowBg = cancelled ? C_CANCEL_ROW : (i % 2 == 0 ? C_GRAY_ROW : C_WHITE);

            long finalAmt = round(o.getFinalAmount());
            long paidAmt  = round(o.getPaidAmount());
            long debt     = "PAID".equals(o.getPaymentStatus().name()) ? 0 : Math.max(0, finalAmt - paidAmt);

            Object[] rowData;
            if (cancelled) {
                String cancelReason = getCancelReason(o.getId());
                rowData = new Object[]{
                        i + 1, o.getOrderCode(), fmtTs(o.getCreatedAt()),
                        o.getCustomerName(), o.getCustomerPhone(),
                        "COMPANY".equals(o.getCustomerType()) ? "Công ty" : "Khách lẻ",
                        o.getWarehouseName(),
                        o.getUser() != null ? o.getUser().getFullName() : o.getOrderedByName(),
                        "__STATUS__", cancelReason,
                        "", "", "", "", "", "", "", ""
                };
            } else {
                rowData = new Object[]{
                        i + 1, o.getOrderCode(), fmtTs(o.getCreatedAt()),
                        o.getCustomerName(), o.getCustomerPhone(),
                        "COMPANY".equals(o.getCustomerType()) ? "Công ty" : "Khách lẻ",
                        o.getWarehouseName(),
                        o.getUser() != null ? o.getUser().getFullName() : o.getOrderedByName(),
                        "__STATUS__",
                        PAY_METHOD_LABELS.getOrDefault(o.getPaymentMethod(), o.getPaymentMethod()),
                        "__PAY_STATUS__",
                        round(o.getSubtotal()), round(o.getDiscountAmount()),
                        round(o.getSurcharge()), round(o.getVatAmount()),
                        finalAmt, paidAmt, debt,
                };
            }

            Row row = ws.createRow(rowNum);
            row.setHeightInPoints(calcRowHeight(rowData, colWidths));
            if (cancelled) ws.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 9, 17));

            for (int col = 0; col < rowData.length; col++) {
                Cell cell = row.createCell(col);
                if (cancelled && col == 9) {
                    cell.setCellValue(rowData[col] != null ? rowData[col].toString() : "");
                    cell.setCellStyle(sc.get(C_CANCEL_ROW, "6B7280", false, 9, HorizontalAlignment.LEFT));
                    continue;
                }
                if (cancelled && col > 9) {
                    cell.setCellValue("");
                    cell.setCellStyle(sc.get(C_CANCEL_ROW, "9CA3AF", false, 9, HorizontalAlignment.LEFT));
                    continue;
                }
                if ("__STATUS__".equals(rowData[col])) {
                    applyStatusCell(sc, cell, o.getStatus().name(), STATUS_LABELS, STATUS_COLORS);
                } else if ("__PAY_STATUS__".equals(rowData[col])) {
                    if (cancelled) {
                        cell.setCellValue("—");
                        cell.setCellStyle(sc.get(rowBg, "888888", false, 9, HorizontalAlignment.CENTER));
                    } else {
                        applyStatusCell(sc, cell, o.getPaymentStatus().name(), PAY_STATUS_LABELS, PAY_STATUS_COLORS);
                    }
                } else if (rowData[col] instanceof Long num) {
                    cell.setCellValue(num);
                    String fg = (col == 17 && debt > 0) ? "EF4444" : "1C1C1E";
                    boolean bold = (col == 15 || col == 17);
                    cell.setCellStyle(sc.get(rowBg, fg, bold, 9, HorizontalAlignment.LEFT, vndFormat));
                } else if (rowData[col] instanceof Integer num) {
                    cell.setCellValue(num);
                    cell.setCellStyle(sc.get(rowBg, "888888", false, 9, HorizontalAlignment.CENTER));
                } else {
                    String val = rowData[col] != null ? rowData[col].toString() : "";
                    cell.setCellValue(val);
                    String color = cancelled ? "9CA3AF" : "1C1C1E";
                    HorizontalAlignment align = (col == 0 || col == 2) ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT;
                    String fgColor = col == 1 ? (cancelled ? "9CA3AF" : C_ACCENT) : color;
                    cell.setCellStyle(sc.get(rowBg, fgColor, col == 1, col == 1 ? 10 : 9, align));
                }
            }
            rowNum++;
        }

        Row sumRow = ws.createRow(rowNum); sumRow.setHeightInPoints(28);
        ws.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, 10));
        Cell sumLabel = sumRow.createCell(0);
        sumLabel.setCellValue("TỔNG CỘNG  (" + orders.size() + " đơn)");
        sumLabel.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.LEFT));

        for (int col : new int[]{11, 12, 13, 14, 15, 16, 17}) {
            Cell scell = sumRow.createCell(col);
            char colLetter = (char) ('A' + col);
            scell.setCellFormula("SUM(" + colLetter + "4:" + colLetter + rowNum + ")");
            scell.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.LEFT, vndFormat));
        }
        ws.setAutoFilter(new CellRangeAddress(2, rowNum - 1, 0, headers.length - 1));
    }

    // ════════════════════════════════════════════════════════════════
    // SHEET 1 — SUMMARY (bản kế toán, có cột "Số phiếu thu" index 11)
    // Map<orderCode, List<receiptNumber>> — hỗ trợ nhiều phiếu thu / đơn
    // ════════════════════════════════════════════════════════════════
    private void buildSummarySheet(StyleCache sc, XSSFWorkbook wb, List<Order> orders, String title,
                                   String exportedBy, Map<String, List<String>> orderCodeToReceipts, boolean isAccountant) {
        XSSFSheet ws = wb.createSheet("Đơn hàng");
        ws.setDisplayGridlines(false);

        DataFormat fmt = wb.createDataFormat();
        short vndFormat = fmt.getFormat("#,##0");

        int totalCols = isAccountant ? 20 : 19;

        Row r1 = ws.createRow(0); r1.setHeightInPoints(36);
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols - 1));
        Cell t = r1.createCell(0);
        t.setCellValue(title.toUpperCase());
        t.setCellStyle(sc.get(C_PRIMARY, C_ACCENT, true, 16, HorizontalAlignment.LEFT));

        Row r2 = ws.createRow(1); r2.setHeightInPoints(20);
        ws.addMergedRegion(new CellRangeAddress(1, 1, 0, totalCols - 1));
        Cell m = r2.createCell(0);
        m.setCellValue("Xuất lúc: " + LocalDateTime.now(TZ).format(DT_FMT)
                + "   |   Người xuất: " + exportedBy
                + "   |   Tổng đơn: " + orders.size());
        m.setCellStyle(sc.get("F9F9F9", "888888", false, 9, HorizontalAlignment.LEFT));

        String[] headersWithReceipt = {
                "STT","Mã đơn","Ngày tạo","Khách hàng","SĐT","Loại KH","Kho",
                "Người tạo đơn","Trạng thái đơn","Phương thức TT","TT thanh toán",
                "Số phiếu thu","Tạm tính (đ)","Giảm giá (đ)","Phụ phí (đ)","VAT (đ)",
                "Tổng tiền (đ)","Đã thu (đ)","Còn nợ (đ)","Thông tin giao hàng"
        };
        String[] headersWithoutReceipt = {
                "STT","Mã đơn","Ngày tạo","Khách hàng","SĐT","Loại KH","Kho",
                "Người tạo đơn","Trạng thái đơn","Phương thức TT","TT thanh toán",
                "Tạm tính (đ)","Giảm giá (đ)","Phụ phí (đ)","VAT (đ)",
                "Tổng tiền (đ)","Đã thu (đ)","Còn nợ (đ)","Thông tin giao hàng"
        };
        int[] colWidthsWithReceipt = {5,16,18,30,14,9,18,20,18,18,16,16,16,14,13,12,16,14,14,28};
        int[] colWidthsWithoutReceipt = {5,16,18,30,14,9,18,20,18,18,16,16,14,13,12,16,14,14,28};

        String[] headers = isAccountant ? headersWithReceipt : headersWithoutReceipt;
        int[] colWidths = isAccountant ? colWidthsWithReceipt : colWidthsWithoutReceipt;

        Row hdrRow = ws.createRow(2); hdrRow.setHeightInPoints(28);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hdrRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.CENTER));
            ws.setColumnWidth(i, colWidths[i] * 256);
        }
        ws.createFreezePane(0, 3);

        int rowNum = 3;
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            boolean cancelled = "CANCELLED".equals(o.getStatus().name());
            String rowBg = cancelled ? C_CANCEL_ROW : (i % 2 == 0 ? C_GRAY_ROW : C_WHITE);

            long finalAmt = round(o.getFinalAmount());
            long paidAmt  = round(o.getPaidAmount());
            long debt     = "PAID".equals(o.getPaymentStatus().name()) ? 0 : Math.max(0, finalAmt - paidAmt);

            List<String> rcList = orderCodeToReceipts.getOrDefault(o.getOrderCode(), List.of());
            String receiptNums  = String.join("\n", rcList);
            String deliveryInfo = formatDeliveryInfo(o.getDeliveryInfoJson(), o.getWarehouseName());

            Object[] rowData;
            if (cancelled) {
                String cancelReason = getCancelReason(o.getId());
                if (isAccountant) {
                    rowData = new Object[]{
                            i + 1, o.getOrderCode(), fmtTs(o.getCreatedAt()),
                            o.getCustomerName(), o.getCustomerPhone(),
                            "COMPANY".equals(o.getCustomerType()) ? "Công ty" : "Khách lẻ",
                            o.getWarehouseName(),
                            o.getUser() != null ? o.getUser().getFullName() : o.getOrderedByName(),
                            "__STATUS__", cancelReason,
                            "", "", "", "", "", "", "", "", "", ""
                    };
                } else {
                    rowData = new Object[]{
                            i + 1, o.getOrderCode(), fmtTs(o.getCreatedAt()),
                            o.getCustomerName(), o.getCustomerPhone(),
                            "COMPANY".equals(o.getCustomerType()) ? "Công ty" : "Khách lẻ",
                            o.getWarehouseName(),
                            o.getUser() != null ? o.getUser().getFullName() : o.getOrderedByName(),
                            "__STATUS__", cancelReason,
                            "", "", "", "", "", "", "", "", ""
                    };
                }
            } else {
                if (isAccountant) {
                    rowData = new Object[]{
                            i + 1, o.getOrderCode(), fmtTs(o.getCreatedAt()),
                            o.getCustomerName(), o.getCustomerPhone(),
                            "COMPANY".equals(o.getCustomerType()) ? "Công ty" : "Khách lẻ",
                            o.getWarehouseName(),
                            o.getUser() != null ? o.getUser().getFullName() : o.getOrderedByName(),
                            "__STATUS__",
                            PAY_METHOD_LABELS.getOrDefault(o.getPaymentMethod(), o.getPaymentMethod()),
                            "__PAY_STATUS__",
                            receiptNums,
                            round(o.getSubtotal()),
                            round(o.getDiscountAmount()),
                            round(o.getSurcharge()),
                            round(o.getVatAmount()),
                            finalAmt,
                            paidAmt,
                            debt,
                            deliveryInfo,
                    };
                } else {
                    rowData = new Object[]{
                            i + 1, o.getOrderCode(), fmtTs(o.getCreatedAt()),
                            o.getCustomerName(), o.getCustomerPhone(),
                            "COMPANY".equals(o.getCustomerType()) ? "Công ty" : "Khách lẻ",
                            o.getWarehouseName(),
                            o.getUser() != null ? o.getUser().getFullName() : o.getOrderedByName(),
                            "__STATUS__",
                            PAY_METHOD_LABELS.getOrDefault(o.getPaymentMethod(), o.getPaymentMethod()),
                            "__PAY_STATUS__",
                            round(o.getSubtotal()),
                            round(o.getDiscountAmount()),
                            round(o.getSurcharge()),
                            round(o.getVatAmount()),
                            finalAmt,
                            paidAmt,
                            debt,
                            deliveryInfo,
                    };
                }
            }

            Row row = ws.createRow(rowNum);
            row.setHeightInPoints(calcRowHeight(rowData, colWidths));

            if (cancelled) {
                int mergeStart = 9;
                int mergeEnd = isAccountant ? 19 : 18;
                ws.addMergedRegion(new CellRangeAddress(rowNum, rowNum, mergeStart, mergeEnd));
            }

            for (int col = 0; col < rowData.length; col++) {
                Cell cell = row.createCell(col);

                if (cancelled && col == 9) {
                    cell.setCellValue(rowData[col] != null ? rowData[col].toString() : "");
                    cell.setCellStyle(sc.get(C_CANCEL_ROW, "6B7280", false, 9, HorizontalAlignment.LEFT));
                    continue;
                }
                if (cancelled && col > 9) {
                    cell.setCellValue("");
                    cell.setCellStyle(sc.get(C_CANCEL_ROW, "9CA3AF", false, 9, HorizontalAlignment.LEFT));
                    continue;
                }

                if ("__STATUS__".equals(rowData[col])) {
                    applyStatusCell(sc, cell, o.getStatus().name(), STATUS_LABELS, STATUS_COLORS);
                } else if ("__PAY_STATUS__".equals(rowData[col])) {
                    applyStatusCell(sc, cell, o.getPaymentStatus().name(), PAY_STATUS_LABELS, PAY_STATUS_COLORS);
                } else if (isAccountant && col == 11) {
                    String rc = rowData[col] != null ? rowData[col].toString() : "";
                    cell.setCellValue(rc);
                    boolean hasReceipt   = !rc.isEmpty();
                    boolean multiReceipt = rc.contains("\n");
                    String fg = !hasReceipt ? "CCCCCC" : (multiReceipt ? "7C3AED" : C_ACCENT);
                    cell.setCellStyle(sc.get(rowBg, fg, hasReceipt, 9, HorizontalAlignment.CENTER));
                } else if (col == (isAccountant ? 19 : 18)) {
                    String di = rowData[col] != null ? rowData[col].toString() : "";
                    cell.setCellValue(di);
                    cell.setCellStyle(sc.get(rowBg, di.isEmpty() ? "CCCCCC" : "374151",
                            false, 9, HorizontalAlignment.LEFT));
                } else if (rowData[col] instanceof Long num) {
                    cell.setCellValue(num);
                    int totalCol = isAccountant ? 16 : 15;
                    int debtCol  = isAccountant ? 18 : 17;
                    String fg = (col == debtCol && debt > 0) ? "EF4444" : "1C1C1E";
                    boolean bold = (col == totalCol || col == debtCol);
                    cell.setCellStyle(sc.get(rowBg, fg, bold, 9, HorizontalAlignment.LEFT, vndFormat));
                } else if (rowData[col] instanceof Integer num) {
                    cell.setCellValue(num);
                    cell.setCellStyle(sc.get(rowBg, "888888", false, 9, HorizontalAlignment.CENTER));
                } else {
                    String val = rowData[col] != null ? rowData[col].toString() : "";
                    cell.setCellValue(val);
                    String color = cancelled ? "9CA3AF" : "1C1C1E";
                    HorizontalAlignment align = (col == 0 || col == 2) ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT;
                    String fgColor = col == 1 ? (cancelled ? "9CA3AF" : C_ACCENT) : color;
                    cell.setCellStyle(sc.get(rowBg, fgColor, col == 1, col == 1 ? 10 : 9, align));
                }
            }
            rowNum++;
        }

        Row sumRow = ws.createRow(rowNum); sumRow.setHeightInPoints(28);
        int mergeEnd = isAccountant ? 12 : 11;
        ws.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, mergeEnd));
        Cell sumLabel = sumRow.createCell(0);
        sumLabel.setCellValue("TỔNG CỘNG  (" + orders.size() + " đơn)");
        sumLabel.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.LEFT));

        int[] sumCols = isAccountant
                ? new int[]{12, 13, 14, 15, 16, 17, 18}
                : new int[]{11, 12, 13, 14, 15, 16, 17};

        for (int col : sumCols) {
            Cell scell = sumRow.createCell(col);
            char colLetter = (char) ('A' + col);
            scell.setCellFormula("SUM(" + colLetter + "4:" + colLetter + rowNum + ")");
            scell.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.LEFT, vndFormat));
        }

        int lastCol = isAccountant ? 19 : 18;
        sumRow.createCell(lastCol).setCellStyle(sc.get(C_PRIMARY, C_WHITE, false, 9, HorizontalAlignment.LEFT));

        ws.setAutoFilter(new CellRangeAddress(2, rowNum - 1, 0, totalCols - 1));
    }

    // ════════════════════════════════════════════════════════════════
    // SHEET 2 — ITEM DETAIL
    //
    // "Giả merge" (fix filter/sort mất dòng trên Excel): KHÔNG merge ô theo
    // chiều dọc. Set giá trị THẬT cho mọi dòng của một khối. Để nhìn vẫn như
    // merge: các dòng "phụ" (không phải dòng đầu khối) được set màu chữ = màu
    // nền để chữ ẩn đi, đồng thời border trên/dưới giữa các dòng trong khối bị
    // bỏ. Border được TÍNH SẴN theo vị trí (đầu/giữa/cuối khối) và lấy style đã
    // cache — không mutate style dùng chung (đó là lý do gây lỗi 64000 style cũ).
    // ════════════════════════════════════════════════════════════════
    private void buildDetailSheet(StyleCache sc, XSSFWorkbook wb, List<Order> orders, boolean accountantMode) {
        XSSFSheet ws = wb.createSheet("Chi tiết sản phẩm");
        ws.setDisplayGridlines(false);

        DataFormat fmt  = wb.createDataFormat();
        short vndFormat = fmt.getFormat("#,##0");
        short vnd2Format = fmt.getFormat("#,##0.00");
        short qtyFormat  = fmt.getFormat("#,##0.###");

        int lastCol = accountantMode ? 13 : 8;

        Row r1 = ws.createRow(0); r1.setHeightInPoints(32);
        ws.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));
        Cell t = r1.createCell(0);
        t.setCellValue("CHI TIẾT SẢN PHẨM TRONG ĐƠN HÀNG");
        t.setCellStyle(sc.get(C_PRIMARY, C_ACCENT, true, 14, HorizontalAlignment.LEFT));

        String[] hdrs;
        int[]    widths;
        if (accountantMode) {
            hdrs = new String[]{
                    "Mã đơn", "Khách hàng", "Ngày tạo", "Trạng thái",
                    "Nguyên liệu", "Số lượng", "ĐVT",
                    "Đơn giá\n(trước thuế)", "Discount", "TT\n(trước thuế)",
                    "VAT (đ)", "TT\n(sau thuế)", "Phụ phí", "Tổng cộng\n(sau thuế)",
            };
            widths = new int[]{16, 26, 16, 16, 26, 14, 10, 16, 14, 16, 14, 15, 13, 16};
        } else {
            hdrs = new String[]{
                    "Mã đơn", "Khách hàng", "Ngày tạo", "Trạng thái",
                    "Nguyên liệu", "SL nguyên liệu", "ĐVT",
                    "Đơn giá (đ)", "Thành tiền (đ)",
            };
            widths = new int[]{16, 30, 18, 18, 28, 16, 12, 16, 16};
        }

        Row hdrRow = ws.createRow(1);
        hdrRow.setHeightInPoints(accountantMode ? 36 : 28);
        for (int i = 0; i < hdrs.length; i++) {
            Cell c = hdrRow.createCell(i);
            c.setCellValue(hdrs[i]);
            c.setCellStyle(sc.get(C_PRIMARY, C_WHITE, true, 10, HorizontalAlignment.CENTER));
            ws.setColumnWidth(i, widths[i] * 256);
        }
        ws.createFreezePane(0, 2);

        int rowNum   = 2;
        int orderIdx = 0;

        for (Order o : orders) {
            boolean cancelled = "CANCELLED".equals(o.getStatus().name());
            String rowBg = cancelled ? C_CANCEL_ROW : (orderIdx % 2 == 0 ? C_GRAY_ROW : C_WHITE);

            List<OrderItem> items = new ArrayList<>(o.getOrderItems());

            int orderTotalRows = 0;
            for (OrderItem item : items) {
                var ings = item.getOrderItemIngredients();
                orderTotalRows += (ings != null && !ings.isEmpty()) ? ings.size() : 1;
            }

            int orderFirstRow = rowNum;
            int orderLastRow  = rowNum + orderTotalRows - 1;

            // Pre-create tất cả dòng của đơn với ô trống nền theo rowBg
            for (int r = orderFirstRow; r <= orderLastRow; r++) {
                Row row = ws.createRow(r);
                row.setHeightInPoints(LINE_HEIGHT_PT + ROW_PADDING_PT);
                for (int col = 0; col <= lastCol; col++) {
                    Cell cell = row.createCell(col);
                    cell.setCellValue("");
                    cell.setCellStyle(sc.get(rowBg, "1C1C1E", false, 9, HorizontalAlignment.LEFT));
                }
            }

            String orderCode    = o.getOrderCode();
            String customerName = o.getCustomerName() != null ? o.getCustomerName() : "";
            String createdAtStr = fmtTs(o.getCreatedAt());
            String fgMain       = cancelled ? "9CA3AF" : "1C1C1E";
            String fgCode       = cancelled ? "9CA3AF" : C_ACCENT;

            // Cols 0–3: khối = toàn bộ đơn (giá trị giống nhau mọi dòng)
            for (int r = orderFirstRow; r <= orderLastRow; r++) {
                Row row = ws.getRow(r);

                Cell c0 = row.getCell(0);
                c0.setCellValue(orderCode);
                styleMerged(sc, c0, r, orderFirstRow, orderLastRow, rowBg, fgCode, true, 10,
                        HorizontalAlignment.LEFT, (short) 0);

                Cell c1 = row.getCell(1);
                c1.setCellValue(customerName);
                styleMerged(sc, c1, r, orderFirstRow, orderLastRow, rowBg, fgMain, false, 9,
                        HorizontalAlignment.LEFT, (short) 0);

                Cell c2 = row.getCell(2);
                c2.setCellValue(createdAtStr);
                styleMerged(sc, c2, r, orderFirstRow, orderLastRow, rowBg, fgMain, false, 9,
                        HorizontalAlignment.CENTER, (short) 0);

                Cell c3 = row.getCell(3);
                styleStatusMerged(sc, c3, o.getStatus().name(), r, orderFirstRow, orderLastRow);
            }

            if (accountantMode) {
                java.math.BigDecimal totalGross = java.math.BigDecimal.ZERO;
                for (OrderItem item : items) {
                    java.math.BigDecimal sub = item.getSubtotal() != null ? item.getSubtotal() : java.math.BigDecimal.ZERO;
                    totalGross = totalGross.add(sub);
                }
                java.math.BigDecimal orderDiscountAmt = o.getDiscountAmount() != null
                        ? o.getDiscountAmount() : java.math.BigDecimal.ZERO;
                java.math.BigDecimal discRatio = totalGross.compareTo(java.math.BigDecimal.ZERO) > 0
                        ? orderDiscountAmt.divide(totalGross, 10, java.math.RoundingMode.HALF_UP)
                        : java.math.BigDecimal.ZERO;

                java.math.BigDecimal sumL = java.math.BigDecimal.ZERO;
                for (OrderItem item : items) {
                    java.math.BigDecimal sub = item.getSubtotal() != null ? item.getSubtotal() : java.math.BigDecimal.ZERO;
                    java.math.BigDecimal qty = item.getQuantity()  != null ? item.getQuantity()  : java.math.BigDecimal.ONE;
                    int vr       = item.getVatRate() != null ? item.getVatRate() : 0;
                    boolean incl = "INCLUSIVE".equalsIgnoreCase(item.getVatMode());
                    java.math.BigDecimal divisor = vr == 0 ? java.math.BigDecimal.ONE
                            : java.math.BigDecimal.ONE.add(java.math.BigDecimal.valueOf(vr)
                            .divide(java.math.BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP));
                    java.math.BigDecimal grossPerUnit = sub.divide(qty, 10, java.math.RoundingMode.HALF_UP);
                    java.math.BigDecimal h  = incl ? grossPerUnit.divide(divisor, 10, java.math.RoundingMode.HALF_UP) : grossPerUnit;
                    java.math.BigDecimal i2 = h.multiply(discRatio);
                    java.math.BigDecimal j  = h.subtract(i2).multiply(qty);
                    java.math.BigDecimal k  = vr == 0 ? java.math.BigDecimal.ZERO
                            : j.multiply(java.math.BigDecimal.valueOf(vr))
                            .divide(java.math.BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP);
                    sumL = sumL.add(j.add(k));
                }

                java.math.BigDecimal surchargeVal = o.getSurcharge() != null ? o.getSurcharge() : java.math.BigDecimal.ZERO;
                long surchargeDisplay = surchargeVal.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
                long nValue = sumL.add(surchargeVal).setScale(0, java.math.RoundingMode.HALF_UP).longValue();

                // Cols 12,13: khối = toàn bộ đơn
                for (int r = orderFirstRow; r <= orderLastRow; r++) {
                    Row row = ws.getRow(r);

                    Cell cSur = row.getCell(12);
                    cSur.setCellValue(surchargeDisplay > 0 ? surchargeDisplay : 0);
                    styleMerged(sc, cSur, r, orderFirstRow, orderLastRow, rowBg, fgMain, false, 9,
                            HorizontalAlignment.LEFT, vndFormat);

                    Cell cn = row.getCell(13);
                    cn.setCellValue(nValue);
                    styleMerged(sc, cn, r, orderFirstRow, orderLastRow, rowBg, fgMain, true, 10,
                            HorizontalAlignment.LEFT, vndFormat);
                }

                for (OrderItem item : items) {
                    var ings = item.getOrderItemIngredients();
                    int ingCount     = (ings != null && !ings.isEmpty()) ? ings.size() : 1;
                    int itemFirstRow = rowNum;
                    int itemLastRow  = rowNum + ingCount - 1;

                    java.math.BigDecimal sub = item.getSubtotal() != null ? item.getSubtotal() : java.math.BigDecimal.ZERO;
                    java.math.BigDecimal qty = item.getQuantity()  != null ? item.getQuantity()  : java.math.BigDecimal.ONE;
                    int vr       = item.getVatRate() != null ? item.getVatRate() : 0;
                    boolean incl = "INCLUSIVE".equalsIgnoreCase(item.getVatMode());
                    java.math.BigDecimal divisor = vr == 0 ? java.math.BigDecimal.ONE
                            : java.math.BigDecimal.ONE.add(java.math.BigDecimal.valueOf(vr)
                            .divide(java.math.BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP));
                    java.math.BigDecimal grossPerUnit = sub.divide(qty, 10, java.math.RoundingMode.HALF_UP);
                    java.math.BigDecimal h  = incl ? grossPerUnit.divide(divisor, 10, java.math.RoundingMode.HALF_UP) : grossPerUnit;
                    java.math.BigDecimal i2 = h.multiply(discRatio);
                    java.math.BigDecimal j  = h.subtract(i2).multiply(qty);
                    java.math.BigDecimal k  = vr == 0 ? java.math.BigDecimal.ZERO
                            : j.multiply(java.math.BigDecimal.valueOf(vr))
                            .divide(java.math.BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP);
                    java.math.BigDecimal l  = j.add(k);

                    double hVal = h.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
                    double iVal = i2.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
                    double jVal = j.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
                    double kVal = k.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
                    double lVal = l.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();

                    boolean isBox = "BOX".equalsIgnoreCase(item.getSaleType());
                    String displayUnit = isBox ? "Thùng" : (item.getUnit() != null ? item.getUnit() : "");

                    String fgDisc = cancelled ? "9CA3AF" : "991B1B";
                    String fgVat  = cancelled ? "9CA3AF" : "374151";

                    // Cols 7–11: khối = item (giá trị giống nhau mọi dòng của item)
                    for (int r = itemFirstRow; r <= itemLastRow; r++) {
                        Row itemRow = ws.getRow(r);

                        Cell ch = itemRow.getCell(7);
                        ch.setCellValue(hVal);
                        styleMerged(sc, ch, r, itemFirstRow, itemLastRow, rowBg, fgMain, false, 9,
                                HorizontalAlignment.LEFT, vnd2Format);

                        Cell ci = itemRow.getCell(8);
                        ci.setCellValue(iVal);
                        styleMerged(sc, ci, r, itemFirstRow, itemLastRow, rowBg, fgDisc, false, 9,
                                HorizontalAlignment.LEFT, vnd2Format);

                        Cell cj = itemRow.getCell(9);
                        cj.setCellValue(jVal);
                        styleMerged(sc, cj, r, itemFirstRow, itemLastRow, rowBg, fgMain, false, 9,
                                HorizontalAlignment.LEFT, vnd2Format);

                        Cell ck = itemRow.getCell(10);
                        ck.setCellValue(kVal);
                        styleMerged(sc, ck, r, itemFirstRow, itemLastRow, rowBg, fgVat, false, 9,
                                HorizontalAlignment.LEFT, vnd2Format);

                        Cell cl = itemRow.getCell(11);
                        cl.setCellValue(lVal);
                        styleMerged(sc, cl, r, itemFirstRow, itemLastRow, rowBg, fgMain, true, 9,
                                HorizontalAlignment.LEFT, vnd2Format);
                    }

                    if (ings != null && !ings.isEmpty()) {
                        for (int idx = 0; idx < ings.size(); idx++) {
                            var ii = ings.get(idx);
                            Row ingRow = ws.getRow(itemFirstRow + idx);

                            Cell cIng = ingRow.getCell(4);
                            cIng.setCellValue(ii.getIngredientName() != null ? ii.getIngredientName() : "");
                            cIng.setCellStyle(sc.get(rowBg, cancelled ? "9CA3AF" : "374151", false, 9, HorizontalAlignment.LEFT));

                            Cell cQty = ingRow.getCell(5);
                            if (idx == 0) {
                                cQty.setCellValue(qty.doubleValue());
                                cQty.setCellStyle(sc.get(rowBg, cancelled ? "9CA3AF" : "374151", false, 9,
                                        HorizontalAlignment.LEFT, qtyFormat));
                            } else {
                                cQty.setCellValue("");
                                cQty.setCellStyle(sc.get(rowBg, "9CA3AF", false, 9, HorizontalAlignment.LEFT));
                            }

                            Cell cUnit = ingRow.getCell(6);
                            if (idx == 0) {
                                cUnit.setCellValue(displayUnit);
                                cUnit.setCellStyle(sc.get(rowBg, cancelled ? "9CA3AF" : "374151", false, 9, HorizontalAlignment.CENTER));
                            } else {
                                cUnit.setCellValue("");
                                cUnit.setCellStyle(sc.get(rowBg, "9CA3AF", false, 9, HorizontalAlignment.CENTER));
                            }
                        }
                    }
                    rowNum = itemLastRow + 1;
                }

            } else {
                for (OrderItem item : items) {
                    var ings = item.getOrderItemIngredients();
                    int ingCount     = (ings != null && !ings.isEmpty()) ? ings.size() : 1;
                    int itemFirstRow = rowNum;
                    int itemLastRow  = rowNum + ingCount - 1;

                    long unitPriceVal = round(item.getUnitPrice());
                    long subtotalVal  = round(item.getSubtotal());

                    // Cols 7,8: khối = item
                    for (int r = itemFirstRow; r <= itemLastRow; r++) {
                        Row itemRow = ws.getRow(r);

                        Cell c7 = itemRow.getCell(7);
                        c7.setCellValue(unitPriceVal);
                        styleMerged(sc, c7, r, itemFirstRow, itemLastRow, rowBg, fgMain, false, 9,
                                HorizontalAlignment.LEFT, vndFormat);

                        Cell c8 = itemRow.getCell(8);
                        c8.setCellValue(subtotalVal);
                        styleMerged(sc, c8, r, itemFirstRow, itemLastRow, rowBg, fgMain, true, 9,
                                HorizontalAlignment.LEFT, vndFormat);
                    }

                    if (ings != null && !ings.isEmpty()) {
                        for (int idx = 0; idx < ings.size(); idx++) {
                            var ii = ings.get(idx);
                            Row ingRow = ws.getRow(itemFirstRow + idx);

                            Cell cIng = ingRow.getCell(4);
                            cIng.setCellValue(ii.getIngredientName() != null ? ii.getIngredientName() : "");
                            cIng.setCellStyle(sc.get(rowBg, cancelled ? "9CA3AF" : "374151", false, 9, HorizontalAlignment.LEFT));

                            Cell cQty = ingRow.getCell(5);
                            if (ii.getQuantityUsed() != null) {
                                cQty.setCellValue(ii.getQuantityUsed().doubleValue());
                                cQty.setCellStyle(sc.get(rowBg, cancelled ? "9CA3AF" : "374151", false, 9,
                                        HorizontalAlignment.LEFT, qtyFormat));
                            } else {
                                cQty.setCellValue("");
                                cQty.setCellStyle(sc.get(rowBg, "9CA3AF", false, 9, HorizontalAlignment.LEFT));
                            }

                            Cell cUnit = ingRow.getCell(6);
                            cUnit.setCellValue(ii.getUnit() != null ? ii.getUnit() : "");
                            cUnit.setCellStyle(sc.get(rowBg, cancelled ? "9CA3AF" : "374151", false, 9, HorizontalAlignment.CENTER));
                        }
                    }
                    rowNum = itemLastRow + 1;
                }
            }
            orderIdx++;
        }
        ws.setAutoFilter(new CellRangeAddress(1, rowNum - 1, 0, lastCol));
    }

    // ════════════════════════════════════════════════════════════════
    // exportForAccountant — build map orderCode → List<receiptNumber>
    // ════════════════════════════════════════════════════════════════
    public byte[] exportForAccountant(List<Order> orders, String title, String exportedBy, boolean isAccountant) throws Exception {
        Map<String, List<String>> orderCodeToReceipts = new HashMap<>();
        try {
            List<IncomeVoucher> vouchers = incomeVoucherRepository.findAllWithLinkedOrders();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (IncomeVoucher v : vouchers) {
                if (v.getReceiptNumber() == null || v.getReceiptNumber().isBlank()) continue;
                try {
                    List<String> codes = mapper.readValue(v.getLinkedOrderCodes(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    for (String code : codes) {
                        orderCodeToReceipts
                                .computeIfAbsent(code.trim(), k -> new ArrayList<>())
                                .add(v.getReceiptNumber().trim());
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("[EXPORT] Failed to load income vouchers: {}", e.getMessage());
        }

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            StyleCache sc = new StyleCache(wb);
            buildSummarySheet(sc, wb, orders, title, exportedBy, orderCodeToReceipts, isAccountant);
            buildDetailSheet(sc, wb, orders, true);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // HEIGHT CALCULATOR
    // ════════════════════════════════════════════════════════════════
    private float calcRowHeight(Object[] rowData, int[] colWidths) {
        int maxLines = 1;
        for (int i = 0; i < rowData.length && i < colWidths.length; i++) {
            if (rowData[i] == null) continue;
            String val = rowData[i].toString();
            if (val.startsWith("__") || val.isEmpty()) continue;
            int charsPerLine = Math.max((int)(colWidths[i] * 1.5), 6);
            String[] hardLines = val.split("\n", -1);
            int totalLines = 0;
            for (String hl : hardLines) {
                totalLines += Math.max(1, (int) Math.ceil((double) hl.length() / charsPerLine));
            }
            maxLines = Math.max(maxLines, totalLines);
        }
        return maxLines * LINE_HEIGHT_PT + ROW_PADDING_PT;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Áp style cho một ô thuộc "khối giả-merge". Dòng đầu khối hiển thị chữ
     * (màu fg thật) và có border trên; các dòng sau ẩn chữ (màu chữ = màu nền)
     * và bỏ border trên. Border dưới chỉ có ở dòng cuối khối. Dữ liệu thật vẫn
     * được set cho mọi dòng (do caller) nên filter/sort trên Excel hoạt động đúng.
     */
    private void styleMerged(StyleCache sc, Cell cell, int r, int first, int last,
                             String bg, String fg, boolean bold, int size,
                             HorizontalAlignment align, short dataFmt) {
        boolean isFirst = (r == first);
        boolean isLast  = (r == last);
        String useFg = isFirst ? fg : bg; // ẩn chữ ở các dòng nối tiếp
        cell.setCellStyle(sc.get(bg, useFg, bold, size, align, dataFmt, isFirst, isLast));
    }

    /**
     * Giống styleMerged nhưng cho ô Trạng thái: nền là màu theo status. Giá trị
     * trạng thái được set cho mọi dòng (để filter/sort đúng), ẩn chữ ở dòng phụ
     * bằng cách cho màu chữ = màu nền status.
     */
    private void styleStatusMerged(StyleCache sc, Cell cell, String status, int r, int first, int last) {
        cell.setCellValue(STATUS_LABELS.getOrDefault(status, status));
        String[] clr = STATUS_COLORS.get(status);
        String sbg = clr != null ? clr[0] : C_WHITE;
        String sfg = clr != null ? clr[1] : "374151";
        boolean isFirst = (r == first);
        boolean isLast  = (r == last);
        String useFg = isFirst ? sfg : sbg;
        cell.setCellStyle(sc.get(sbg, useFg, true, 9, HorizontalAlignment.CENTER, (short) 0, isFirst, isLast));
    }

    private void applyStatusCell(StyleCache sc, Cell cell, String status,
                                 Map<String, String> labels, Map<String, String[]> colors) {
        cell.setCellValue(labels.getOrDefault(status, status));
        String[] clr = colors.get(status);
        if (clr != null) {
            cell.setCellStyle(sc.get(clr[0], clr[1], true, 9, HorizontalAlignment.CENTER));
        }
    }

    private static byte[] hexToBytes(String hex) {
        return new byte[]{
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16),
        };
    }

    private String fmtTs(Long ms) {
        if (ms == null) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), TZ).format(DT_FMT);
    }

    private long round(java.math.BigDecimal bd) {
        return bd == null ? 0L : bd.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    private String getCancelReason(Long orderId) {
        return orderLogRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream()
                .filter(l -> "CANCELLED".equals(l.getAction()))
                .findFirst()
                .map(l -> {
                    String reason = l.getNote() != null && !l.getNote().isBlank()
                            ? l.getNote() : "(Không có lý do)";
                    String actor = l.getActorName() != null ? l.getActorName() : "";
                    return "Hủy bởi: " + actor + "\nLý do: " + reason;
                })
                .orElse("Đã hủy");
    }

    public byte[] exportDeliveryReport(List<Order> orders, Long fromMs, Long toMs) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            java.util.function.Function<String, XSSFColor> hex2color = hex -> new XSSFColor(
                    new byte[]{
                            (byte) Integer.parseInt(hex.substring(0, 2), 16),
                            (byte) Integer.parseInt(hex.substring(2, 4), 16),
                            (byte) Integer.parseInt(hex.substring(4, 6), 16)
                    }, null);

            java.util.function.BiFunction<String, String, XSSFCellStyle> mkHeader = (bg, fg) -> {
                XSSFCellStyle cs = wb.createCellStyle();
                XSSFFont f = wb.createFont();
                f.setBold(true); f.setFontName("Arial"); f.setFontHeightInPoints((short) 11);
                f.setColor(hex2color.apply(fg));
                cs.setFont(f);
                cs.setFillForegroundColor(hex2color.apply(bg));
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cs.setAlignment(HorizontalAlignment.CENTER);
                cs.setVerticalAlignment(VerticalAlignment.CENTER);
                cs.setBorderBottom(BorderStyle.THIN); cs.setBorderTop(BorderStyle.THIN);
                cs.setBorderLeft(BorderStyle.THIN);   cs.setBorderRight(BorderStyle.THIN);
                cs.setWrapText(true);
                return cs;
            };

            XSSFCellStyle headerStyle = mkHeader.apply("1A1A2E", "FFFFFF");

            XSSFCellStyle titleStyle = wb.createCellStyle();
            { XSSFFont f = wb.createFont(); f.setBold(true); f.setFontName("Arial");
                f.setFontHeightInPoints((short) 14); f.setColor(hex2color.apply("C9A84C"));
                titleStyle.setFont(f);
                titleStyle.setFillForegroundColor(hex2color.apply("1A1A2E"));
                titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                titleStyle.setAlignment(HorizontalAlignment.LEFT);
                titleStyle.setVerticalAlignment(VerticalAlignment.CENTER); }

            XSSFCellStyle metaStyle = wb.createCellStyle();
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9);
                f.setColor(hex2color.apply("888888")); metaStyle.setFont(f);
                metaStyle.setFillForegroundColor(hex2color.apply("F9F9F9"));
                metaStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                metaStyle.setAlignment(HorizontalAlignment.LEFT);
                metaStyle.setVerticalAlignment(VerticalAlignment.CENTER); }

            XSSFCellStyle dataStyle = wb.createCellStyle();
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 10);
                dataStyle.setFont(f);
                dataStyle.setBorderBottom(BorderStyle.THIN); dataStyle.setBorderLeft(BorderStyle.THIN);
                dataStyle.setBorderRight(BorderStyle.THIN);
                dataStyle.setVerticalAlignment(VerticalAlignment.TOP);
                dataStyle.setWrapText(true); }

            XSSFCellStyle centerStyle = wb.createCellStyle();
            centerStyle.cloneStyleFrom(dataStyle);
            centerStyle.setAlignment(HorizontalAlignment.CENTER);
            centerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            XSSFCellStyle sttStyle = wb.createCellStyle();
            sttStyle.cloneStyleFrom(centerStyle);
            sttStyle.setFillForegroundColor(hex2color.apply("FAFAFA"));
            sttStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFCellStyle sumStyle = wb.createCellStyle();
            sumStyle.cloneStyleFrom(dataStyle);
            sumStyle.setFillForegroundColor(hex2color.apply("FFF8E7"));
            sumStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sumStyle.setAlignment(HorizontalAlignment.CENTER);
            sumStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setBold(true); f.setFontName("Arial");
                f.setFontHeightInPoints((short) 10); sumStyle.setFont(f); }

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
            ZoneId vnZone = ZoneId.of("Asia/Ho_Chi_Minh");
            String fromStr    = fromMs != null ? Instant.ofEpochMilli(fromMs).atZone(vnZone).format(dtf) : "Tất cả";
            String toStr      = toMs   != null ? Instant.ofEpochMilli(toMs).atZone(vnZone).format(dtf)   : "Tất cả";
            String exportedAt = LocalDateTime.now(vnZone).format(dtf);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            @SuppressWarnings("unchecked")
            java.util.function.Function<Order, List<Map<String, Object>>> parseInfo = order -> {
                String json = order.getDeliveryInfoJson();
                if (json == null || json.isBlank()) return List.of();
                try { return mapper.readValue(json, List.class); }
                catch (Exception e) { return List.of(); }
            };

            List<Order> validOrders = new ArrayList<>();
            for (Order order : orders) {
                String addr = order.getDeliveryAddress();
                if (addr == null || addr.isBlank()) addr = order.getShippingAddress();
                if (addr == null || addr.isBlank()) continue;
                if (parseInfo.apply(order).isEmpty()) continue;
                validOrders.add(order);
            }

            // ── SHEET 1: Báo cáo giao hàng ──────────────────────────────────────
            XSSFSheet sheet1 = wb.createSheet("Báo cáo giao hàng");
            sheet1.setDisplayGridlines(false);

            Map<String, int[]> summary = new java.util.LinkedHashMap<>();
            for (Order order : validOrders) {
                for (Map<String, Object> d : parseInfo.apply(order)) {
                    String name  = String.valueOf(d.getOrDefault("name", ""));
                    String type  = String.valueOf(d.getOrDefault("type", "MOTORBIKE"));
                    int    trips = ((Number) d.getOrDefault("trips", 1)).intValue();
                    summary.computeIfAbsent(name, k -> new int[]{0, 0});
                    if ("TRUCK".equals(type)) summary.get(name)[1] += trips;
                    else                      summary.get(name)[0] += trips;
                }
            }

            int rn = 0;

            Row t1 = sheet1.createRow(rn++); t1.setHeightInPoints(32);
            sheet1.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
            Cell tc1 = t1.createCell(0); tc1.setCellValue("BÁO CÁO GIAO HÀNG"); tc1.setCellStyle(titleStyle);

            Row m1 = sheet1.createRow(rn++);
            sheet1.addMergedRegion(new CellRangeAddress(1, 1, 0, 4));
            Cell mc1 = m1.createCell(0);
            mc1.setCellValue(String.format("Thời gian: %s → %s | Xuất lúc: %s | Số tài xế: %d",
                    fromStr, toStr, exportedAt, summary.size()));
            mc1.setCellStyle(metaStyle);

            sheet1.createRow(rn++).setHeightInPoints(4);

            Row hdr1 = sheet1.createRow(rn++); hdr1.setHeightInPoints(28);
            String[] hdr1Cols = {"STT", "Tên nhân viên giao", "Số phiếu xe máy", "Số phiếu xe tải", "Tổng phiếu"};
            int[]    hdr1W    = {8, 28, 20, 20, 16};
            for (int i = 0; i < hdr1Cols.length; i++) {
                Cell c = hdr1.createCell(i); c.setCellValue(hdr1Cols[i]); c.setCellStyle(headerStyle);
                sheet1.setColumnWidth(i, hdr1W[i] * 256);
            }
            sheet1.createFreezePane(0, 4);

            int stt1 = 1, sumMotorbike = 0, sumTruck = 0;
            for (Map.Entry<String, int[]> e : summary.entrySet()) {
                int motorbike = e.getValue()[0], truck = e.getValue()[1], total = motorbike + truck;
                sumMotorbike += motorbike; sumTruck += truck;
                Row row = sheet1.createRow(rn++); row.setHeightInPoints(20);
                Cell c0 = row.createCell(0); c0.setCellValue(stt1++); c0.setCellStyle(sttStyle);
                Cell c1 = row.createCell(1); c1.setCellValue(e.getKey()); c1.setCellStyle(dataStyle);
                Cell c2 = row.createCell(2); c2.setCellValue(motorbike); c2.setCellStyle(centerStyle);
                Cell c3 = row.createCell(3); c3.setCellValue(truck);     c3.setCellStyle(centerStyle);
                Cell c4 = row.createCell(4); c4.setCellValue(total);     c4.setCellStyle(centerStyle);
            }

            Row sumRow1 = sheet1.createRow(rn++); sumRow1.setHeightInPoints(24);
            Cell s0 = sumRow1.createCell(0); s0.setCellValue("∑"); s0.setCellStyle(sumStyle);
            Cell s1 = sumRow1.createCell(1); s1.setCellValue("Tổng cộng"); s1.setCellStyle(sumStyle);
            Cell s2 = sumRow1.createCell(2); s2.setCellValue(sumMotorbike); s2.setCellStyle(sumStyle);
            Cell s3 = sumRow1.createCell(3); s3.setCellValue(sumTruck);     s3.setCellStyle(sumStyle);
            Cell s4 = sumRow1.createCell(4); s4.setCellValue(sumMotorbike + sumTruck); s4.setCellStyle(sumStyle);

            if (rn > 4) sheet1.setAutoFilter(new CellRangeAddress(3, rn - 2, 0, 4));

            // ── SHEET 2: Chi tiết giao hàng ──────────────────────────────────────
            XSSFSheet sheet2 = wb.createSheet("Chi tiết giao hàng");
            sheet2.setDisplayGridlines(false);
            rn = 0;

            Row t2 = sheet2.createRow(rn++); t2.setHeightInPoints(32);
            sheet2.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
            Cell tc2 = t2.createCell(0); tc2.setCellValue("CHI TIẾT GIAO HÀNG"); tc2.setCellStyle(titleStyle);

            Row m2 = sheet2.createRow(rn++);
            sheet2.addMergedRegion(new CellRangeAddress(1, 1, 0, 5));
            Cell mc2 = m2.createCell(0);
            mc2.setCellValue(String.format("Thời gian: %s → %s | Xuất lúc: %s | Tổng đơn: %d",
                    fromStr, toStr, exportedAt, validOrders.size()));
            mc2.setCellStyle(metaStyle);

            sheet2.createRow(rn++).setHeightInPoints(4);

            Row hdr2 = sheet2.createRow(rn++); hdr2.setHeightInPoints(28);
            String[] hdr2Cols = {"STT", "Mã đơn", "Địa chỉ giao hàng", "Loại", "Người giao", "Thời gian giao"};
            int[]    hdr2W    = {8, 20, 50, 14, 35, 22};
            for (int i = 0; i < hdr2Cols.length; i++) {
                Cell c = hdr2.createCell(i); c.setCellValue(hdr2Cols[i]); c.setCellStyle(headerStyle);
                sheet2.setColumnWidth(i, hdr2W[i] * 256);
            }
            sheet2.createFreezePane(0, 4);

            int stt2 = 1;
            for (Order order : validOrders) {
                String addr = order.getDeliveryAddress();
                if (addr == null || addr.isBlank()) addr = order.getShippingAddress();
                String deliveryTime = getDeliveryTimeFromLogs(order.getId());
                List<Map<String, Object>> dInfoList = parseInfo.apply(order);

                Map<String, List<String>> byType = new java.util.LinkedHashMap<>();
                for (Map<String, Object> d : dInfoList) {
                    String type  = String.valueOf(d.getOrDefault("type", "MOTORBIKE"));
                    String name  = String.valueOf(d.getOrDefault("name", ""));
                    int    trips = ((Number) d.getOrDefault("trips", 1)).intValue();
                    String label = trips > 1 ? name + " x" + trips + " lượt" : name;
                    byType.computeIfAbsent(type, k -> new ArrayList<>()).add(label);
                }

                List<Map.Entry<String, List<String>>> entries = new ArrayList<>(byType.entrySet());
                int lineCount = entries.size();
                int firstRow  = rn;

                for (int li = 0; li < lineCount; li++) {
                    Row row = sheet2.createRow(rn++); row.setHeightInPoints(20);
                    Map.Entry<String, List<String>> entry = entries.get(li);

                    Cell c0 = row.createCell(0);
                    if (li == 0) { c0.setCellValue(stt2++); c0.setCellStyle(sttStyle); }
                    else         { c0.setCellStyle(sttStyle); }

                    Cell c1 = row.createCell(1);
                    if (li == 0) c1.setCellValue(order.getOrderCode());
                    c1.setCellStyle(dataStyle);

                    Cell c2 = row.createCell(2);
                    if (li == 0) c2.setCellValue(addr);
                    c2.setCellStyle(dataStyle);

                    Cell c3 = row.createCell(3);
                    c3.setCellValue("TRUCK".equals(entry.getKey()) ? "Xe tải" : "Xe máy");
                    c3.setCellStyle(centerStyle);

                    Cell c4 = row.createCell(4);
                    c4.setCellValue(String.join("\n", entry.getValue()));
                    c4.setCellStyle(dataStyle);

                    Cell c5 = row.createCell(5);
                    if (li == 0) c5.setCellValue(deliveryTime);
                    c5.setCellStyle(dataStyle);
                }

                if (lineCount > 1) {
                    for (int col : new int[]{0, 1, 2, 5}) {
                        sheet2.addMergedRegion(new CellRangeAddress(firstRow, rn - 1, col, col));
                    }
                }
            }

            if (rn > 4) sheet2.setAutoFilter(new CellRangeAddress(3, rn - 1, 0, 5));

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private String getDeliveryTimeFromLogs(Long orderId) {
        var logs = orderLogRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        return logs.stream()
                .filter(log -> "DELIVERING".equals(log.getAction()))
                .findFirst()
                .map(log -> LocalDateTime.ofInstant(Instant.ofEpochMilli(log.getCreatedAt()), TZ)
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
                .orElse("—");
    }

    private String formatDeliveryInfo(String deliveryInfoJson, String warehouseName) {
        if (deliveryInfoJson == null || deliveryInfoJson.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = mapper.readValue(deliveryInfoJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            if (list == null || list.isEmpty()) return "";

            boolean allAtWarehouse = list.stream().allMatch(d ->
                    "Kho giao tại kho".equalsIgnoreCase(String.valueOf(d.getOrDefault("name", ""))));
            if (allAtWarehouse) {
                return "Giao tại kho\n" + (warehouseName != null ? warehouseName : "");
            }

            Map<String, List<String>> byType = new java.util.LinkedHashMap<>();
            for (Map<String, Object> d : list) {
                String type  = String.valueOf(d.getOrDefault("type", "MOTORBIKE"));
                String name  = String.valueOf(d.getOrDefault("name", ""));
                int    trips = d.get("trips") instanceof Number n ? n.intValue() : 1;
                String label = name + " x" + trips + " lượt";
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(label);
            }

            StringBuilder sb = new StringBuilder();
            byType.forEach((type, names) -> {
                if (sb.length() > 0) sb.append("\n");
                sb.append("TRUCK".equals(type) ? "Xe tải" : "Xe máy").append("\n");
                sb.append(String.join("\n", names));
            });
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}