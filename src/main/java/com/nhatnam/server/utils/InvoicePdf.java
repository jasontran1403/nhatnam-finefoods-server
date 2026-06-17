package com.nhatnam.server.utils;

import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.nhatnam.server.dto.InvoiceDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Log4j2
public class InvoicePdf {

    private static final String TEMPLATE_PATH = "pdf.html";
    private static final ZoneId VN_TZ         = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int    ROWS_PER_PAGE = 14;
    private static final int    TARGET_ROWS   = 14;

    public byte[] GenerateInvoicePdf(InvoiceDTO dto) throws IOException {
        return GenerateInvoicePdf(dto, true);
    }

    public byte[] generateDraftInvoicePdf(InvoiceDTO dto, boolean showPrices) throws IOException {
        String html = buildHtml(dto, showPrices);
        ByteArrayOutputStream firstPass = new ByteArrayOutputStream();
        HtmlConverter.convertToPdf(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                firstPass);
        return addWatermark(firstPass.toByteArray(), "FINE FOODS");
    }

    public byte[] GenerateInvoicePdf(InvoiceDTO dto, boolean showPrices) throws IOException {
        String html = buildHtml(dto, showPrices);
        ByteArrayOutputStream firstPass = new ByteArrayOutputStream();
        HtmlConverter.convertToPdf(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                firstPass);
        return addWatermark(firstPass.toByteArray(), "FINE FOODS");
    }

    // ── Watermark ─────────────────────────────────────────────────────────────
    private byte[] addWatermark(byte[] inputPdf, String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (PdfDocument pdfDoc = new PdfDocument(
                new PdfReader(new ByteArrayInputStream(inputPdf)),
                new PdfWriter(out))) {

            for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
                PdfPage   page   = pdfDoc.getPage(i);
                Rectangle pSize  = page.getPageSize();
                PdfCanvas canvas = new PdfCanvas(page);

                PdfExtGState gs = new PdfExtGState().setFillOpacity(0.1f);
                canvas.setExtGState(gs);

                float centerX = pSize.getWidth()  / 2.4f;
                float centerY = pSize.getHeight() / 1.75f;

                canvas.saveState();
                canvas.setFillColor(ColorConstants.RED);

                PdfFont font = PdfFontFactory.createFont(
                        com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD,
                        PdfEncodings.WINANSI, PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED);

                canvas.concatMatrix(
                        Math.cos(Math.toRadians(0)), Math.sin(Math.toRadians(0)),
                        -Math.sin(Math.toRadians(0)), Math.cos(Math.toRadians(0)),
                        centerX, centerY);

                canvas.beginText()
                        .setFontAndSize(font, 64)
                        .setTextRenderingMode(0)
                        .setCharacterSpacing(8)
                        .moveText(-180, -20)
                        .showText(text)
                        .endText();

                canvas.restoreState();
            }
        }
        return out.toByteArray();
    }

    // ── HTML builder ──────────────────────────────────────────────────────────
    private String buildHtml(InvoiceDTO dto, boolean showPrices) throws IOException {
        InputStream is = InvoicePdf.class.getClassLoader().getResourceAsStream(TEMPLATE_PATH);
        if (is == null) throw new FileNotFoundException("Template not found: " + TEMPLATE_PATH);
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        String logoSrc = loadLogoBase64();
        String fontCSS = buildFontFaceCSS();
        html = html.replace("</style>", fontCSS + "\n</style>");

        // Lấy giá trị hideAllPrices từ DTO (mặc định false)
        boolean hideAllPrices = dto.getHideAllPrices() != null && dto.getHideAllPrices();

        // ── Order code ────────────────────────────────────────────────────────
        String orderCode = safe(dto.getOrderCode(), "N/A");
        String prefix = orderCode, seq = "";
        int dash = orderCode.lastIndexOf('-');
        if (dash >= 0) {
            prefix = orderCode.substring(0, dash);
            seq    = orderCode.substring(dash + 1);
        }

        // ── Customer info ─────────────────────────────────────────────────────
        String custName  = safe(dto.getCustomerName(), "Khách lẻ");
        custName = custName.equalsIgnoreCase("Khách vãng lai") ? "" : custName;
        String custPhone = safe(dto.getCustomerPhone(), "");
        String custAddr  = safe(dto.getDeliveryAddress() != null
                ? dto.getDeliveryAddress() : dto.getShippingAddress(), "");
        String receiverName = safe(dto.getReceiverName() != null ? dto.getReceiverName() : "", "");
        String orderedBy    = (dto.getOrderedByName() != null && !dto.getOrderedByName().isBlank())
                ? dto.getOrderedByName() : "";

        String paymentDisplay = buildPaymentDisplay(dto.getPaymentMethod());
        String deliTime  = formatDeliveryTime(dto.getDeliveryDatetime());
        String orderDate = "N/A";
        if (dto.getCreatedAt() != null) {
            orderDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(dto.getCreatedAt()), VN_TZ)
                    .format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
        }

        // ── Sắp xếp items ─────────────────────────────────────────────────────
        java.util.List<InvoiceDTO.Item> allItems = dto.getItems() != null
                ? dto.getItems() : java.util.List.of();
        java.util.List<InvoiceDTO.Item> normalItems = allItems.stream()
                .filter(i -> !isPromoItem(i)).toList();
        java.util.List<InvoiceDTO.Item> promoItems = allItems.stream()
                .filter(InvoicePdf::isPromoItem).toList();
        java.util.List<InvoiceDTO.Item> sortedItems = new java.util.ArrayList<>();
        sortedItems.addAll(normalItems);
        sortedItems.addAll(promoItems);

        // ── Build row data ─────────────────────────────────────────────────────
        BigDecimal grossSubtotal = BigDecimal.ZERO;
        int dataRows = 0;
        java.util.List<Object[]> rows = new java.util.ArrayList<>();

        for (InvoiceDTO.Item item : sortedItems) {
            BigDecimal qty       = bd(item.getQuantity());
            boolean    km        = isPromoItem(item);
            String     promoNote = km ? extractPromoNote(item.getNotes()) : "";

            BigDecimal unitPriceRaw = bd(item.getUnitPrice()).compareTo(BigDecimal.ZERO) > 0
                    ? bd(item.getUnitPrice())
                    : bd(item.getDefaultPrice());

            boolean isBox = "BOX".equals(item.getSaleType());

            BigDecimal displayPrice = km ? BigDecimal.ZERO
                    : (isBox && item.getUnitsPerBox() != null && item.getUnitsPerBox() > 0
                    ? unitPriceRaw.multiply(BigDecimal.valueOf(item.getUnitsPerBox()))
                    : unitPriceRaw);

            BigDecimal lineAmt = km ? BigDecimal.ZERO
                    : displayPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP);

            if (!km) {
                grossSubtotal = grossSubtotal.add(lineAmt);
            }

            String unit    = safe(item.getUnit(), "");
            String dvt     = capitalizeWords(unit);

            String dongGoi;
            if (isBox && item.getUnitsPerBox() != null && item.getUnitsPerBox() > 0) {
                dongGoi = "Thùng<br/><span style='font-size:10px;line-height:1.2;'>(&#215;"
                        + item.getUnitsPerBox() + " " + esc(dvt) + ")</span>";
                dvt = "Thùng";
            } else if (isBox) {
                dongGoi = "Thùng";
                dvt     = "Thùng";
            } else {
                dongGoi = "";
            }

            String qtyDisplay;
            if ("KG".equalsIgnoreCase(unit) && dongGoi.isEmpty()) {
                qtyDisplay = qty.setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
            } else {
                qtyDisplay = qty.setScale(0, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
            }

            rows.add(new Object[]{esc(item.getProductName()), dongGoi, esc(dvt),
                    qtyDisplay, km, promoNote, displayPrice, lineAmt});
            dataRows++;
        }

        // ── Summary ───────────────────────────────────────────────────────────
        BigDecimal totalDiscount = bd(dto.getDiscountAmount());

        boolean hasInclusive = dto.getItems() != null && dto.getItems().stream()
                .anyMatch(i -> !isPromoItem(i) && "INCLUSIVE".equalsIgnoreCase(i.getVatMode()));
        boolean hasExclusive = dto.getItems() != null && dto.getItems().stream()
                .anyMatch(i -> !isPromoItem(i) && "EXCLUSIVE".equalsIgnoreCase(i.getVatMode()));

        StringBuilder vatRows = new StringBuilder();

        // Nếu hideAllPrices = true thì không hiển thị bất kỳ dòng giá nào (kể cả tổng cộng)
        if (!hideAllPrices && showPrices) {
            if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
                vatRows.append(sumRow("Giảm giá:", "-" + fc(totalDiscount)));
            }

            Map<Integer, BigDecimal> inclMap = dto.getVatBreakdownInclusive();
            if (inclMap != null && !inclMap.isEmpty()) {
                BigDecimal totalIncl = inclMap.values().stream()
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (totalIncl.compareTo(BigDecimal.ZERO) > 0) {
                    vatRows.append(vatInclusiveRow("VAT (đã bao gồm):", fc(totalIncl)));
                    if (inclMap.size() > 1) {
                        for (Map.Entry<Integer, BigDecimal> e : new TreeMap<>(inclMap).entrySet()) {
                            vatRows.append("""
                    <tr class="vat-breakdown">
                        <td class="sum-lbl vat-child" style="font-style:italic;">+ %d%%</td>
                        <td class="sum-val vat-child" style="font-style:italic;">%s</td>
                    </tr>
                    """.formatted(e.getKey(), fc(e.getValue())));
                        }
                    }
                }
            }

            Map<Integer, BigDecimal> exclMap = dto.getVatBreakdownExclusive();
            if (exclMap != null && !exclMap.isEmpty()) {
                BigDecimal totalExcl = exclMap.values().stream()
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (totalExcl.compareTo(BigDecimal.ZERO) > 0) {
                    vatRows.append(sumRow("VAT (ngoài giá):", "+" + fc(totalExcl)));
                    if (exclMap.size() > 1) {
                        for (Map.Entry<Integer, BigDecimal> e : new TreeMap<>(exclMap).entrySet()) {
                            vatRows.append("""
                    <tr class="vat-breakdown">
                        <td class="sum-lbl vat-child">+ %d%%</td>
                        <td class="sum-val vat-child">%s</td>
                    </tr>
                    """.formatted(e.getKey(), fc(e.getValue())));
                        }
                    }
                }
            }

            BigDecimal surcharge = bd(dto.getSurcharge());
            if (surcharge.compareTo(BigDecimal.ZERO) > 0) {
//                vatRows.append(sumRow("Tổng phụ phí:", fc(surcharge)));
                if (dto.getSurchargeDetail() != null && !dto.getSurchargeDetail().isBlank()) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                        java.util.List<java.util.Map<String,Object>> items = om.readValue(
                                dto.getSurchargeDetail(),
                                new com.fasterxml.jackson.core.type.TypeReference<>(){});
                        for (java.util.Map<String,Object> item : items) {
                            String itemName = String.valueOf(item.getOrDefault("name",""));
                            Object amtObj = item.get("amount");
                            if (amtObj == null || itemName.isBlank()) continue;
                            BigDecimal itemAmt = new BigDecimal(amtObj.toString());
                            vatRows.append("""
                         <tr>
                            <td class="sum-lbl" style="padding-left:16px;font-size:11px;">* %s</td>
                            <td class="sum-val" style="font-size:11px;">%s</td>
                         </tr>
                        """.formatted(esc(itemName), fc(itemAmt)));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        BigDecimal finalAmt  = bd(dto.getFinalAmount());
        String     amtWords  = numberToVietnamese(finalAmt.setScale(0, RoundingMode.HALF_UP).longValue());

        // ── Phân trang ────────────────────────────────────────────────────────
        int totalPages  = (dataRows == 0) ? 1 : (int) Math.ceil((double) dataRows / ROWS_PER_PAGE);
        boolean multiPage = totalPages > 1;

        String baseInfo = buildTopInfoBlock(custName, custAddr, custPhone, paymentDisplay,
                orderDate, receiverName, orderedBy, deliTime);

        StringBuilder pages = new StringBuilder();

        for (int pageIdx = 0; pageIdx < totalPages; pageIdx++) {
            int fromRow    = pageIdx * ROWS_PER_PAGE;
            int toRow      = Math.min(fromRow + ROWS_PER_PAGE, dataRows);
            boolean isLastPage = (pageIdx == totalPages - 1);

            StringBuilder itemsHtml = new StringBuilder();
            for (int r = fromRow; r < toRow; r++) {
                Object[]   row      = rows.get(r);
                String     rName    = (String)     row[0];
                String     rDongGoi = (String)     row[1];
                String     rDvt     = (String)     row[2];
                String     rQty     = (String)     row[3];
                boolean    rIsKm    = (Boolean)    row[4];
                String     rNote    = (String)     row[5];
                BigDecimal rPrice   = (BigDecimal) row[6];
                BigDecimal rAmt     = (BigDecimal) row[7];

                itemsHtml.append("<tr>")
                        .append("<td class='c'>").append(r + 1).append("</td>")
                        .append("<td>").append(rName).append("</td>")
                        .append("<td class='c'>").append(rDongGoi).append("</td>")
                        .append("<td class='c dvt'>").append(rDvt).append("</td>")
                        .append("<td class='c sl'>").append(rQty).append("</td>");

                if (rIsKm) {
                    itemsHtml.append("<td class='c'></td>");
                    String noteText = (rNote == null || rNote.isEmpty())
                            ? "KM" : wrapPromoText(esc(rNote), 18);
                    itemsHtml.append("<td style='font-size:10px;color:#e11d48;font-style:italic;text-align:right;padding-right:6px;'>")
                            .append(noteText).append("</td>");
                } else if (!hideAllPrices && showPrices) {
                    itemsHtml.append("<td class='r'>").append(fc(rPrice)).append("</td>")
                            .append("<td class='r'>").append(fc(rAmt)).append("</td>");
                } else {
                    itemsHtml.append("<td></td><td></td>");
                }
                itemsHtml.append("</tr>");
            }

            int blankNeeded = Math.max(0, ROWS_PER_PAGE - (toRow - fromRow));
            String blanks = "<tr class='blank'><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>"
                    .repeat(blankNeeded);

            String summaryBlock = "";
            if (isLastPage) {
                if (hideAllPrices) {
                    // Trường hợp che toàn bộ: không hiển thị bất kỳ dòng tổng nào
                    summaryBlock = "";
                } else {
                    StringBuilder sumInner = new StringBuilder();
                    if (showPrices) {
                        sumInner.append(sumRow("Thành tiền:", fc(grossSubtotal)));
                        sumInner.append(vatRows);
                    }
                    sumInner.append("<tr class='grand-total'><td class='sum-lbl'>Tổng cộng:</td><td class='sum-val'>")
                            .append(fc(finalAmt)).append("</td></tr>");

                    summaryBlock = "<table class='summary-wrapper'>"
                            + "<colgroup>"
                            + "<col style='width:5%'><col style='width:30%'><col style='width:8%'>"
                            + "<col style='width:7%'><col style='width:7%'><col style='width:10%'><col style='width:23%'>"
                            + "</colgroup><tr>"
                            + "<td colspan='4' style='border:none;padding:0;'></td>"
                            + "<td colspan='3' style='border:none;padding:0;vertical-align:top;'>"
                            + "<table class='summary-inner'>" + sumInner + "</table>"
                            + "</td></tr></table>";

                    if (showPrices) {
                        summaryBlock += "<div class='total-words'><span class='total-words-label'>Tổng thành tiền (viết bằng chữ): "
                                + esc(amtWords) + "</span><span class='dotline'></span></div>";
                    }
                }
            }

            String pageNumBlock = multiPage
                    ? "<div class='page-num'>Trang " + (pageIdx + 1) + "/" + totalPages + "</div>"
                    : "";

            String sigBlock = "<div class='sig-row'>"
                    + "<div class='sig-cell'><div class='sig-title'>Trưởng bộ phận</div><div class='sig-name'>&nbsp;</div></div>"
                    + "<div class='sig-cell'><div class='sig-title'>Thủ kho</div><div class='sig-name'>&nbsp;</div></div>"
                    + "<div class='sig-cell'><div class='sig-title'>Người giao</div><div class='sig-name'>&nbsp;</div></div>"
                    + "<div class='sig-cell'><div class='sig-title'>Người nhận</div><div class='sig-name'>&nbsp;</div></div>"
                    + "<div class='sig-cell'><div class='sig-title'>Người lập phiếu</div><div class='sig-name'></div></div>"
                    + "</div>";

            boolean isDraft     = orderCode.startsWith("DRAFT-");
            boolean isCancelled = "CANCELLED".equalsIgnoreCase(safe(dto.getStatus(), ""));

            String pageHeader;
            if (isDraft) {
                pageHeader = "<div class='header'>"
                        + "<div class='header-left'><img src='https://iili.io/3vdqwMl.md.png' alt='Logo'></div>"
                        + "<div class='header-center'><div class='doc-title'>Phiếu Đặt Hàng</div></div>"
                        + "<div class='header-right'></div>"
                        + "</div>";
            } else if (isCancelled) {
                pageHeader = "<div class='header'>"
                        + "<div class='header-left'><img src='https://iili.io/3vdqwMl.md.png' alt='Logo'></div>"
                        + "<div class='header-center'><div class='doc-title'>Phiếu Đặt Hàng</div></div>"
                        + "<div class='header-right'>" + esc(prefix) + ": <span class='doc-id-num'>" + esc(seq) + "</span>"
                        + "<div style='margin-top:5px;font-size:26px;font-weight:900;color:#DC2626;text-align:right;'>"
                        + "ĐƠN ĐÃ HỦY</div></div>"
                        + "</div>";
            } else {
                pageHeader = "<div class='header'>"
                        + "<div class='header-left'><img src='https://iili.io/3vdqwMl.md.png' alt='Logo'></div>"
                        + "<div class='header-center'><div class='doc-title'>Phiếu Đặt Hàng</div></div>"
                        + "<div class='header-right'>" + esc(prefix) + ": <span class='doc-id-num'>" + esc(seq) + "</span>"
                        + "<div style='margin-top:5px;font-size:12px;font-weight:600;color:#444;text-align:right;'>"
                        + java.time.LocalDate.now().getYear() + "&nbsp;-&nbsp;............</div></div>"
                        + "</div>";
            }

            pages.append("<div class='page'>")
                    .append(pageNumBlock)
                    .append(pageHeader)
                    .append(baseInfo)
                    .append("<table class='items'><thead><tr>")
                    .append("<th style='width:5%'>STT</th>")
                    .append("<th style='width:39%;text-align:left;padding-left:5px;'>Tên Sản Phẩm</th>")
                    .append("<th style='width:8%'>QC</th>")
                    .append("<th style='width:7%'>ĐVT</th>")
                    .append("<th style='width:7%'>SL</th>")
                    .append("<th style='width:15.5%;text-align:right'>Đơn giá</th>")
                    .append("<th style='width:18.5%;text-align:right'>Thành tiền</th>")
                    .append("</tr></thead><tbody>")
                    .append(itemsHtml)
                    .append(blanks)
                    .append("</tbody></table>")
                    .append(summaryBlock)
                    .append(isLastPage ? sigBlock : "")
                    .append("</div>");

            if (!isLastPage) pages.append("<div class='page-break'></div>");
        }

        String pagesStr = pages.toString();
        if (logoSrc != null) {
            pagesStr = pagesStr.replace("https://iili.io/3vdqwMl.md.png", logoSrc);
        }
        html = html.replace("{{pages}}", pagesStr);
        return html;
    }

    // ── fc: format tiền VNĐ ───────────────────────────────────────────────────
    private static String fc(BigDecimal value) {
        if (value == null) return "0 đ";
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        return df.format(rounded) + " đ";
    }

    @Deprecated
    private static String fc(double a) { return fc(BigDecimal.valueOf(a)); }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String buildPaymentDisplay(String paymentMethod) {
        if ("CASH".equalsIgnoreCase(paymentMethod)) return "Tiền mặt - COD";
        return resolvePaymentLabel(paymentMethod);
    }

    private String formatDeliveryTime(Long timestamp) {
        if (timestamp == null || timestamp <= 0) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), VN_TZ)
                .format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
    }

    private static boolean isPromoItem(InvoiceDTO.Item item) {
        return item.getNotes() != null && item.getNotes().startsWith("[KM]");
    }

    private static String extractPromoNote(String notes) {
        if (notes == null) return "";
        if (notes.startsWith("[KM] ")) return notes.substring(5).trim();
        if (notes.startsWith("[KM]"))  return notes.substring(4).trim();
        return "";
    }

    private String buildTopInfoBlock(String custName, String custAddr, String custPhone,
                                     String paymentDisplay, String orderDate, String receiverName,
                                     String orderedBy, String deliTime) {
        return "<table class='top-info-table'><tr>"
                + "<td class='col-l'><table style='width:100%;border-collapse:collapse;'>"
                + "<tr><td class='lbl' style='white-space:nowrap;width:1%;vertical-align:top;'>Tên khách hàng:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(custName) + "</td></tr>"
                + "<tr><td class='lbl' style='white-space:nowrap;width:1%;vertical-align:top;'>Địa chỉ giao hàng:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(custAddr) + "</td></tr>"
                + "<tr><td class='lbl' style='white-space:nowrap;width:1%;vertical-align:top;'>Điện thoại:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(custPhone) + "</td></tr>"
                + "<tr><td class='lbl' style='white-space:nowrap;width:1%;vertical-align:top;'>Thanh toán:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(paymentDisplay) + "</td></tr>"
                + "</table></td>"
                + "<td class='col-r'><table style='width:100%;border-collapse:collapse;'>"
                + "<tr><td class='lbl' style='white-space:nowrap;vertical-align:top;'>Ngày đặt hàng:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(orderDate) + "</td></tr>"
                + "<tr><td class='lbl' style='white-space:nowrap;vertical-align:top;'>Người nhận:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(receiverName) + "</td></tr>"
                + "<tr><td class='lbl' style='white-space:nowrap;vertical-align:top;'>Người đặt hàng:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(orderedBy) + "</td></tr>"
                + "<tr><td class='lbl' style='white-space:nowrap;vertical-align:top;'>Ngày giờ giao hàng:</td>"
                + "<td class='customer-info val' style='word-wrap:break-word;word-break:break-word;white-space:normal;'>" + esc(deliTime) + "</td></tr>"
                + "</table></td>"
                + "</tr></table>";
    }

    // Row thường
    private static String sumRow(String label, String value) {
        return "<tr><td class=\"sum-lbl\">" + esc(label)
                + "</td><td class=\"sum-val\">" + value + "</td></tr>";
    }

    // Row VAT đã trong giá: in nghiêng
    private static String vatInclusiveRow(String label, String value) {
        return "<tr>"
                + "<td class=\"sum-lbl\" style=\"font-style:italic;\">" + esc(label) + "</td>"
                + "<td class=\"sum-val\" style=\"font-style:italic;\">" + value + "</td>"
                + "</tr>";
    }

    private static String capitalizeWords(String s) {
        if (s == null || s.isBlank()) return s;
        String[] words = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static String safe(String v, String fb) { return (v != null && !v.isBlank()) ? v : fb; }
    private static BigDecimal bd(BigDecimal v)       { return v != null ? v : BigDecimal.ZERO; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    private static String resolvePaymentLabel(String m) {
        if (m == null) return "Tiền mặt";
        return switch (m.toUpperCase()) {
            case "CASH"          -> "Tiền mặt";
            case "BANK_TRANSFER" -> "Chuyển khoản";
            case "DEBT", "OTHER" -> "Công nợ";
            default              -> m;
        };
    }

    // ── Number to Vietnamese ──────────────────────────────────────────────────
    private static final String[] U = {"","một","hai","ba","bốn","năm","sáu","bảy","tám","chín"};
    private static final String[] T = {"","mười","hai mươi","ba mươi","bốn mươi",
            "năm mươi","sáu mươi","bảy mươi","tám mươi","chín mươi"};

    private static String r3(int n) {
        int h = n/100, t = (n%100)/10, u = n%10;
        StringBuilder s = new StringBuilder();
        if (h > 0) s.append(U[h]).append(" trăm");
        if (t > 0) { if (!s.isEmpty()) s.append(" "); s.append(T[t]); }
        else if (h > 0 && u > 0) s.append(" linh");
        if (u > 0) {
            if (!s.isEmpty()) s.append(" ");
            s.append(t == 1 && u == 5 ? "lăm" : (t > 1 && u == 1 ? "mốt" : U[u]));
        }
        return s.toString().trim();
    }

    static String numberToVietnamese(long n) {
        if (n == 0) return "Không đồng";
        String[] g = {""," nghìn"," triệu"," tỷ"};
        long[] p = new long[4];
        long tmp = n;
        for (int i = 0; i < 4; i++) { p[i] = tmp % 1000; tmp /= 1000; }
        StringBuilder s = new StringBuilder();
        for (int i = 3; i >= 0; i--) {
            if (p[i] == 0) continue;
            if (!s.isEmpty()) s.append(" ");
            s.append(r3((int) p[i])).append(g[i]);
        }
        String r = s.toString().trim();
        return r.isEmpty() ? "Không đồng"
                : Character.toUpperCase(r.charAt(0)) + r.substring(1) + " đồng";
    }

    // ── Font & Logo ───────────────────────────────────────────────────────────
    private String buildFontFaceCSS() {
        String regular = loadFontBase64("fonts/Roboto-Regular.ttf");
        String bold    = loadFontBase64("fonts/Roboto-Bold.ttf");
        if (regular == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("@font-face { font-family: 'Roboto'; font-weight: 400; ")
                .append("src: url('data:font/truetype;base64,").append(regular)
                .append("') format('truetype'); }\n");
        if (bold != null) {
            sb.append("@font-face { font-family: 'Roboto'; font-weight: 700; ")
                    .append("src: url('data:font/truetype;base64,").append(bold)
                    .append("') format('truetype'); }\n");
        }
        return sb.toString();
    }

    private String loadFontBase64(String resourcePath) {
        try (InputStream is = InvoicePdf.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return java.util.Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) { return null; }
    }

    private String loadLogoBase64() {
        try (InputStream is = InvoicePdf.class.getClassLoader().getResourceAsStream("logo.png")) {
            if (is == null) return null;
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) { return null; }
    }

    private static String wrapPromoText(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        StringBuilder result = new StringBuilder();
        int current = 0;
        for (String word : text.split(" ")) {
            if (current + word.length() > maxLen) { result.append("<br/>"); current = 0; }
            else if (current > 0) { result.append(" "); current++; }
            result.append(word);
            current += word.length();
        }
        return result.toString();
    }
}