package com.nhatnam.server.restcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.config.TransactionLockManager;
import com.nhatnam.server.dto.InvoiceDTO;
import com.nhatnam.server.dto.request.*;
import com.nhatnam.server.dto.response.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.exception.PriceChangedException;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.*;
import com.nhatnam.server.utils.InvoicePdf;
import com.nhatnam.server.utils.OrderExcelExporter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Log4j2
@RequestMapping("/api/seller")
public class SellerController {
    private final InvoicePdf                invoicePdf;
    private final TransactionLockManager    transactionLockManager;
    private final ProductService            productService;
    private final OrderService              orderService;
    private final IngredientService         ingredientService;
    private final FileStorageService        fileStorageService;
    private final CategoryService           categoryService;
    private final CustomerService           customerService;
    private final com.nhatnam.server.service.SuperSellerOrderService superSellerOrderService;
    private final ObjectMapper              objectMapper;
    private final CustomerRepository        customerRepository;
    private final CustomerReceiverInfoRepository receiverInfoRepository;
    private final OrderRepository orderRepository;
    private final OrderExcelExporter exporter;
    private final SellerKpiRepository sellerKpiRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/orders/export-delivery-report")
    public ResponseEntity<?> exportDeliveryReport(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        try {
            User currentUser = (User) authentication.getPrincipal();

            // Lấy tất cả đơn hàng của seller hiện tại
            List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);

            // Filter theo thời gian nếu có
            if (from != null) {
                final long f = from;
                orders = orders.stream()
                        .filter(o -> o.getCreatedAt() != null && o.getCreatedAt() >= f)
                        .collect(Collectors.toList());
            }
            if (to != null) {
                final long t = to;
                orders = orders.stream()
                        .filter(o -> o.getCreatedAt() != null && o.getCreatedAt() <= t)
                        .collect(Collectors.toList());
            }

            // Chỉ lấy đơn có trạng thái DELIVERING hoặc đã hoàn thành (đã có thời gian giao)
            orders = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.DELIVERING
                            || o.getStatus() == OrderStatus.COMPLETED
                            || o.getStatus() == OrderStatus.PENDING_PAYMENT)
                    .collect(Collectors.toList());

            // Lọc tiếp trong exporter (đơn có delivery_address và driver)
            // Truyền toàn bộ orders, exporter sẽ tự lọc
            byte[] excelBytes = exporter.exportDeliveryReport(orders, from, to);

            String fromStr = from != null ? Instant.ofEpochMilli(from).atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd")) : "all";
            String toStr = to != null ? Instant.ofEpochMilli(to).atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd")) : "all";
            String filename = String.format("bao-cao-giao-hang_%s_%s.xlsx", fromStr, toStr);

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(excelBytes);

        } catch (Exception e) {
            log.error("[SELLER] exportDeliveryReport error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders/export-ingredients")
    public ResponseEntity<?> exportIngredients(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        try {
            // Lấy tất cả đơn hàng (không phân biệt ai tạo), filter theo thời gian
            List<Order> orders = orderRepository.findAllByOrderByCreatedAtDesc();

            if (from != null) {
                final long f = from;
                orders = orders.stream()
                        .filter(o -> o.getCreatedAt() != null && o.getCreatedAt() >= f)
                        .toList();
            }
            if (to != null) {
                final long t = to;
                orders = orders.stream()
                        .filter(o -> o.getCreatedAt() != null && o.getCreatedAt() <= t)
                        .toList();
            }

            // Loại bỏ đơn hủy
            orders = orders.stream()
                    .filter(o -> o.getStatus() != com.nhatnam.server.enumtype.OrderStatus.CANCELLED)
                    .toList();

            int totalOrders = orders.size();

            // Build: Map<customerName, Map<ingredientName, IngredientAgg>>
            // IngredientAgg: unit, totalQty
            byte[] bytes = _buildIngredientExcel(
                    orders,
                    from,
                    to,
                    totalOrders
            );

            String filename = "nguyen-lieu-" + LocalDate.now() + ".xlsx";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);

        } catch (Exception e) {
            log.error("[SELLER] exportIngredients error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private byte[] _buildIngredientExcel(
            List<Order> orders,
            Long fromMs,
            Long toMs,
            int totalOrders) throws Exception {

        java.time.ZoneId VN = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        java.time.format.DateTimeFormatter dtf     = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        java.time.format.DateTimeFormatter dtfTime = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        String fromStr    = fromMs != null ? java.time.Instant.ofEpochMilli(fromMs).atZone(VN).format(dtf) : "—";
        String toStr      = toMs   != null ? java.time.Instant.ofEpochMilli(toMs).atZone(VN).format(dtf)   : "—";
        String exportedAt = java.time.LocalDateTime.now(VN).format(dtfTime);

        record IngredientRow(String ingredientName, String unit, double qty) {}

        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet ws = wb.createSheet("Báo cáo nguyên liệu");
            ws.setDisplayGridlines(false);

            // ── hex → XSSFColor ───────────────────────────────────────────────────
            java.util.function.Function<String, XSSFColor> hex2color = hex -> new XSSFColor(
                    new byte[]{
                            (byte) Integer.parseInt(hex.substring(0, 2), 16),
                            (byte) Integer.parseInt(hex.substring(2, 4), 16),
                            (byte) Integer.parseInt(hex.substring(4, 6), 16)
                    }, null);

            // ── Base style supplier ───────────────────────────────────────────────
            java.util.function.Supplier<XSSFCellStyle> base = () -> {
                XSSFCellStyle cs = wb.createCellStyle();
                XSSFColor border = hex2color.apply("E0E0E0");
                cs.setBorderLeft(BorderStyle.THIN);
                cs.setBorderColor(org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.LEFT,   border);
                cs.setBorderRight(BorderStyle.THIN);
                cs.setBorderColor(org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.RIGHT,  border);
                cs.setBorderTop(BorderStyle.THIN);
                cs.setBorderColor(org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.TOP,    border);
                cs.setBorderBottom(BorderStyle.THIN);
                cs.setBorderColor(org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.BOTTOM, border);
                cs.setVerticalAlignment(VerticalAlignment.CENTER);
                cs.setWrapText(true);
                return cs;
            };

            // ── Styles ───────────────────────────────────────────────────────────

            // Title
            XSSFCellStyle titleStyle = base.get();
            titleStyle.setFillForegroundColor(hex2color.apply("1A1A2E"));
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setAlignment(HorizontalAlignment.LEFT);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 16); f.setColor(hex2color.apply("C9A84C")); titleStyle.setFont(f); }

            // Meta
            XSSFCellStyle metaStyle = wb.createCellStyle();
            metaStyle.setFillForegroundColor(hex2color.apply("F9F9F9"));
            metaStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            metaStyle.setAlignment(HorizontalAlignment.LEFT);
            metaStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("888888")); metaStyle.setFont(f); }

            // Header
            XSSFCellStyle headerStyle = base.get();
            headerStyle.setFillForegroundColor(hex2color.apply("1A1A2E"));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 10); f.setColor(hex2color.apply("FFFFFF")); headerStyle.setFont(f); }

            // Data zebra A — left, 9pt dark
            XSSFCellStyle dataA = base.get();
            dataA.setFillForegroundColor(hex2color.apply("FAFAFA"));
            dataA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            dataA.setAlignment(HorizontalAlignment.LEFT);
            dataA.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); dataA.setFont(f); }

            // Data zebra B
            XSSFCellStyle dataB = base.get();
            dataB.setFillForegroundColor(hex2color.apply("FFFFFF"));
            dataB.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            dataB.setAlignment(HorizontalAlignment.LEFT);
            dataB.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); dataB.setFont(f); }

            // STT zebra A — center+center (1 dòng)
            XSSFCellStyle sttA = base.get();
            sttA.setFillForegroundColor(hex2color.apply("FAFAFA"));
            sttA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sttA.setAlignment(HorizontalAlignment.CENTER);
            sttA.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 10); f.setColor(hex2color.apply("C9A84C")); sttA.setFont(f); }

            // STT zebra A — center+bottom (merge)
            XSSFCellStyle sttAbot = base.get();
            sttAbot.setFillForegroundColor(hex2color.apply("FAFAFA"));
            sttAbot.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sttAbot.setAlignment(HorizontalAlignment.CENTER);
            sttAbot.setVerticalAlignment(VerticalAlignment.BOTTOM);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 10); f.setColor(hex2color.apply("C9A84C")); sttAbot.setFont(f); }

            // STT zebra B — center+center
            XSSFCellStyle sttB = base.get();
            sttB.setFillForegroundColor(hex2color.apply("FFFFFF"));
            sttB.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sttB.setAlignment(HorizontalAlignment.CENTER);
            sttB.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 10); f.setColor(hex2color.apply("C9A84C")); sttB.setFont(f); }

            // STT zebra B — center+bottom (merge)
            XSSFCellStyle sttBbot = base.get();
            sttBbot.setFillForegroundColor(hex2color.apply("FFFFFF"));
            sttBbot.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sttBbot.setAlignment(HorizontalAlignment.CENTER);
            sttBbot.setVerticalAlignment(VerticalAlignment.BOTTOM);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 10); f.setColor(hex2color.apply("C9A84C")); sttBbot.setFont(f); }

            // Info zebra A — left+center (1 dòng)
            XSSFCellStyle infoA = base.get();
            infoA.setFillForegroundColor(hex2color.apply("FAFAFA"));
            infoA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            infoA.setAlignment(HorizontalAlignment.LEFT);
            infoA.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); infoA.setFont(f); }

            // Info zebra A — left+bottom (merge)
            XSSFCellStyle infoAbot = base.get();
            infoAbot.setFillForegroundColor(hex2color.apply("FAFAFA"));
            infoAbot.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            infoAbot.setAlignment(HorizontalAlignment.LEFT);
            infoAbot.setVerticalAlignment(VerticalAlignment.BOTTOM);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); infoAbot.setFont(f); }

            // Info zebra B — left+center
            XSSFCellStyle infoB = base.get();
            infoB.setFillForegroundColor(hex2color.apply("FFFFFF"));
            infoB.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            infoB.setAlignment(HorizontalAlignment.LEFT);
            infoB.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); infoB.setFont(f); }

            // Info zebra B — left+bottom (merge)
            XSSFCellStyle infoBbot = base.get();
            infoBbot.setFillForegroundColor(hex2color.apply("FFFFFF"));
            infoBbot.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            infoBbot.setAlignment(HorizontalAlignment.LEFT);
            infoBbot.setVerticalAlignment(VerticalAlignment.BOTTOM);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); infoBbot.setFont(f); }

            // Customer zebra A
            XSSFCellStyle custA = base.get();
            custA.setFillForegroundColor(hex2color.apply("FAFAFA"));
            custA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            custA.setAlignment(HorizontalAlignment.LEFT);
            custA.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1A1A2E")); custA.setFont(f); }

            // Customer zebra B
            XSSFCellStyle custB = base.get();
            custB.setFillForegroundColor(hex2color.apply("FFFFFF"));
            custB.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            custB.setAlignment(HorizontalAlignment.LEFT);
            custB.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1A1A2E")); custB.setFont(f); }

            // Number format
            XSSFDataFormat fmtObj = wb.createDataFormat();
            short numFmt = fmtObj.getFormat("#,##0.##");

            // Num zebra A
            XSSFCellStyle numA = base.get();
            numA.setFillForegroundColor(hex2color.apply("FAFAFA"));
            numA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            numA.setAlignment(HorizontalAlignment.RIGHT);
            numA.setVerticalAlignment(VerticalAlignment.CENTER);
            numA.setDataFormat(numFmt);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); numA.setFont(f); }

            // Num zebra B
            XSSFCellStyle numB = base.get();
            numB.setFillForegroundColor(hex2color.apply("FFFFFF"));
            numB.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            numB.setAlignment(HorizontalAlignment.RIGHT);
            numB.setVerticalAlignment(VerticalAlignment.CENTER);
            numB.setDataFormat(numFmt);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setBold(true); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("1C1C1E")); numB.setFont(f); }

            // Unit zebra A
            XSSFCellStyle unitA = base.get();
            unitA.setFillForegroundColor(hex2color.apply("FAFAFA"));
            unitA.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            unitA.setAlignment(HorizontalAlignment.CENTER);
            unitA.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("6B7280")); unitA.setFont(f); }

            // Unit zebra B
            XSSFCellStyle unitB = base.get();
            unitB.setFillForegroundColor(hex2color.apply("FFFFFF"));
            unitB.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            unitB.setAlignment(HorizontalAlignment.CENTER);
            unitB.setVerticalAlignment(VerticalAlignment.CENTER);
            { XSSFFont f = wb.createFont(); f.setFontName("Arial"); f.setFontHeightInPoints((short) 9); f.setColor(hex2color.apply("6B7280")); unitB.setFont(f); }

            // ── Column widths (6 cột) ─────────────────────────────────────────────
            int[] colWidths = { 10, 36, 28, 40, 12, 14 };
            for (int i = 0; i < colWidths.length; i++) ws.setColumnWidth(i, colWidths[i] * 256);

            // ── Row 0: Title ──────────────────────────────────────────────────────
            int rowNum = 0;
            Row r0 = ws.createRow(rowNum++);
            r0.setHeightInPoints(36);
            ws.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
            Cell tc = r0.createCell(0);
            tc.setCellValue("BÁO CÁO NGUYÊN LIỆU BÁN RA");
            tc.setCellStyle(titleStyle);

            // ── Row 1: Meta ───────────────────────────────────────────────────────
            Row r1 = ws.createRow(rowNum++);
            r1.setHeightInPoints(20);
            ws.addMergedRegion(new CellRangeAddress(1, 1, 0, 5));
            Cell mc1 = r1.createCell(0);
            mc1.setCellValue("Khoảng thời gian: " + fromStr + "  →  " + toStr
                    + "   |   Thời gian xuất: " + exportedAt
                    + "   |   Tổng đơn: " + totalOrders);
            mc1.setCellStyle(metaStyle);

            // ── Row 2: Separator ──────────────────────────────────────────────────
            Row r2 = ws.createRow(rowNum++);
            r2.setHeightInPoints(4);
            for (int i = 0; i < 6; i++) {
                Cell sep = r2.createCell(i);
                sep.setCellValue("");
                sep.setCellStyle(metaStyle);
            }

            // ── Row 3: Header ─────────────────────────────────────────────────────
            Row hdrRow = ws.createRow(rowNum++);
            hdrRow.setHeightInPoints(28);
            String[] hdrs = { "STT", "Thông tin", "Tên khách hàng", "Tên nguyên liệu", "Đơn vị", "Số lượng" };
            for (int i = 0; i < hdrs.length; i++) {
                Cell hc = hdrRow.createCell(i);
                hc.setCellValue(hdrs[i]);
                hc.setCellStyle(headerStyle);
            }

            ws.createFreezePane(0, 4);

            // ── Data rows ─────────────────────────────────────────────────────────
            int stt = 1;
            DateTimeFormatter orderTimeFmt = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

            // Bước 1: group orders theo customerName (giữ thứ tự xuất hiện)
            java.util.LinkedHashMap<String, List<Order>> ordersByCustomer = new java.util.LinkedHashMap<>();
            for (Order order : orders) {
                String key = order.getCustomerName() != null
                        ? order.getCustomerName().trim()
                        : "Khách lẻ";
                ordersByCustomer.computeIfAbsent(key, k -> new ArrayList<>()).add(order);
            }

            // Bước 2: render từng customer
            for (var entry : ordersByCustomer.entrySet()) {

                String customerName    = entry.getKey();
                List<Order> custOrders = entry.getValue();

                // Build infoText: mỗi đơn 1 dòng "HH:mm dd/MM/yyyy - NĐ-XXXXX"
                String infoText = custOrders.stream()
                        .filter(o -> o.getCreatedAt() != null || o.getOrderCode() != null)
                        .map(o -> {
                            String t    = o.getCreatedAt() != null
                                    ? Instant.ofEpochMilli(o.getCreatedAt()).atZone(VN).format(orderTimeFmt)
                                    : "";
                            String code = o.getOrderCode() != null ? o.getOrderCode() : "";
                            return t + " - " + code;
                        })
                        .collect(java.util.stream.Collectors.joining("\n"));

                int infoLineCount = (int) infoText.chars().filter(c -> c == '\n').count() + 1;

                // Group + cộng dồn nguyên liệu
                java.util.LinkedHashMap<String, IngredientRow> grouped = new java.util.LinkedHashMap<>();
                for (Order order : custOrders) {
                    if (order.getOrderItems() == null) continue;
                    for (var item : order.getOrderItems()) {
                        if (item.getOrderItemIngredients() == null) continue;
                        for (var ing : item.getOrderItemIngredients()) {
                            String ingName = ing.getIngredientName();
                            if (ingName == null || ingName.isBlank()) continue;
                            String unit = ing.getUnit() != null ? ing.getUnit() : "";
                            double qty  = ing.getQuantityUsed() != null ? ing.getQuantityUsed().doubleValue() : 0;
                            String k    = ingName.trim() + "|" + unit.trim();
                            grouped.merge(k,
                                    new IngredientRow(ingName.trim(), unit, qty),
                                    (ex, in) -> new IngredientRow(ex.ingredientName(), ex.unit(), ex.qty() + in.qty()));
                        }
                    }
                }

                if (grouped.isEmpty()) continue;

                List<IngredientRow> ingredientRows = new ArrayList<>(grouped.values());
                boolean isMerged = ingredientRows.size() > 1;
                boolean zebra    = (stt % 2 == 1);

                // Chọn style theo zebra + có merge hay không
                XSSFCellStyle sStyle = zebra ? (isMerged ? sttAbot  : sttA)  : (isMerged ? sttBbot  : sttB);
                XSSFCellStyle iStyle = zebra ? (isMerged ? infoAbot : infoA) : (isMerged ? infoBbot : infoB);
                XSSFCellStyle dStyle = zebra ? dataA : dataB;
                XSSFCellStyle cStyle = zebra ? custA : custB;
                XSSFCellStyle nStyle = zebra ? numA  : numB;
                XSSFCellStyle uStyle = zebra ? unitA : unitB;

                int startRow = rowNum;

                for (int i = 0; i < ingredientRows.size(); i++) {
                    IngredientRow ingRow = ingredientRows.get(i);

                    Row excelRow = ws.createRow(rowNum++);
                    // Dòng đầu cao theo số dòng infoText, các dòng sau chuẩn 22pt
                    excelRow.setHeightInPoints(i == 0 ? Math.max(22f, infoLineCount * 14f) : 22f);

                    // Col 0: STT — chỉ dòng đầu
                    Cell c0 = excelRow.createCell(0);
                    c0.setCellStyle(sStyle);
                    if (i == 0) c0.setCellValue(stt);

                    // Col 1: Thông tin — chỉ dòng đầu
                    Cell c1 = excelRow.createCell(1);
                    c1.setCellStyle(iStyle);
                    if (i == 0) c1.setCellValue(infoText);

                    // Col 2: Tên khách hàng — LẶP LẠI mỗi dòng (không merge, để filter đúng)
                    Cell c2 = excelRow.createCell(2);
                    c2.setCellStyle(cStyle);
                    c2.setCellValue(customerName);

                    // Col 3: Tên nguyên liệu
                    Cell c3 = excelRow.createCell(3);
                    c3.setCellStyle(dStyle);
                    c3.setCellValue(ingRow.ingredientName());

                    // Col 4: Đơn vị
                    Cell c4 = excelRow.createCell(4);
                    c4.setCellStyle(uStyle);
                    c4.setCellValue(ingRow.unit());

                    // Col 5: Số lượng
                    Cell c5 = excelRow.createCell(5);
                    c5.setCellStyle(nStyle);
                    c5.setCellValue(ingRow.qty());
                }

                int endRow = rowNum - 1;

                // Chỉ merge STT và Thông tin — KHÔNG merge Tên khách hàng
                if (isMerged) {
                    ws.addMergedRegion(new CellRangeAddress(startRow, endRow, 0, 0)); // STT
                    ws.addMergedRegion(new CellRangeAddress(startRow, endRow, 1, 1)); // Thông tin
                }

                stt++;
            }

            // ── AutoFilter ────────────────────────────────────────────────────────
            ws.setAutoFilter(new CellRangeAddress(3, rowNum - 1, 0, 5));

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    @PatchMapping("/customers/{customerId}/receiver-infos/batch")
    @Transactional
    public ResponseEntity<ApiResponse<Object>> batchReceiverOps(
            @PathVariable Long customerId,
            @RequestBody List<Map<String, Object>> ops) {
        try {
            if (!customerRepository.existsById(customerId))
                throw new RuntimeException("Không tìm thấy KH #" + customerId);

            for (Map<String, Object> op : ops) {
                String type  = _str(op, "op");
                Object idRaw = op.get("id");
                Long   id    = idRaw instanceof Number n ? n.longValue() : null;

                switch (type != null ? type : "") {

                    case "add" -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) op.get("data");
                        if (data == null) continue;

                        String address = _str(data, "receiverAddress");
                        if (address == null || address.isBlank()) continue;

                        String phone = _str(data, "receiverPhone");
                        String name  = _str(data, "receiverName");
                        boolean isDefault = Boolean.TRUE.equals(data.get("isDefault"));

                        Customer customer = customerRepository.getReferenceById(customerId);
                        CustomerReceiverInfo info = CustomerReceiverInfo.builder()
                                .customer(customer)
                                .receiverName(name  != null && !name.isBlank()  ? name.trim()  : null)
                                .receiverPhone(phone != null && !phone.isBlank() ? phone.trim() : null)
                                .receiverAddress(address.trim())
                                .isDefault(false)
                                .build();
                        receiverInfoRepository.save(info);

                        // Nếu isDefault = true thì set default sau khi save
                        if (isDefault) {
                            List<CustomerReceiverInfo> all = receiverInfoRepository.findByCustomerId(customerId);
                            all.forEach(r -> r.setIsDefault(r.getId().equals(info.getId())));
                            receiverInfoRepository.saveAll(all);
                        }
                    }

                    case "update" -> {
                        if (id == null) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) op.get("data");
                        if (data == null) continue;

                        receiverInfoRepository.findById(id).ifPresent(info -> {
                            String addr  = _str(data, "receiverAddress");
                            String name  = _str(data, "receiverName");
                            String phone = _str(data, "receiverPhone");

                            if (addr != null && !addr.isBlank())
                                info.setReceiverAddress(addr.trim());
                            info.setReceiverName(name   != null && !name.isBlank()   ? name.trim()   : null);
                            info.setReceiverPhone(phone != null && !phone.isBlank() ? phone.trim() : null);
                            receiverInfoRepository.save(info);
                        });
                    }

                    case "delete" -> {
                        if (id == null) continue;
                        receiverInfoRepository.findById(id).ifPresent(info -> {
                            boolean wasDefault = Boolean.TRUE.equals(info.getIsDefault());
                            receiverInfoRepository.delete(info);
                            // Nếu xóa cái default → tự động set cái đầu tiên còn lại
                            if (wasDefault) {
                                List<CustomerReceiverInfo> remaining =
                                        receiverInfoRepository.findByCustomerId(customerId);
                                if (!remaining.isEmpty()) {
                                    remaining.get(0).setIsDefault(true);
                                    receiverInfoRepository.save(remaining.get(0));
                                }
                            }
                        });
                    }

                    case "setDefault" -> {
                        if (id == null) continue;
                        List<CustomerReceiverInfo> all = receiverInfoRepository.findByCustomerId(customerId);
                        // Chỉ set default nếu id tồn tại trong danh sách
                        boolean found = all.stream().anyMatch(r -> r.getId().equals(id));
                        if (!found) continue;
                        all.forEach(r -> r.setIsDefault(r.getId().equals(id)));
                        receiverInfoRepository.saveAll(all);
                    }
                }
            }

            return ResponseEntity.ok(ApiResponse.success(null, "OK"));

        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] batchReceiverOps error customerId={}", customerId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{orderId}/pending-payment")
    public ResponseEntity<ApiResponse<Object>> markPendingPayment(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

            User user = (User) authentication.getPrincipal();
            String actorName = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();

            // Kiểm tra trạng thái hiện tại
            OrderResponse order = orderService.getOrderById(orderId);
            if (!"DELIVERING".equals(order.getStatus())) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Chỉ được chuyển sang Chờ thanh toán khi đơn đang ở trạng thái Đang giao"));
            }

            orderService.markAsPendingPayment(orderId, actorName);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã chuyển sang Chờ thanh toán"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] markPendingPayment error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{orderId}/cancel")
    public ResponseEntity<ApiResponse<Object>> cancelOrder(
            @PathVariable Long orderId,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));
            String reason = body.get("reason");
            if (reason == null || reason.isBlank())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Vui lòng nhập lý do hủy đơn"));
            User user = (User) authentication.getPrincipal();
            String actorName = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();
            orderService.cancelOrder(orderId, user.getId(), actorName,
                    user.getRole().name(), reason);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã hủy đơn hàng"));
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] cancelOrder error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders/export")
    public ResponseEntity<?> exportMyOrders(
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
            String name = user.getFullName() != null ? user.getFullName() : user.getUsername();
            byte[] bytes = exporter.export(orders, "Đơn hàng của tôi", name);
            String filename = "don-hang-" + LocalDate.now() + ".xlsx";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(bytes);
        } catch (Exception e) {
            log.error("[SELLER] exportMyOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/customers/{customerId}/receiver-infos")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addReceiverInfo(
            @PathVariable Long customerId,
            @RequestBody Map<String, Object> req) {
        try {
            Customer customer = customerRepository.findById(customerId)
                    .filter(c -> c.getDeletedAt() == null)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy KH #" + customerId));


            String name    = _str(req, "receiverName");
            String phone   = _str(req, "receiverPhone");
            String address = _str(req, "receiverAddress");

            if (address == null || address.isBlank())
                throw new IllegalArgumentException("Thiếu địa chỉ nhận hàng");

            // Double-check trùng SĐT ở tầng backend (chỉ check nếu phone có giá trị)
            if (phone != null && !phone.isBlank() && receiverInfoRepository.existsByReceiverPhone(phone.trim()))
                throw new IllegalArgumentException("SĐT " + phone.trim() + " đã được đăng ký cho người nhận khác");

            CustomerReceiverInfo info = CustomerReceiverInfo.builder()
                    .customer(customer)
                    .receiverName(name != null && !name.isBlank() ? name.trim() : null)
                    .receiverPhone(phone != null && !phone.isBlank() ? phone.trim() : null)
                    .receiverAddress(address.trim())
                    .isDefault(false)
                    .build();

            info = receiverInfoRepository.save(info);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              info.getId());
            m.put("receiverName",    info.getReceiverName());
            m.put("receiverPhone",   info.getReceiverPhone());
            m.put("receiverAddress", info.getReceiverAddress());
            m.put("isDefault",       info.getIsDefault());
            m.put("createdAt",       info.getCreatedAt());

            return ResponseEntity.ok(ApiResponse.success(m, "Đã thêm người nhận"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] addReceiverInfo error customerId={}", customerId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/customers/{customerId}/receiver-infos/{receiverId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateReceiverInfo(
            @PathVariable Long customerId,
            @PathVariable Long receiverId,
            @RequestBody Map<String, Object> req) {
        try {
            CustomerReceiverInfo info = receiverInfoRepository.findByCustomerId(customerId)
                    .stream()
                    .filter(r -> r.getId().equals(receiverId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy người nhận #" + receiverId));

            String phone = _str(req, "receiverPhone");
            String address = _str(req, "receiverAddress");

            if (address != null && address.isBlank())
                throw new IllegalArgumentException("Địa chỉ nhận hàng không được để trống");

            // Chỉ check trùng nếu SĐT thay đổi và có giá trị
            if (phone != null && !phone.isBlank()
                    && !phone.trim().equals(info.getReceiverPhone())
                    && receiverInfoRepository.existsByReceiverPhone(phone.trim())) {
                throw new IllegalArgumentException("SĐT " + phone.trim() + " đã được đăng ký cho người nhận khác");
            }

            String name = _str(req, "receiverName");

            // Cho phép update (kể cả set null)
            if (req.containsKey("receiverName"))
                info.setReceiverName(name != null && !name.isBlank() ? name.trim() : null);
            if (req.containsKey("receiverPhone"))
                info.setReceiverPhone(phone != null && !phone.isBlank() ? phone.trim() : null);
            if (address != null && !address.isBlank())
                info.setReceiverAddress(address.trim());

            info = receiverInfoRepository.save(info);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              info.getId());
            m.put("receiverName",    info.getReceiverName());
            m.put("receiverPhone",   info.getReceiverPhone());
            m.put("receiverAddress", info.getReceiverAddress());
            m.put("isDefault",       info.getIsDefault());
            m.put("createdAt",       info.getCreatedAt());

            return ResponseEntity.ok(ApiResponse.success(m, "Đã cập nhật người nhận"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] updateReceiverInfo error customerId={} receiverId={}", customerId, receiverId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/customers/{customerId}/receiver-infos/{receiverId}/set-default")
    public ResponseEntity<ApiResponse<Object>> setDefaultReceiverInfo(
            @PathVariable Long customerId,
            @PathVariable Long receiverId) {
        try {
            List<CustomerReceiverInfo> infos = receiverInfoRepository.findByCustomerId(customerId);

            if (infos.stream().noneMatch(r -> r.getId().equals(receiverId)))
                throw new RuntimeException("Không tìm thấy người nhận #" + receiverId);

            // Bỏ default tất cả, set default cho cái được chọn
            infos.forEach(r -> r.setIsDefault(r.getId().equals(receiverId)));
            receiverInfoRepository.saveAll(infos);

            return ResponseEntity.ok(ApiResponse.success(null, "Đã đặt làm mặc định"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] setDefaultReceiverInfo error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/b2b")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getB2bCustomers(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        try {
            User currentUser = (User) auth.getPrincipal();

            // Thay toàn bộ phần check canSeeAll:

            Set<Role> userRoles = currentUser.getAllRoles();

            boolean canSeeAll = userRoles.contains(Role.ADMIN)
                    || userRoles.contains(Role.OWNER)
                    || userRoles.contains(Role.SUPERADMIN)
                    || userRoles.contains(Role.SUPER_SELLER);

            var stream = canSeeAll
                    ? customerRepository.findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc().stream()
                    : customerRepository.findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc().stream()
                    .filter(c -> {
                        Long uid = currentUser.getId();

                        // Khách cá nhân (RETAIL)
                        if (c.getCustomerType() == Customer.CustomerType.RETAIL) {
                            // Đã gán assignedSeller → chỉ người được gán thấy
                            if (c.getAssignedSeller() != null) {
                                return Objects.equals(c.getAssignedSeller().getId(), uid);
                            }
                            // Chưa gán → tất cả seller đều thấy
                            return true;
                        }

                        // Khách công ty (COMPANY)
                        if (c.getCustomerType() == Customer.CustomerType.COMPANY) {
                            // Được gán chăm sóc → thấy
                            if (c.getAssignedSeller() != null
                                    && Objects.equals(c.getAssignedSeller().getId(), uid)) {
                                return true;
                            }
                            // Do chính seller này tạo → thấy
                            if (c.getCreatedBySeller() != null
                                    && Objects.equals(c.getCreatedBySeller().getId(), uid)) {
                                return true;
                            }
                            // Admin/Owner tạo (createdBySeller == null) → tất cả seller thấy
                            if (c.getCreatedBySeller() == null) {
                                return true;
                            }
                            return false;
                        }
                        return false;
                    });

            if (type != null && !type.isBlank()) {
                final var t = Customer.CustomerType.valueOf(type.toUpperCase());
                stream = stream.filter(c -> c.getCustomerType() == t);
            }
            if (search != null && !search.isBlank()) {
                final var q = search.toLowerCase();
                stream = stream.filter(c ->
                        (c.getCustomerCode()  != null && c.getCustomerCode().toLowerCase().contains(q))  ||
                                (c.getCompanyName()   != null && c.getCompanyName().toLowerCase().contains(q))   ||
                                (c.getPhone()         != null && c.getPhone().contains(q))                       ||
                                (c.getName()          != null && c.getName().toLowerCase().contains(q))          ||
                                (c.getContactName()   != null && c.getContactName().toLowerCase().contains(q))
                );
            }

            var allList = stream.map(this::_toB2bMap).toList();
            int total = allList.size();
            int start = page * size;
            int end   = Math.min(start + size, total);
            var content = start >= total ? List.of() : allList.subList(start, end);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     content);
            result.put("totalItems",  total);
            result.put("currentPage", page);
            result.put("totalPages",  (int) Math.ceil((double) total / size));
            result.put("pageSize",    size);

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[SELLER] getB2bCustomers error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/b2b/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getB2bCustomerById(
            @PathVariable Long id) {
        try {
            Customer c = customerRepository.findById(id)
                    .filter(customer -> customer.getDeletedAt() == null)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy KH #" + id));

            return ResponseEntity.ok(ApiResponse.success(_toB2bMap(c), "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
// ORDER STATUS & PAYMENT METHOD (SELLER)
// ════════════════════════════════════════════════════════════════

    @PatchMapping("/orders/{orderId}/complete")
    public ResponseEntity<ApiResponse<OrderResponse>> completeOrder(
            @PathVariable Long orderId,
            Authentication authentication) {
        try {
            User user = (User) authentication.getPrincipal();
            OrderResponse order = orderService.getOrderById(orderId);

            // Chỉ cho phép: DELIVERING, PENDING_PAYMENT, PARTIAL (paymentStatus)
            boolean statusOk = order.getStatus().equals("DELIVERING")
                    || order.getStatus().equals("PENDING_PAYMENT");
            boolean partialOk = order.getPaymentStatus().equals("PARTIAL");

            if (!statusOk && !partialOk) {
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Chỉ được hoàn thành đơn ở trạng thái Đang giao / Chờ thanh toán / Thanh toán 1 phần"));
            }

            OrderResponse updated = orderService.completeOrderBySeller(orderId);
            return ResponseEntity.ok(ApiResponse.success(updated, "Đơn hàng đã hoàn thành"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] completeOrder error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /**
     * Sửa đơn hàng đang PREPARING: thay đổi món, số lượng, giá override.
     */
    @PutMapping("/orders/{orderId}/items")
    public ResponseEntity<ApiResponse<OrderResponse>> updateOrderItems(
            @PathVariable Long orderId,
            @RequestBody UpdateOrderItemsRequest req,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));
            User user = (User) authentication.getPrincipal();
            OrderResponse updated = orderService.updateOrderItems(orderId, user.getId(), req);
            return ResponseEntity.ok(ApiResponse.success(updated, "Đã cập nhật đơn hàng"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] updateOrderItems error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }


    // ════════════════════════════════════════════════════════════════
    // SUPER_SELLER — Sửa đơn hàng (mọi trạng thái trừ CANCELLED)
    // ════════════════════════════════════════════════════════════════

    /** Search nhân viên theo tên để điền vào ô "Người yêu cầu sửa" */
    @GetMapping("/orders/super-edit/staff-search")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> searchStaff(
            @RequestParam(defaultValue = "") String keyword) {
        return ResponseEntity.ok(ApiResponse.success(
                superSellerOrderService.searchStaff(keyword), "OK"));
    }

    /** Sửa đơn hàng — SUPER_SELLER */
    @PutMapping("/orders/{orderId}/super-edit")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ResponseEntity<ApiResponse<com.nhatnam.server.dto.response.OrderResponse>> superSellerUpdateOrder(
            @PathVariable Long orderId,
            @RequestBody SuperSellerUpdateOrderRequest req,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));
            com.nhatnam.server.entity.User user = (com.nhatnam.server.entity.User) authentication.getPrincipal();
            com.nhatnam.server.dto.response.OrderResponse updated =
                    superSellerOrderService.updateOrder(orderId, user.getId(), req);
            return ResponseEntity.ok(ApiResponse.success(updated, "Đã cập nhật đơn hàng"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SUPER_SELLER] updateOrder error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{orderId}/partial-payment")
    public ResponseEntity<ApiResponse<Object>> recordPartialPayment(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

            User user = (User) authentication.getPrincipal();
            String actorName = user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername();

            Object amtRaw = body.get("paidAmount");
            if (amtRaw == null)
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu trường paidAmount"));

            BigDecimal paidAmount = new BigDecimal(amtRaw.toString());
            int debtDays = body.get("debtDays") instanceof Number n ? n.intValue() : 0;

            orderService.recordPartialPayment(orderId, paidAmount, debtDays, actorName);
            return ResponseEntity.ok(ApiResponse.success(null, "Ghi nhận thanh toán thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] recordPartialPayment error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PatchMapping("/orders/{orderId}/payment-method")
    public ResponseEntity<ApiResponse<OrderResponse>> updatePaymentMethod(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> req,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

            String newMethod = req.get("paymentMethod") instanceof String s
                    ? s.trim().toUpperCase() : null;
            if (newMethod == null || newMethod.isBlank())
                throw new IllegalArgumentException("Thiếu paymentMethod");

            OrderResponse order = orderService.getOrderById(orderId);

            // ← Sửa: cho phép cả DELIVERING
            if (!"PENDING_PAYMENT".equals(order.getStatus())
                    && !"DELIVERING".equals(order.getStatus()))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Chỉ được đổi phương thức thanh toán khi đơn đang Giao hàng hoặc Chờ thanh toán"));

            if ("PAID".equals(order.getPaymentStatus()))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST,
                        "Đơn đã thanh toán, không thể đổi phương thức"));

            OrderResponse updated = orderService.updatePaymentMethodBySeller(orderId, newMethod);
            return ResponseEntity.ok(ApiResponse.success(updated, "Cập nhật phương thức thanh toán thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] updatePaymentMethod error orderId={}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/receiver-infos/check-phone")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkReceiverPhone(
            @RequestParam String phone,
            @RequestParam(required = false) Long excludeCustomerId) {
        try {
            boolean exists = receiverInfoRepository.existsByReceiverPhone(phone.trim());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", exists);
            result.put("phone", phone.trim());
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/customers/b2b/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateB2bCustomer(
            @PathVariable Long id,
            @RequestBody Map<String, Object> req) {
        try {
            Customer c = customerRepository.findById(id)
                    .filter(customer -> customer.getDeletedAt() == null)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy KH #" + id));


            // ── Xử lý đổi mã khách hàng ──────────────────────────────────────
            String newCode = _str(req, "customerCode");
            if (newCode != null && !newCode.isBlank()) {
                String upperCode = newCode.trim().toUpperCase();
                // Chỉ check trùng nếu mã thực sự thay đổi
                if (!upperCode.equals(c.getCustomerCode())) {
                    customerRepository.findByCustomerCodeAndDeletedAtIsNull(upperCode).ifPresent(existing -> {
                        if (!existing.getId().equals(id))
                            throw new IllegalArgumentException("Mã khách hàng '" + upperCode + "' đã tồn tại");
                    });
                    c.setCustomerCode(upperCode);
                }
            }

            _applyB2bFields(req, c, false);
            c = customerRepository.save(c);
            return ResponseEntity.ok(ApiResponse.success(_toB2bMap(c), "Cập nhật thành công"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/customers/b2b/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchB2bByCode(
            @RequestParam String code,
            Authentication auth) {
        User currentUser = auth != null ? (User) auth.getPrincipal() : null;
        Set<Role> userRoles = currentUser != null ? currentUser.getAllRoles() : Set.of();
        boolean canSeeAll = userRoles.contains(Role.ADMIN) || userRoles.contains(Role.OWNER)
                || userRoles.contains(Role.SUPERADMIN) || userRoles.contains(Role.SUPER_SELLER);

        var allCustomers = customerRepository.findAllByOrderByCustomerCodeAscNameAsc();
        final Long userId = currentUser != null ? currentUser.getId() : null;

        var list = allCustomers.stream()
                .filter(c -> canSeeAll
                        || c.getCustomerType() == Customer.CustomerType.RETAIL
                        || (c.getCreatedBySeller() != null && Objects.equals(c.getCreatedBySeller().getId(), userId)))
                .filter(c ->
                        (c.getCustomerCode() != null && c.getCustomerCode().toLowerCase().contains(code.toLowerCase())) ||
                        (c.getTaxCode() != null && c.getTaxCode().contains(code)) ||
                        (c.getPhone()   != null && c.getPhone().contains(code)) ||
                        (c.getName()    != null && c.getName().toLowerCase().contains(code.toLowerCase())) ||
                        (c.getCompanyName() != null && c.getCompanyName().toLowerCase().contains(code.toLowerCase())))
                .map(this::_toB2bMap)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list, "OK"));
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOMERS — GENERAL
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>> getAllCustomers() {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    customerService.getAllActiveCustomers(), "Customers retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get customers", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/search")
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> searchCustomers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "10")   int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc")  String sortDir) {
        try {
            Sort sort = sortDir.equalsIgnoreCase("asc")
                    ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
            Pageable pageable = PageRequest.of(page, size, sort);
            return ResponseEntity.ok(ApiResponse.success(
                    customerService.searchCustomers(keyword != null ? keyword : "", pageable),
                    "Customers retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to search customers", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/phone/{phone}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomerByPhone(
            @PathVariable String phone) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    customerService.getCustomerByPhone(phone), "Customer retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to get customer by phone: {}", phone, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/check-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkCustomerCode(
            @RequestParam String code) {
        try {
            String upperCode = code.trim().toUpperCase();
            boolean exists = customerRepository.findByCustomerCodeAndDeletedAtIsNull(upperCode).isPresent();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", exists);
            result.put("code", upperCode);
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/customers/{customerId}/receiver-infos")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReceiverInfos(
            @PathVariable Long customerId) {
        try {
            Customer customer = customerRepository.findById(customerId)
                    .filter(c -> c.getDeletedAt() == null)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy KH #" + customerId));


            List<Map<String, Object>> list = receiverInfoRepository
                    .findByCustomerId(customerId)
                    .stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",              r.getId());
                        m.put("receiverName",    r.getReceiverName());
                        m.put("receiverPhone",   r.getReceiverPhone());
                        m.put("receiverAddress", r.getReceiverAddress());
                        m.put("isDefault",       r.getIsDefault());
                        m.put("createdAt",       r.getCreatedAt());
                        return m;
                    })
                    .collect(Collectors.toList());

            // ── Gắn thêm discountRate + invoiceDays của customer ──────────────
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("discountRate", customer.getDiscountRate());
            result.put("invoiceDays",  customer.getInvoiceDays());
            String info = "";

            if (customer.getCustomerType() == Customer.CustomerType.COMPANY) {
                if (customer.getCompanyName() != null) {
                    info = customer.getCompanyName();
                }
            } else if (customer.getCustomerType() == Customer.CustomerType.RETAIL) {
                if (customer.getName() != null) {
                    info = customer.getName();
                }
            } else {
                info = "Khách vãng lai";
            }

            result.put("customerName", info);
            result.put("customerType", customer.getCustomerType());
            result.put("receiverInfos", list);

            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @DeleteMapping("/customers/{customerId}/receiver-infos/{receiverId}")
    public ResponseEntity<ApiResponse<Object>> deleteReceiverInfo(
            @PathVariable Long customerId,
            @PathVariable Long receiverId) {
        try {
            List<CustomerReceiverInfo> infos = receiverInfoRepository.findByCustomerId(customerId);

            if (infos.size() <= 1)
                throw new IllegalArgumentException("Phải có ít nhất 1 người nhận");

            CustomerReceiverInfo toDelete = infos.stream()
                    .filter(r -> r.getId().equals(receiverId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy người nhận #" + receiverId));

            boolean wasDefault = Boolean.TRUE.equals(toDelete.getIsDefault());
            receiverInfoRepository.delete(toDelete);

            // Nếu xóa cái default → set cái đầu tiên còn lại làm default
            if (wasDefault) {
                List<CustomerReceiverInfo> remaining = receiverInfoRepository.findByCustomerId(customerId);
                if (!remaining.isEmpty()) {
                    remaining.get(0).setIsDefault(true);
                    receiverInfoRepository.save(remaining.get(0));
                }
            }

            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa người nhận"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        }
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomerById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    customerService.getCustomerById(id), "Customer retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to get customer ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/customers")
    public ResponseEntity<ApiResponse<CustomerResponse>> createCustomer(
            @Valid @RequestBody CreateCustomerRequest request,
            Authentication authentication) {
        try {
            User currentUser = (User) authentication.getPrincipal();

            Long assignedSellerId = Boolean.TRUE.equals(request.getAssignPrivate())
                    ? currentUser.getId()
                    : null;

            log.info("[DEBUG] assignPrivate={}, assignedSellerId={}", request.getAssignPrivate(), assignedSellerId);

            return ResponseEntity.ok(ApiResponse.success(
                    customerService.createCustomer(request, currentUser.getId(), assignedSellerId),
                    "Customer created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating customer", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/customers/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateCustomer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    customerService.updateCustomer(id, request), "Customer updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            int code = e.getMessage().contains("Không tìm thấy")
                    ? StatusCode.NOT_FOUND : StatusCode.BAD_REQUEST;
            return ResponseEntity.ok(ApiResponse.error(code, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error updating customer ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/customers/b2b")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createB2bCustomer(
            @RequestBody Map<String, Object> req,
            Authentication auth) {
        try {
            // Change 9: gắn seller hiện tại vào customer
            User currentUser = auth != null ? (User) auth.getPrincipal() : null;

            String code = _str(req, "customerCode");
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("Thiếu mã khách hàng (customerCode)");

            String upperCode = code.trim().toUpperCase();
            if (customerRepository.findByCustomerCodeAndDeletedAtIsNull(upperCode).isPresent())
                throw new IllegalArgumentException("Mã khách hàng đã tồn tại: " + upperCode);

            // Change 10: phone optional cho mọi loại KH
            String typeStr = _str(req, "customerType");
            boolean isCompanyType = typeStr != null && typeStr.toUpperCase().equals("COMPANY");

            String phone = _str(req, "phone");
            if (phone != null && !phone.isBlank()
                    && customerRepository.findByPhoneAndDeletedAtIsNull(phone.trim()).isPresent())
                throw new IllegalArgumentException("Số điện thoại đã tồn tại: " + phone.trim());

            // RETAIL: tên và SĐT đều tuỳ chọn — nếu trống hiển thị "Khách vãng lai"
            // (không cần validate bắt buộc)

            // Change 10: COMPANY bắt buộc tên công ty, MST, email, ≥1 địa chỉ giao hàng
            if (isCompanyType) {
                String companyName = _str(req, "companyName");
                if (companyName == null || companyName.isBlank())
                    throw new IllegalArgumentException("Tên công ty là bắt buộc với khách doanh nghiệp");
                // Receiver là tuỳ chọn cho cả khách lẻ lẫn công ty
            }

            String taxCode = _str(req, "taxCode");
            if (taxCode != null && !taxCode.isBlank()
                    && customerRepository.findByTaxCodeAndDeletedAtIsNull(taxCode.trim()).isPresent())
                throw new IllegalArgumentException("Mã số thuế đã tồn tại: " + taxCode.trim());

            // ── Parse & validate receiverInfos trước khi lưu ─────────────────
            List<?> rawReceivers = req.get("receiverInfos") instanceof List<?>
                    ? (List<?>) req.get("receiverInfos") : List.of();

            record ReceiverData(String name, String rPhone, String address, boolean isDefault) {}
            List<ReceiverData> parsedReceivers = new ArrayList<>();

            for (int i = 0; i < rawReceivers.size(); i++) {
                Object item = rawReceivers.get(i);
                if (!(item instanceof Map<?, ?> rMap)) continue;

                String rName    = rMap.get("receiverName")    instanceof String s ? s.trim() : null;
                String rPhone   = rMap.get("receiverPhone")   instanceof String s ? s.trim() : null;
                String rAddress = rMap.get("receiverAddress") instanceof String s ? s.trim() : null;
                boolean isDefault = Boolean.TRUE.equals(rMap.get("isDefault"));

                // Chỉ cần địa chỉ — nếu trống thì bỏ qua
                if (rAddress == null || rAddress.isBlank()) continue;

                // Normalize: empty string → null
                if (rName != null && rName.isBlank()) rName = null;
                if (rPhone != null && rPhone.isBlank()) rPhone = null;

                parsedReceivers.add(new ReceiverData(rName, rPhone, rAddress, isDefault));
            }

            // ── Validate duplicate address across receivers ─────────────
            String mainPhone = (phone != null && !phone.isBlank()) ? phone.trim() : "";
            for (int i = 0; i < parsedReceivers.size(); i++) {
                for (int j = i + 1; j < parsedReceivers.size(); j++) {
                    String phoneI = parsedReceivers.get(i).rPhone();
                    String phoneJ = parsedReceivers.get(j).rPhone();
                    String addrI  = parsedReceivers.get(i).address().toLowerCase();
                    String addrJ  = parsedReceivers.get(j).address().toLowerCase();

                    if (phoneI != null && phoneJ != null && phoneI.equals(phoneJ)) {
                        if (!phoneI.equals(mainPhone)) {
                            throw new IllegalArgumentException(
                                    "Người nhận #" + (i + 1) + " và #" + (j + 1) +
                                            " có số điện thoại trùng nhau (" + phoneI + "). " +
                                            "Mỗi người nhận phải có SĐT riêng biệt.");
                        } else {
                            throw new IllegalArgumentException(
                                    "Người nhận #" + (i + 1) + " và #" + (j + 1) +
                                            " cùng dùng SĐT chính (" + phoneI + "). " +
                                            "Chỉ 1 người nhận được phép dùng SĐT liên hệ chính.");
                        }
                    }

                    if (addrI.equals(addrJ)) {
                        throw new IllegalArgumentException(
                                "Người nhận #" + (i + 1) + " và #" + (j + 1) +
                                        " có địa chỉ nhận hàng trùng nhau. " +
                                        "Mỗi người nhận phải có địa chỉ riêng biệt.");
                    }
                }
            }

            // ── Tạo customer ──────────────────────────────────────────────────
            Customer c = new Customer();
            c.setCustomerCode(upperCode);
            _applyB2bFields(req, c, true);
            // Change 9: gắn seller tạo + snapshot tên
            if (currentUser != null) {
                c.setCreatedBySeller(currentUser);
                String sellerName = currentUser.getFullName() != null && !currentUser.getFullName().isBlank()
                        ? currentUser.getFullName() : currentUser.getUsername();
                c.setCreatedByName(sellerName);
            }

            // Khách riêng (assignPrivate = true) → gán luôn người tạo làm assigned seller
            boolean assignPrivate = Boolean.TRUE.equals(req.get("assignPrivate"));
            if (assignPrivate && currentUser != null) {
                c.setAssignedSeller(currentUser);
                String assignedName = currentUser.getFullName() != null && !currentUser.getFullName().isBlank()
                        ? currentUser.getFullName() : currentUser.getUsername();
                c.setAssignedSellerName(assignedName);
            }

            c = customerRepository.save(c);


            // ── Lưu receiverInfos ─────────────────────────────────────────────
            final Customer savedCustomer = c;
            List<CustomerReceiverInfo> infos = new ArrayList<>();

            // Với RETAIL: frontend lọc sẵn receiver có address, backend lọc lại cho chắc
            // All receivers with address are valid now
            List<ReceiverData> validReceivers = parsedReceivers.stream()
                    .filter(r -> r.address() != null && !r.address().isBlank())
                    .toList();

            for (int i = 0; i < validReceivers.size(); i++) {
                ReceiverData r = validReceivers.get(i);
                infos.add(CustomerReceiverInfo.builder()
                        .customer(savedCustomer)
                        .receiverName(r.name())       // null ok
                        .receiverPhone(r.rPhone())    // null ok
                        .receiverAddress(r.address())
                        .isDefault(r.isDefault() || i == 0)
                        .build());
            }

            // Đảm bảo chỉ có 1 default
            boolean hasDefault = infos.stream().anyMatch(CustomerReceiverInfo::getIsDefault);
            if (!hasDefault && !infos.isEmpty()) {
                infos.get(0).setIsDefault(true);
            } else if (hasDefault) {
                boolean foundFirst = false;
                for (CustomerReceiverInfo info : infos) {
                    if (info.getIsDefault()) {
                        if (foundFirst) info.setIsDefault(false);
                        else foundFirst = true;
                    }
                }
            }

            if (!infos.isEmpty()) {
                receiverInfoRepository.saveAll(infos);
            }

            return ResponseEntity.ok(ApiResponse.success(_toB2bMap(c), "Tạo khách hàng thành công"));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            log.warn("[SELLER] createB2bCustomer DB constraint: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, _parseDuplicateError(e)));
        } catch (Exception e) {
            log.error("[SELLER] createB2bCustomer error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                    "Lỗi hệ thống khi tạo khách hàng: " + e.getMessage()));
        }
    }

    @DeleteMapping("/customers/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCustomer(@PathVariable Long id) {
        try {
            customerService.deleteCustomer(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Customer deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to delete customer ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private String _parseDuplicateError(DataIntegrityViolationException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("receiver_phone") || msg.contains("phone"))
            return "Số điện thoại người nhận đã tồn tại cho khách hàng này";
        if (msg.contains("receiver_address") || msg.contains("address"))
            return "Địa chỉ người nhận đã tồn tại cho khách hàng này";
        if (msg.contains("customer_code"))
            return "Mã khách hàng đã tồn tại";
        if (msg.contains("tax_code"))
            return "Mã số thuế đã tồn tại";
        if (msg.contains("phone"))
            return "Số điện thoại đã tồn tại";
        return "Dữ liệu bị trùng lặp, vui lòng kiểm tra lại";
    }

    // ════════════════════════════════════════════════════════════════
    // CATEGORIES
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/all-categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategoriesUnpaged() {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    categoryService.getAllCategories(), "Categories retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get categories", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    categoryService.getPaginationCategories(page, size),
                    "Categories retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get categories", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    categoryService.getCategoryById(id), "Category retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    categoryService.createCategory(request), "Category created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to create category", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody CreateCategoryRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    categoryService.updateCategory(id, request), "Category updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to update category ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCategory(@PathVariable Long id) {
        try {
            categoryService.deleteCategory(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Category deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to delete category ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INGREDIENTS
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/all-ingredients")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getAllIngredients() {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.getAllIngredients(), "Ingredients retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get ingredients", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/ingredients")
    public ResponseEntity<ApiResponse<List<IngredientResponse>>> getPaginationIngredients(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.getPaginationIngredients(page, size),
                    "Ingredients retrieved successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to get ingredients", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<IngredientResponse>> getIngredientById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.getIngredientById(id), "Ingredient retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to get ingredient ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/ingredients")
    public ResponseEntity<ApiResponse<IngredientResponse>> createIngredient(
            @Valid @RequestBody CreateIngredientRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.createIngredient(request), "Ingredient created successfully"));
        } catch (Exception e) {
            log.error("❌ Failed to create ingredient", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @PutMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<IngredientResponse>> updateIngredient(
            @PathVariable Long id,
            @Valid @RequestBody CreateIngredientRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    ingredientService.updateIngredient(id, request), "Ingredient updated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to update ingredient ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @DeleteMapping("/ingredients/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteIngredient(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(null, "Ingredient deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to delete ingredient ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PRODUCTS
    // ════════════════════════════════════════════════════════════════
    private final WarehouseRepository warehouseRepository;
    @GetMapping("/warehouses")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getWarehouses() {
        try {
            List<Map<String, Object>> list = warehouseRepository.findByActiveTrue()
                    .stream()
                    .filter(w -> w.getType() == Warehouse.WarehouseType.SALE) // ← chỉ lấy SALE
                    .map(w -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",      w.getId());
                        m.put("name",    w.getName());
                        m.put("address", w.getAddress());
                        m.put("type",    w.getType().name());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProducts(
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "10")   int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc")  String sortDir,
            @RequestParam(required = false)      String category,
            @RequestParam(required = false)      Long warehouseId) {
        try {
            Sort sort = sortDir.equalsIgnoreCase("asc")
                    ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
            Pageable pageable = PageRequest.of(page, size, sort);

            List<ProductResponse> products = (category != null && !category.isEmpty())
                    ? productService.getProductsByCategory(category, warehouseId)  // ← truyền warehouseId
                    : productService.getAllProducts(warehouseId);

            int start = (int) pageable.getOffset();
            int end   = Math.min(start + pageable.getPageSize(), products.size());

            Map<String, Object> data = new HashMap<>();
            data.put("content",     products.subList(start, end));
            data.put("currentPage", page);
            data.put("totalItems",  products.size());
            data.put("totalPages",  (int) Math.ceil((double) products.size() / size));
            data.put("pageSize",    size);

            return ResponseEntity.ok(ApiResponse.success(data, "Products retrieved successfully"));
        } catch (Exception e) {
            log.error("[SELLER] Error retrieving products", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductDetail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    productService.getProductById(id), "Product detail retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] Error getting product detail ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductResponse>> createCompleteProduct(
            @Valid @RequestBody CreateCompleteProductRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    productService.createCompleteProduct(request), "Product created successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Failed to create product", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating product", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody CreateCompleteProductRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    productService.updateProduct(id, request), "Product updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Failed to update product ID: {}", id, e);
            int code = e.getMessage().contains("not found")
                    ? StatusCode.NOT_FOUND : StatusCode.BAD_REQUEST;
            return ResponseEntity.ok(ApiResponse.error(code, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error updating product ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteProduct(@PathVariable Long id) {
        try {
            ProductResponse product = productService.getProductById(id);
            String imageUrl = product.getImageUrl();
            productService.deleteProduct(id);
            if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    fileStorageService.deleteFile(imageUrl);
                } catch (IOException e) {
                    log.warn("⚠️ Failed to delete product image: {}", imageUrl);
                }
            }
            return ResponseEntity.ok(ApiResponse.success(null, "Product deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Failed to delete product ID: {}", id, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Product Tiers ─────────────────────────────────────────────

    @PostMapping("/products/{id}/tiers")
    public ResponseEntity<ApiResponse<ProductResponse>> addTier(
            @PathVariable Long id,
            @Valid @RequestBody TierRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.addTier(id, req), "Tier added"));
    }

    @PutMapping("/products/{id}/tiers/{tierId}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateTier(
            @PathVariable Long id,
            @PathVariable Long tierId,
            @Valid @RequestBody TierRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.updateTier(id, tierId, req), "Tier updated"));
    }

    @DeleteMapping("/products/{id}/tiers/{tierId}")
    public ResponseEntity<ApiResponse<ProductResponse>> deleteTier(
            @PathVariable Long id,
            @PathVariable Long tierId) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.deleteTier(id, tierId), "Tier deleted"));
    }

    // ════════════════════════════════════════════════════════════════
    // ORDERS
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated())
            return ResponseEntity.ok(ApiResponse.error(StatusCode.UNAUTHORIZED, "Unauthorized"));

        User user   = (User) authentication.getPrincipal();
        Long userId = user.getId();
        ReentrantLock lock = transactionLockManager.getLock(userId);

        if (!lock.tryLock())
            return ResponseEntity.ok(ApiResponse.error(
                    StatusCode.TOO_MANY_REQUESTS,
                    "Another order is being processed. Please wait and try again."));

        try {
            return ResponseEntity.ok(ApiResponse.success(
                    orderService.createOrder(request, userId), "Order created successfully"));
        } catch (PriceChangedException e) {
            log.warn("[SELLER] Price mismatch for user={}: {}", userId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(StatusCode.PRICE_CHANGED, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("[SELLER] Order creation failed for user: {}", userId, e);
            if (e.getMessage().contains("not found"))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
            if (e.getMessage().contains("Insufficient stock") || e.getMessage().contains("Không đủ tồn kho"))
                return ResponseEntity.ok(ApiResponse.error(StatusCode.OUT_OF_STOCK, e.getMessage()));
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] Unexpected error creating order for user: {}", userId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR,
                    "Failed to create order: " + e.getMessage()));
        } finally {
            lock.unlock();
            transactionLockManager.cleanupIfUnused(userId);
        }
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable Long orderId) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    orderService.getOrderById(orderId), "Order retrieved successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.NOT_FOUND, e.getMessage()));
        } catch (Exception e) {
            log.error("[SELLER] Error getting order ID: {}", orderId, e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrders(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            boolean hasKeyword = keyword != null && !keyword.isBlank();

            // Khi có keyword → bỏ filter ngày, lấy toàn bộ
            // Khi không có keyword → filter theo ngày (mặc định hôm nay nếu không truyền)
            List<Order> orders;
            if (hasKeyword) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findAll(Sort.by("createdAt").descending())
                );
            } else if (from != null && to != null) {
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(from, to)
                );
            } else {
                // Default: hôm nay theo timezone VN
                LocalDate today = LocalDate.now(VN);
                long fromMs = today.atStartOfDay(VN).toInstant().toEpochMilli();
                long toMs   = today.atTime(23, 59, 59, 999_000_000).atZone(VN).toInstant().toEpochMilli();
                orders = new java.util.ArrayList<>(
                        orderRepository.findByCreatedAtBetween(fromMs, toMs)
                );
            }

            // Filter status
            if (status != null && !status.isBlank()) {
                orders = orders.stream()
                        .filter(o -> o.getStatus().name().equalsIgnoreCase(status))
                        .collect(Collectors.toList());
            }

            // Filter customerId
            if (customerId != null) {
                final Long cid = customerId;
                orders = orders.stream()
                        .filter(o -> o.getCustomer() != null && o.getCustomer().getId().equals(cid))
                        .collect(Collectors.toList());
            }

            // Filter productId
            if (productId != null) {
                final Long pid = productId;
                orders = orders.stream()
                        .filter(o -> o.getOrderItems() != null && o.getOrderItems().stream()
                                .anyMatch(i -> pid.equals(i.getProductId())))
                        .collect(Collectors.toList());
            }

            // Filter keyword — accent-insensitive (gõ không dấu vẫn ra)
            if (hasKeyword) {
                String kw = removeAccents(keyword.toLowerCase().trim());
                orders = orders.stream()
                        .filter(o ->
                                matchesKeyword(o.getOrderCode(),     kw)
                                        || matchesKeyword(o.getCustomerName(),  kw)
                                        || matchesKeyword(o.getCustomerPhone(), kw)
                                        || matchesKeyword(o.getOrderedByName(), kw)
                                        || (o.getUser() != null && (
                                        matchesKeyword(o.getUser().getFullName(), kw)
                                                || matchesKeyword(o.getUser().getUsername(), kw)))
                        )
                        .collect(Collectors.toList());
            }

            int total = orders.size();
            int start = page * size;
            int end   = Math.min(start + size, total);
            List<Order> paged = start >= total ? List.of() : orders.subList(start, end);
            List<Map<String, Object>> content = paged.stream().map(this::_toOrderMap).toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content",     content);
            result.put("totalItems",  total);
            result.put("currentPage", page);
            result.put("totalPages",  (int) Math.ceil((double) total / size));
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            log.error("[ACCOUNTANT] getOrders error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ── Helpers accent-insensitive ────────────────────────────────────────────────
    private static String removeAccents(String s) {
        if (s == null) return "";
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return normalized
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace("đ", "d").replace("Đ", "D");
    }

    private static boolean matchesKeyword(String field, String kwNoAccent) {
        if (field == null || field.isBlank()) return false;
        return removeAccents(field.toLowerCase()).contains(kwNoAccent);
    }

    private Map<String, Object> _toOrderMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                 o.getId());
        m.put("orderCode",          o.getOrderCode());
        m.put("customerName",       o.getCustomerName());
        m.put("customerPhone",      o.getCustomerPhone());
        m.put("customerType",       o.getCustomerType());
        m.put("warehouseName",      o.getWarehouseName());
        m.put("status",             o.getStatus());
        m.put("paymentStatus",      o.getPaymentStatus());
        m.put("paymentMethod",      o.getPaymentMethod());
        m.put("finalAmount",        o.getFinalAmount());
        m.put("discountAmount",     o.getDiscountAmount());
        m.put("vatAmount",          o.getVatAmount());
        m.put("surcharge",          o.getSurcharge());
        m.put("notes",              o.getNotes());
        m.put("orderedByName",      o.getOrderedByName());
        m.put("createdAt",          o.getCreatedAt());
        m.put("updatedAt",          o.getUpdatedAt());
        m.put("paidAmount",         o.getPaidAmount() != null ? o.getPaidAmount() : java.math.BigDecimal.ZERO);
        m.put("debtDays",           o.getDebtDays());
        m.put("pendingPaymentAt",   o.getPendingPaymentAt());
        m.put("receiptFileUrl",     o.getReceiptFileUrl());
        m.put("invoiceNumber",      o.getInvoiceNumber());

        // Người tạo đơn (từ user_id của order)
        if (o.getUser() != null) {
            User creator = o.getUser();
            String createdByName = creator.getFullName() != null && !creator.getFullName().isBlank()
                    ? creator.getFullName()
                    : creator.getUsername();
            m.put("createdByUserId", creator.getId());
            m.put("createdByName",   createdByName);
        } else {
            m.put("createdByUserId", null);
            m.put("createdByName",   null);
        }

        // Dùng để UI xác định quyền thao tác
        // null = tất cả SELLER thấy (khách lẻ hoặc công ty do admin/super tạo)
        // có giá trị = chỉ seller đó thấy (công ty do SELLER thuần tạo)
        m.put("visibleToSellerId", o.getVisibleToSellerId());

        return m;
    }

    // ════════════════════════════════════════════════════════════════
    // INVOICE
    // ════════════════════════════════════════════════════════════════

    // Thay toàn bộ method generateInvoice() bằng đoạn sau:

    @GetMapping("/orders/{orderId}/invoice")
    public ResponseEntity<?> generateInvoice(@PathVariable Long orderId) {
        try {
            OrderResponse order = orderService.getOrderById(orderId);

            // ── VAT breakdown tách INCLUSIVE / EXCLUSIVE ──────────────────────────
            Map<Integer, BigDecimal> vatBreakdownInclusive = new LinkedHashMap<>();
            Map<Integer, BigDecimal> vatBreakdownExclusive = new LinkedHashMap<>();

            for (var item : order.getItems()) {
                Integer    rate   = item.getVatRate();
                BigDecimal amount = item.getVatAmount();
                if (rate == null || rate == 0 || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) continue;

                boolean isInclusive = "INCLUSIVE".equalsIgnoreCase(item.getVatMode());
                if (isInclusive) {
                    vatBreakdownInclusive.merge(rate, amount, BigDecimal::add);
                } else {
                    vatBreakdownExclusive.merge(rate, amount, BigDecimal::add);
                }
            }

            InvoiceDTO invoiceDTO = InvoiceDTO.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .customerName(order.getCustomerName())
                    .customerPhone(order.getCustomerPhone())
                    .customerEmail(order.getCustomerEmail())
                    .shippingAddress(order.getShippingAddress())
                    .notes(order.getNotes())
                    .totalAmount(order.getTotalAmount())
                    .discountAmount(order.getDiscountAmount())
                    .finalAmount(order.getFinalAmount())
                    .vatAmount(order.getVatAmount())
                    .surcharge(order.getSurcharge())
                    .surchargeDetail(order.getSurchargeDetail())
                    .status(order.getStatus())
                    .paymentStatus(order.getPaymentStatus())
                    .paymentMethod(order.getPaymentMethod())
                    .createdAt(order.getCreatedAt())
                    .receiverName(order.getReceiverName())
                    .estimatedDelivery(order.getEstimatedDelivery())
                    .paymentDeadline(order.getPaymentDeadline())
                    .deliveryDatetime(order.getDeliveryDatetime())
                    .customerType(order.getCustomerType())
                    .companyName(order.getCompanyName())
                    .orderedByName(order.getOrderedByName())
                    .taxCode(order.getTaxCode())
                    .companyPhone(order.getCompanyPhone())
                    .companyAddress(order.getCompanyAddress())
                    .contactName(order.getContactName())
                    .hideAllPrices(order.getHideAllPrices())
                    .deliveryAddress(order.getDeliveryAddress())
                    .vatBreakdownInclusive(vatBreakdownInclusive)   // ← thay thế vatBreakdown cũ
                    .vatBreakdownExclusive(vatBreakdownExclusive)   // ← thêm mới
                    .items(order.getItems().stream()
                            .map(item -> InvoiceDTO.Item.builder()
                                    .productName(item.getProductName())
                                    .priceName(item.getPriceName())
                                    .unitPrice(item.getUnitPrice())
                                    .quantity(item.getQuantity())
                                    .subtotal(item.getSubtotal())
                                    .unit(item.getUnit())
                                    .saleType(item.getSaleType() != null ? item.getSaleType() : "RETAIL")
                                    .unitsPerBox(item.getUnitsPerBox())
                                    .defaultPrice(item.getDefaultPrice())
                                    .vatRate(item.getVatRate())
                                    .vatMode(item.getVatMode())
                                    .vatAmount(item.getVatAmount())
                                    .tierPrice(
                                            item.getDiscountPercent() != null && item.getDiscountPercent() > 0
                                                    ? item.getUnitPrice().multiply(BigDecimal.valueOf(100))
                                                    .divide(BigDecimal.valueOf(100 - item.getDiscountPercent()), 0, java.math.RoundingMode.HALF_UP)
                                                    : item.getUnitPrice()
                                    )
                                    .notes(item.getNotes())
                                    .ingredientsUsed(item.getIngredientsUsed() == null
                                            ? List.of()
                                            : item.getIngredientsUsed().stream()
                                            .map(ing -> InvoiceDTO.Ingredient.builder()
                                                    .ingredientName(ing.getIngredientName())
                                                    .quantityUsed(ing.getQuantityUsed())
                                                    .unit(ing.getUnit())
                                                    .build())
                                            .collect(Collectors.toList()))
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            boolean showPrices = order.getShowPrices() == null || order.getShowPrices();
            byte[] pdfBytes = invoicePdf.GenerateInvoicePdf(invoiceDTO, showPrices);
            String filename = "invoice_" + order.getOrderCode().substring(2) + ".pdf";

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "application/pdf")
                    .body(pdfBytes);

        } catch (Exception e) {
            log.error("Lỗi generate invoice cho order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error(500, "Lỗi khi xử lý hóa đơn: " + e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // B2B PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════
    // THAY THẾ TOÀN BỘ method _applyB2bFields trong SellerController
    // ════════════════════════════════════════════════════════════════

    private void _applyB2bFields(Map<String, Object> req, Customer c, boolean isCreate) {

        // ── customerType ──────────────────────────────────────────────────────
        if (req.containsKey("customerType") && req.get("customerType") != null) {
            c.setCustomerType(Customer.CustomerType.valueOf(
                    ((String) req.get("customerType")).toUpperCase()));
        } else if (isCreate) {
            c.setCustomerType(Customer.CustomerType.RETAIL);
        }

        // ── String fields ─────────────────────────────────────────────────────
        _setStrNullable(req, "companyName",    c::setCompanyName);
        _setStrNullable(req, "taxCode",        c::setTaxCode);
        _setStrNullable(req, "contactName",    c::setContactName);
        _setStrNullable(req, "companyPhone",   c::setCompanyPhone);
        _setStrNullable(req, "companyAddress", c::setCompanyAddress);
        _setStrNullable(req, "name",           c::setName);
        _setStrNullable(req, "email",          c::setEmail);

        // ── Phone ─────────────────────────────────────────────────────────────
        if (req.containsKey("phone")) {
            String phone = _str(req, "phone");
            c.setPhone(phone != null && !phone.isBlank() ? phone.trim() : null);
        }

        // ── discountRate (0 = không chiết khấu, 1–100 = % chiết khấu) ────────────
        if (req.containsKey("discountRate")) {
            Object raw = req.get("discountRate");
            if (raw == null) {
                c.setDiscountRate(0);
            } else {
                int rate = ((Number) raw).intValue();
                if (rate < 0 || rate > 100)
                    throw new IllegalArgumentException("Tỷ lệ chiết khấu phải từ 0 đến 100");
                c.setDiscountRate(rate);
            }
        }

        // ── invoiceDays (-1 = không xuất HĐ, 0 = ngay trong ngày, >0 = sau n ngày)
        if (req.containsKey("invoiceDays")) {
            Object raw = req.get("invoiceDays");
            if (raw == null) {
                c.setInvoiceDays(-1);
            } else {
                int days = ((Number) raw).intValue();
                if (days < -1)
                    throw new IllegalArgumentException("invoiceDays không hợp lệ");
                c.setInvoiceDays(days);
            }
        }

        // ── pricingType ───────────────────────────────────────────────────────
        if (req.containsKey("pricingType") && req.get("pricingType") != null) {
            try {
                c.setPricingType(Customer.PricingType.valueOf(
                        ((String) req.get("pricingType")).toUpperCase()));
            } catch (Exception ignored) {}
        }

        // ── Phân loại khách hàng (categoryId) ────────────────────────────────
        // Key luôn tồn tại trong payload (kể cả khi null = xóa phân loại)
        if (req.containsKey("categoryId")) {
            Object catIdRaw = req.get("categoryId");
            if (catIdRaw == null) {
                // Xóa phân loại
                c.setCustomerCategory(null);
            } else {
                try {
                    Long catId = ((Number) catIdRaw).longValue();
                    categoryRepository.findById(catId)
                            .ifPresentOrElse(
                                    c::setCustomerCategory,
                                    () -> c.setCustomerCategory(null)
                            );
                } catch (Exception e) {
                    log.warn("[B2B] Không parse được categoryId: {}", catIdRaw);
                }
            }
        }

        // ── isActive ──────────────────────────────────────────────────────────
        if (isCreate) c.setIsActive(true);

        // ── Khi RETAIL → clear company fields ────────────────────────────────
        if (c.getCustomerType() == Customer.CustomerType.RETAIL) {
            c.setCompanyName(null);
            c.setTaxCode(null);
            c.setContactName(null);
            c.setCompanyPhone(null);
            c.setCompanyAddress(null);
        }

        // ── Khi COMPANY → name = contactName nếu name trống ──────────────────
        if (c.getCustomerType() == Customer.CustomerType.COMPANY) {
            if (c.getName() == null || c.getName().isBlank())
                if (c.getContactName() != null && !c.getContactName().isBlank())
                    c.setName(c.getContactName());
        }
    }

    // Helper mới: set null nếu value rỗng
    private void _setStrNullable(Map<String, Object> req, String key,
                                 java.util.function.Consumer<String> setter) {
        if (!req.containsKey(key)) return;
        Object v = req.get(key);
        String s = v instanceof String str ? str.trim() : null;
        setter.accept(s == null || s.isBlank() ? null : s);
    }

    private void _setStr(Map<String, Object> req, String key,
                         java.util.function.Consumer<String> setter) {
        if (req.containsKey(key) && req.get(key) != null)
            setter.accept(((String) req.get(key)).trim());
    }

    private String _str(Map<String, Object> req, String key) {
        Object v = req.get(key);
        return v instanceof String s ? s : null;
    }

    private Map<String, Object> _toB2bMap(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           c.getId());
        m.put("customerType", c.getCustomerType() != null ? c.getCustomerType().name() : "RETAIL");
        m.put("pricingType",  c.getPricingType()  != null ? c.getPricingType().name()  : "RETAIL_PRICE");
        m.put("isActive",     c.getIsActive() != null ? c.getIsActive() : true);
        m.put("discountRate", c.getDiscountRate());
        m.put("invoiceDays",  c.getInvoiceDays());

        m.put("createdAt",    c.getCreatedAt());

        String code = c.getCustomerCode();
        if (code == null || code.isBlank())
            code = c.getPhone() != null ? c.getPhone() : "KH#" + c.getId();
        m.put("customerCode", code);

        String contact = c.getContactName();
        if (contact == null || contact.isBlank()) contact = c.getName();
        m.put("contactName",     contact != null ? contact : "");
        m.put("phone",           c.getPhone()           != null ? c.getPhone()           : "");
        m.put("companyName",     c.getCompanyName());
        m.put("taxCode",         c.getTaxCode());
        m.put("companyPhone",    c.getCompanyPhone());
        m.put("companyAddress",  c.getCompanyAddress());
        m.put("name",            c.getName());
        m.put("email",           c.getEmail());
        m.put("createdBySellerId", c.getCreatedBySeller() != null ? c.getCreatedBySeller().getId() : null);
        String createdBySellerName = c.getCreatedByName();
        if (createdBySellerName == null && c.getCreatedBySeller() != null) {
            createdBySellerName = c.getCreatedBySeller().getFullName() != null
                    ? c.getCreatedBySeller().getFullName() : c.getCreatedBySeller().getUsername();
        }
        m.put("createdBySellerName", createdBySellerName);
        m.put("createdByAdmin", c.getCreatedBySeller() == null);

        // ── Phân loại khách hàng ──────────────────────────────────────────────
        CustomerCategory cat = c.getCustomerCategory();
        m.put("categoryId",    cat != null ? cat.getId()    : null);
        m.put("categoryName",  cat != null ? cat.getName()  : null);
        m.put("categoryColor", cat != null ? cat.getColor() : null);

        return m;
    }

    private final CustomerCategoryRepository categoryRepository;
    @GetMapping("/customer-categories")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCustomerCategories() {
        try {
            List<Map<String, Object>> list = categoryRepository
                    .findAllByOrderBySortOrderAscNameAsc()
                    .stream()
                    .map(cat -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",        cat.getId());
                        m.put("name",      cat.getName());
                        m.put("color",     cat.getColor());
                        m.put("sortOrder", cat.getSortOrder());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Tìm kiếm phân loại theo keyword */
    @GetMapping("/customer-categories/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchCustomerCategories(
            @RequestParam(required = false, defaultValue = "") String q) {
        try {
            List<Map<String, Object>> list = (q.isBlank()
                    ? categoryRepository.findAllByOrderBySortOrderAscNameAsc()
                    : categoryRepository.findByNameContainingIgnoreCaseOrderBySortOrderAscNameAsc(q))
                    .stream()
                    .map(cat -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",        cat.getId());
                        m.put("name",      cat.getName());
                        m.put("color",     cat.getColor());
                        m.put("sortOrder", cat.getSortOrder());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success(list, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Tạo nhanh phân loại mới (từ combobox) */
    @PostMapping("/customer-categories")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createCustomerCategory(
            @RequestBody Map<String, Object> req) {
        try {
            String name = _str(req, "name");
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("Tên phân loại không được để trống");

            // Check trùng tên
            if (categoryRepository.findByNameIgnoreCase(name.trim()).isPresent())
                throw new IllegalArgumentException("Phân loại '" + name.trim() + "' đã tồn tại");

            String color = _str(req, "color");
            Object sortRaw = req.get("sortOrder");
            int sortOrder = sortRaw instanceof Number n ? n.intValue() : 0;

            CustomerCategory cat = CustomerCategory.builder()
                    .name(name.trim())
                    .color(color != null && !color.isBlank() ? color.trim() : null)
                    .sortOrder(sortOrder)
                    .build();
            cat = categoryRepository.save(cat);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",        cat.getId());
            m.put("name",      cat.getName());
            m.put("color",     cat.getColor());
            m.put("sortOrder", cat.getSortOrder());
            return ResponseEntity.ok(ApiResponse.success(m, "Đã tạo phân loại"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Xóa phân loại */
    @DeleteMapping("/customer-categories/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteCustomerCategory(@PathVariable Long id) {
        try {
            if (!categoryRepository.existsById(id))
                throw new RuntimeException("Không tìm thấy phân loại #" + id);
            categoryRepository.deleteById(id);
            return ResponseEntity.ok(ApiResponse.success(null, "Đã xóa phân loại"));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /** Change 12: Lấy KPI của seller hiện tại */
    @GetMapping("/kpi")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyKpi(Authentication auth) {
        try {
            User user = (User) auth.getPrincipal();
            List<Map<String, Object>> result = sellerKpiRepository
                    .findBySellerIdOrderByPeriodKeyDesc(user.getId())
                    .stream().map(k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("periodKey",    k.getPeriodKey());
                        m.put("totalOrders",  k.getTotalOrders());
                        m.put("totalRevenue", k.getTotalRevenue());
                        m.put("updatedAt",    k.getUpdatedAt());
                        return m;
                    }).toList();
            return ResponseEntity.ok(ApiResponse.success(result, "OK"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // IMPORT / EXPORT CUSTOMERS
    // ════════════════════════════════════════════════════════════════

    /** Download template Excel để import khách hàng */
    @GetMapping("/customers/import-template")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        try {
            byte[] bytes = _buildImportTemplate();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customer-import-template.xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[SELLER] downloadImportTemplate error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Export danh sách khách hàng ra Excel */
    @GetMapping("/customers/export")
    public ResponseEntity<byte[]> exportCustomers(Authentication auth) {
        try {
            User currentUser = (User) auth.getPrincipal();
            Set<Role> userRoles = currentUser.getAllRoles();
            boolean canSeeAll = userRoles.contains(Role.ADMIN) || userRoles.contains(Role.OWNER)
                    || userRoles.contains(Role.SUPERADMIN) || userRoles.contains(Role.SUPER_SELLER);

            var list = canSeeAll
                    ? customerRepository.findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc()
                    : customerRepository.findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc().stream()
                    .filter(c ->
                            c.getCustomerType() == Customer.CustomerType.RETAIL
                                    || (c.getCustomerType() == Customer.CustomerType.COMPANY
                                    && c.getCreatedBySeller() != null
                                    && Objects.equals(c.getCreatedBySeller().getId(), currentUser.getId()))
                                    || (c.getCustomerType() == Customer.CustomerType.COMPANY
                                    && c.getCreatedBySeller() == null)
                    )
                    .toList();

            byte[] bytes = _buildExportExcel(list);
            String filename = "customers-" + LocalDate.now() + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("[SELLER] exportCustomers error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Import khách hàng từ file Excel */
    @PostMapping("/customers/import")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> importCustomers(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        try {
            User currentUser = (User) auth.getPrincipal();
            String creatorName = currentUser.getFullName() != null && !currentUser.getFullName().isBlank()
                    ? currentUser.getFullName() : currentUser.getUsername();

            int imported = 0, skipped = 0;
            List<String> errors = new ArrayList<>();

            try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                // Row 0 = header, Row 1 = example/note, data from row 2
                for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String firstCell = _cellStr(row, 0);
                    if (firstCell == null || firstCell.isBlank()) continue;

                    try {
                        String typeStr  = _cellStr(row, 0);  // Loại KH
                        String code     = _cellStr(row, 1);  // Mã KH
                        String name     = _cellStr(row, 2);  // Tên KH / Tên công ty
                        String taxCode  = _cellStr(row, 3);  // MST
                        String address  = _cellStr(row, 4);  // Địa chỉ
                        String phone    = _cellStr(row, 5);  // SĐT
                        String email    = _cellStr(row, 6);  // Email

                        if (code == null || code.isBlank()) { errors.add("Dòng " + (r+1) + ": thiếu mã KH"); skipped++; continue; }
                        String upperCode = code.trim().toUpperCase();

                        // Check trùng mã
                        if (customerRepository.findByCustomerCodeAndDeletedAtIsNull(upperCode).isPresent()) { skipped++; continue; }

                        boolean isCompany = "COMPANY".equalsIgnoreCase(typeStr) || "Công ty".equalsIgnoreCase(typeStr);
                        Customer c = new Customer();
                        c.setCustomerCode(upperCode);
                        c.setCustomerType(isCompany ? Customer.CustomerType.COMPANY : Customer.CustomerType.RETAIL);
                        c.setPricingType(Customer.PricingType.RETAIL_PRICE);
                        c.setIsActive(true);
                        c.setDiscountRate(0);
                        c.setDebtDays(0);
                        c.setCreatedBySeller(currentUser);
                        c.setCreatedByName(creatorName);

                        if (isCompany) {
                            c.setCompanyName(name);
                            c.setTaxCode(taxCode);
                            c.setCompanyAddress(address);
                            c.setCompanyPhone(phone);
                            c.setEmail(email);
                        } else {
                            c.setName(name);
                            c.setPhone(phone != null && !phone.isBlank() ? phone.trim() : null);
                            c.setEmail(email);
                            c.setTaxCode(taxCode);
                        }

                        customerRepository.save(c);

                        // Parse receiver infos (cols 7,8,9 = địa chỉ, tên, sđt receiver 1)
                        // Có thể có nhiều receiver: cols 7-9, 10-12, 13-15, ...
                        int rcvIdx = 0;
                        for (int col = 7; col < row.getLastCellNum(); col += 3) {
                            String rAddr = _cellStr(row, col);
                            String rName = _cellStr(row, col + 1);
                            String rPhone = _cellStr(row, col + 2);
                            if (rAddr == null || rAddr.isBlank()) break;
                            CustomerReceiverInfo info = CustomerReceiverInfo.builder()
                                    .customer(c)
                                    .receiverAddress(rAddr.trim())
                                    .receiverName(rName != null && !rName.isBlank() ? rName.trim() : null)
                                    .receiverPhone(rPhone != null && !rPhone.isBlank() ? rPhone.trim() : null)
                                    .isDefault(rcvIdx == 0)
                                    .build();
                            receiverInfoRepository.save(info);
                            rcvIdx++;
                        }

                        imported++;
                    } catch (Exception rowErr) {
                        errors.add("Dòng " + (r+1) + ": " + rowErr.getMessage());
                        skipped++;
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("imported", imported);
            result.put("skipped", skipped);
            result.put("errors", errors);
            return ResponseEntity.ok(ApiResponse.success(result, "Import hoàn tất"));
        } catch (Exception e) {
            log.error("[SELLER] importCustomers error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    private String _cellStr(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private byte[] _buildImportTemplate() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet("Danh sách khách hàng");
            XSSFSheet helpSheet = wb.createSheet("Hướng dẫn");

            // ── Styles (giữ nguyên như cũ) ────────────────────────────────────
            XSSFCellStyle headerStyle = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontName("Arial");
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)26,(byte)39,(byte)68}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setWrapText(true);

            XSSFCellStyle subHeaderStyle = wb.createCellStyle();
            XSSFFont subHeaderFont = wb.createFont();
            subHeaderFont.setFontName("Arial");
            subHeaderFont.setFontHeightInPoints((short) 10);
            subHeaderFont.setColor(IndexedColors.WHITE.getIndex());
            subHeaderStyle.setFont(subHeaderFont);
            subHeaderStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)201,(byte)168,(byte)76}, null));
            subHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            subHeaderStyle.setAlignment(HorizontalAlignment.CENTER);
            subHeaderStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            subHeaderStyle.setBorderBottom(BorderStyle.THIN);
            subHeaderStyle.setBorderLeft(BorderStyle.THIN);
            subHeaderStyle.setBorderRight(BorderStyle.THIN);

            XSSFCellStyle exampleStyle = wb.createCellStyle();
            XSSFFont exampleFont = wb.createFont();
            exampleFont.setFontName("Arial");
            exampleFont.setFontHeightInPoints((short) 10);
            exampleFont.setItalic(true);
            exampleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            exampleStyle.setFont(exampleFont);
            exampleStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)250,(byte)248,(byte)243}, null));
            exampleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            exampleStyle.setBorderBottom(BorderStyle.THIN);
            exampleStyle.setBorderLeft(BorderStyle.THIN);
            exampleStyle.setBorderRight(BorderStyle.THIN);

            XSSFCellStyle dataStyle = wb.createCellStyle();
            XSSFFont dataFont = wb.createFont();
            dataFont.setFontName("Arial");
            dataFont.setFontHeightInPoints((short) 10);
            dataStyle.setFont(dataFont);
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);

            // ── Row 0: Title ──────────────────────────────────────────────────
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(28);
            XSSFCellStyle titleStyle = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontName("Arial");
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setColor(new XSSFColor(new byte[]{(byte)26,(byte)39,(byte)68}, null));
            titleStyle.setFont(titleFont);
            titleStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)253,(byte)248,(byte)237}, null));
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setAlignment(HorizontalAlignment.LEFT);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("📋 TEMPLATE IMPORT KHÁCH HÀNG — Nhất Nam");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 9));

            // ── Row 1: Headers (10 cột) ───────────────────────────────────────
            // Cột 0-6: thông tin khách | Cột 7-9: 1 địa chỉ nhận / dòng
            Row headerRow = sheet.createRow(1);
            headerRow.setHeightInPoints(36);
            String[] headers = {
                    "Loại KH *", "Mã KH *", "Tên KH / Tên công ty", "Mã số thuế",
                    "Địa chỉ", "Số điện thoại", "Email",
                    "Địa chỉ nhận *", "Tên người nhận", "SĐT người nhận"
            };
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ── Row 2: Sub-header descriptions ───────────────────────────────
            Row descRow = sheet.createRow(2);
            descRow.setHeightInPoints(20);
            String[] descs = {
                    "RETAIL hoặc COMPANY", "Chữ hoa, số, dấu -/_", "Bắt buộc với COMPANY", "Không trùng",
                    "Địa chỉ khách hàng", "10-15 số", "Email hợp lệ",
                    "Bắt buộc cho địa chỉ nhận", "Tuỳ chọn", "Tuỳ chọn"
            };
            for (int i = 0; i < descs.length; i++) {
                Cell cell = descRow.createCell(i);
                cell.setCellValue(descs[i]);
                cell.setCellStyle(subHeaderStyle);
            }

            // ── Rows 3-5: Ví dụ RETAIL (1 khách, 3 địa chỉ nhận = 3 dòng) ───
            String[][] retailBase = {
                    {"RETAIL", "KH001", "Nguyễn Văn An", "", "12 Lê Lợi, Q.1", "0901234567", "an@gmail.com"},
                    {"RETAIL", "KH001", "Nguyễn Văn An", "", "12 Lê Lợi, Q.1", "0901234567", "an@gmail.com"},
                    {"RETAIL", "KH001", "Nguyễn Văn An", "", "12 Lê Lợi, Q.1", "0901234567", "an@gmail.com"},
            };
            String[][] retailReceiver = {
                    {"447 Huỳnh Văn Bánh, P.Phú Nhuận", "Nguyễn Văn An",   "0901234567"},
                    {"321 Huỳnh Văn Bánh, P.Phú Nhuận", "Nguyễn Văn Banh", "0901234568"},
                    {"123 Huỳnh Văn Bánh, P.Phú Nhuận", "Nguyễn Văn Danh", "0901234569"},
            };
            for (int i = 0; i < 3; i++) {
                Row row = sheet.createRow(3 + i);
                for (int j = 0; j < 7; j++) {
                    Cell c = row.createCell(j);
                    c.setCellValue(retailBase[i][j]);
                    c.setCellStyle(exampleStyle);
                }
                for (int j = 0; j < 3; j++) {
                    Cell c = row.createCell(7 + j);
                    c.setCellValue(retailReceiver[i][j]);
                    c.setCellStyle(exampleStyle);
                }
            }

            // ── Rows 6-7: Ví dụ COMPANY (1 khách, 2 địa chỉ nhận = 2 dòng) ──
            String[][] companyBase = {
                    {"COMPANY", "CTY001", "Công ty TNHH ABC", "0123456789", "123 Nguyễn Huệ, Q.1", "02812345678", "info@abc.com"},
                    {"COMPANY", "CTY001", "Công ty TNHH ABC", "0123456789", "123 Nguyễn Huệ, Q.1", "02812345678", "info@abc.com"},
            };
            String[][] companyReceiver = {
                    {"123 Nguyễn Huệ, Q.1",   "Trần Thị B", "0909876543"},
                    {"456 Lê Văn Sỹ, Q.3",    "",           ""},
            };
            for (int i = 0; i < 2; i++) {
                Row row = sheet.createRow(6 + i);
                for (int j = 0; j < 7; j++) {
                    Cell c = row.createCell(j);
                    c.setCellValue(companyBase[i][j]);
                    c.setCellStyle(exampleStyle);
                }
                for (int j = 0; j < 3; j++) {
                    Cell c = row.createCell(7 + j);
                    c.setCellValue(companyReceiver[i][j]);
                    c.setCellStyle(exampleStyle);
                }
            }

            // ── Empty data rows (từ row 8) ────────────────────────────────────
            for (int r = 8; r <= 107; r++) {
                Row dataRow = sheet.createRow(r);
                for (int c = 0; c < 10; c++) {
                    dataRow.createCell(c).setCellStyle(dataStyle);
                }
            }

            // ── Column widths ─────────────────────────────────────────────────
            int[] colWidths = {14, 14, 28, 16, 30, 16, 28, 38, 22, 16};
            for (int i = 0; i < colWidths.length; i++) {
                sheet.setColumnWidth(i, colWidths[i] * 256);
            }

            // ── Help sheet (giữ nguyên) ───────────────────────────────────────
            Row h1 = helpSheet.createRow(0);
            h1.createCell(0).setCellValue("HƯỚNG DẪN IMPORT KHÁCH HÀNG");
            Row h2 = helpSheet.createRow(2);
            h2.createCell(0).setCellValue("Cột");
            h2.createCell(1).setCellValue("Mô tả");
            h2.createCell(2).setCellValue("Bắt buộc?");
            String[][] helpData = {
                    {"Loại KH",          "RETAIL (cá nhân) hoặc COMPANY (công ty)",                           "Có"},
                    {"Mã KH",            "Mã duy nhất, chữ hoa số, dấu - _. Nếu trùng sẽ bỏ qua.",           "Có"},
                    {"Tên KH / Tên cty", "Tên hiển thị. Bắt buộc với COMPANY.",                               "COMPANY: Có"},
                    {"Mã số thuế",       "MST doanh nghiệp hoặc cá nhân. Có thể để trống.",                   "Không"},
                    {"Địa chỉ",          "Địa chỉ chính của khách hàng.",                                     "Không"},
                    {"Số điện thoại",    "SĐT liên hệ chính (10-15 số).",                                     "Không"},
                    {"Email",            "Email liên hệ.",                                                     "Không"},
                    {"Địa chỉ nhận",     "Mỗi địa chỉ nhận hàng = 1 dòng. Lặp lại Mã KH để thêm nhiều địa chỉ.", "Có"},
                    {"Tên người nhận",   "Tên người nhận tại địa chỉ đó (tuỳ chọn).",                         "Không"},
                    {"SĐT người nhận",   "SĐT người nhận tại địa chỉ đó (tuỳ chọn).",                         "Không"},
            };
            for (int i = 0; i < helpData.length; i++) {
                Row hr = helpSheet.createRow(3 + i);
                hr.createCell(0).setCellValue(helpData[i][0]);
                hr.createCell(1).setCellValue(helpData[i][1]);
                hr.createCell(2).setCellValue(helpData[i][2]);
            }
            helpSheet.setColumnWidth(0, 20 * 256);
            helpSheet.setColumnWidth(1, 65 * 256);
            helpSheet.setColumnWidth(2, 16 * 256);

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] _buildExportExcel(List<Customer> customers) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet("Khách hàng");

            // ── Styles ────────────────────────────────────────────────────────
            // Header style (navy)
            XSSFCellStyle headerStyle = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontName("Arial");
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)26,(byte)39,(byte)68}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setWrapText(true);

            // Title style
            XSSFCellStyle titleStyle = wb.createCellStyle();
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontName("Arial");
            titleFont.setFontHeightInPoints((short) 14);
            titleFont.setColor(new XSSFColor(new byte[]{(byte)26,(byte)39,(byte)68}, null));
            titleStyle.setFont(titleFont);
            titleStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)253,(byte)248,(byte)237}, null));
            titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titleStyle.setAlignment(HorizontalAlignment.LEFT);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            titleStyle.setWrapText(true);

            // Data style
            XSSFCellStyle dataStyle = wb.createCellStyle();
            XSSFFont dataFont = wb.createFont();
            dataFont.setFontName("Arial");
            dataFont.setFontHeightInPoints((short) 10);
            dataStyle.setFont(dataFont);
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);

            dataStyle.setWrapText(true);
            dataStyle.setVerticalAlignment(VerticalAlignment.TOP);

            // Data style — company (highlight nhẹ nền xanh)
            XSSFCellStyle dataCompanyStyle = wb.createCellStyle();
            dataCompanyStyle.cloneStyleFrom(dataStyle);
            dataCompanyStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)235,(byte)242,(byte)255}, null));
            dataCompanyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Data style — admin tạo (highlight nền vàng nhạt)
            XSSFCellStyle dataAdminStyle = wb.createCellStyle();
            dataAdminStyle.cloneStyleFrom(dataStyle);
            dataAdminStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte)253,(byte)248,(byte)237}, null));
            dataAdminStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // ── Row 0: Title ──────────────────────────────────────────────────
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(28);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("📋 DANH SÁCH KHÁCH HÀNG — Nhất Nam  ·  Xuất ngày "
                    + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 11));

            // ── Row 1: Headers ────────────────────────────────────────────────
            Row headerRow = sheet.createRow(1);
            headerRow.setHeightInPoints(36);
            String[] cols = {
                    "Loại KH", "Mã KH", "Tên / Tên công ty", "MST",
                    "Địa chỉ", "SĐT", "Email",
                    "Địa chỉ nhận", "Tên người nhận", "SĐT người nhận",
                    "Người tạo", "Ngày tạo"
            };
            for (int i = 0; i < cols.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(cols[i]);
                cell.setCellStyle(headerStyle);
            }

            // ── Data rows ─────────────────────────────────────────────────────
            java.time.format.DateTimeFormatter dtf =
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

            int r = 2;
            for (Customer c : customers) {
                if (c.getDeletedAt() != null && c.getDeletedAt() > 0) continue;
                boolean isCompany = c.getCustomerType() == Customer.CustomerType.COMPANY;
                boolean isAdminCreated = c.getCreatedBySeller() == null;

                // Chọn style cho dòng data
                XSSFCellStyle rowStyle = isAdminCreated ? dataAdminStyle
                        : isCompany ? dataCompanyStyle
                        : dataStyle;

                String typeStr    = isCompany ? "Công ty" : "Cá nhân";
                String code       = c.getCustomerCode()  != null ? c.getCustomerCode()  : "";
                String name       = isCompany
                        ? (c.getCompanyName()    != null ? c.getCompanyName()    : "")
                        : (c.getName()           != null ? c.getName()           : "");
                String taxCode    = c.getTaxCode()       != null ? c.getTaxCode()       : "";
                String address    = isCompany
                        ? (c.getCompanyAddress() != null ? c.getCompanyAddress() : "")
                        : "";
                String phone      = c.getPhone()         != null ? c.getPhone()         : "";
                String email      = c.getEmail()         != null ? c.getEmail()         : "";
                String creator    = c.getCreatedByName() != null ? c.getCreatedByName()
                        : (c.getCreatedBySeller() != null
                        ? (c.getCreatedBySeller().getFullName() != null
                        ? c.getCreatedBySeller().getFullName()
                        : c.getCreatedBySeller().getUsername())
                        : "Admin");
                String createdAt  = c.getCreatedAt() != null
                        ? java.time.Instant.ofEpochMilli(c.getCreatedAt())
                        .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(dtf)
                        : "";

                List<CustomerReceiverInfo> receivers = c.getReceiverInfos();

                if (receivers == null || receivers.isEmpty()) {
                    // Không có địa chỉ nhận → 1 dòng, cột receiver để trống
                    Row row = sheet.createRow(r++);
                    row.setHeight((short)-1); // auto height
                    _fillCustomerCells(row, rowStyle,
                            typeStr, code, name, taxCode, address, phone, email,
                            "", "", "",
                            creator, createdAt);
                } else {
                    // Mỗi địa chỉ nhận = 1 dòng, thông tin khách lặp lại
                    for (CustomerReceiverInfo recv : receivers) {
                        Row row = sheet.createRow(r++);
                        row.setHeight((short)-1); // auto height
                        _fillCustomerCells(row, rowStyle,
                                typeStr, code, name, taxCode, address, phone, email,
                                recv.getReceiverAddress() != null ? recv.getReceiverAddress() : "",
                                recv.getReceiverName()    != null ? recv.getReceiverName()    : "",
                                recv.getReceiverPhone()   != null ? recv.getReceiverPhone()   : "",
                                creator, createdAt);
                    }
                }
            }

            // ── Column widths ─────────────────────────────────────────────────
            int[] widths = {12, 14, 28, 16, 30, 14, 26, 35, 22, 14, 18, 12};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }

            // Freeze header rows
            sheet.createFreezePane(0, 2);

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ── Helper ghi 1 dòng ─────────────────────────────────────────────────────────
    private void _fillCustomerCells(Row row, XSSFCellStyle style,
                                    String type, String code, String name, String taxCode,
                                    String address, String phone, String email,
                                    String recvAddr, String recvName, String recvPhone,
                                    String creator, String createdAt) {

        String[] values = {
                type, code, name, taxCode, address, phone, email,
                recvAddr, recvName, recvPhone,
                creator, createdAt
        };
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(style);
        }
    }
}