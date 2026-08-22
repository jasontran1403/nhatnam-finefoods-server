package com.nhatnam.server.service;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.font.FontProvider;
import com.itextpdf.layout.properties.TextAlignment;
import com.nhatnam.server.entity.Category;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BÁO CÁO SẢN PHẨM THEO ĐƠN HÀNG — xuất PDF (khổ A4 dọc) để in.
 *
 * <p>Mỗi dòng của bảng = 1 sản phẩm trong 1 đơn hàng. Một đơn có N sản phẩm sẽ
 * sinh ra N dòng, các cột thuộc về đơn (khách hàng, số phiếu, ngày) được lặp lại
 * trên từng dòng cho dễ đọc / dễ lọc khi in ra giấy.</p>
 *
 * <p><b>Lọc theo danh mục:</b> nếu {@code categoryIds} rỗng/null — hoặc chọn ĐỦ
 * tất cả danh mục đang bật — thì KHÔNG lọc, xuất toàn bộ sản phẩm. Ngược lại chỉ
 * giữ lại những dòng sản phẩm thuộc các danh mục được chọn; đơn hàng vẫn xuất
 * hiện miễn là còn ít nhất 1 sản phẩm khớp danh mục.</p>
 *
 * <p>Định dạng (letterhead + khối thông tin đầu file + bảng full-width) bám theo
 * mẫu "Phiếu đặt hàng" / báo cáo đơn hàng hiện có để in ra đồng bộ.</p>
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class OrderProductReportPdfService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Định dạng cột "Ngày" theo đúng yêu cầu: hh:mm dd/MM/yyyy */
    private static final DateTimeFormatter ROW_DT_FMT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final DateTimeFormatter EXPORT_DT_FMT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Logo dự phòng khi không đọc được resources/logo.png (giống InvoicePdf). */
    private static final String LOGO_FALLBACK_URL = "https://iili.io/3vdqwMl.md.png";

    /** Chỉ 4 trạng thái này được đưa vào báo cáo sản phẩm. */
    private static final Set<OrderStatus> REPORTED_STATUSES = EnumSet.of(
            OrderStatus.PREPARING,
            OrderStatus.DELIVERING,
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.COMPLETED
    );

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * @param fromMs      mốc đầu khoảng thời gian (epoch millis, đã set 00:00:00)
     * @param toMs        mốc cuối khoảng thời gian (epoch millis, đã set 23:59:59)
     * @param categoryIds danh mục cần lọc; null/rỗng = tất cả
     * @param exportedBy  tên người xuất báo cáo (in ở khối thông tin đầu file)
     */
    @Transactional(readOnly = true)
    public byte[] generatePdf(long fromMs, long toMs, Collection<Long> categoryIds, String exportedBy) throws IOException {

        // ── 1. Xác định tập sản phẩm được phép theo danh mục ────────────────
        List<Category> activeCategories = categoryRepository.findByIsActiveTrueOrderByNameAsc();

        Set<Long> selectedIds = new LinkedHashSet<>();
        if (categoryIds != null) {
            for (Long id : categoryIds) if (id != null) selectedIds.add(id);
        }

        // Chọn hết = không chọn gì = xuất tất cả
        boolean selectedAll = !selectedIds.isEmpty()
                && activeCategories.stream().map(Category::getId).allMatch(selectedIds::contains);
        boolean filterByCategory = !selectedIds.isEmpty() && !selectedAll;

        Set<Long> allowedProductIds = null;   // null = không lọc
        Set<String> allowedCategoryNames = null;
        String categoryLabel = "Tất cả danh mục";

        if (filterByCategory) {
            allowedProductIds = new HashSet<>();
            allowedCategoryNames = new HashSet<>();
            List<String> labels = new ArrayList<>();

            for (Long catId : selectedIds) {
                String catName = categoryRepository.findById(catId)
                        .map(Category::getName)
                        .orElse(null);
                labels.add(catName != null ? catName : ("#" + catId));
                if (catName != null) allowedCategoryNames.add(norm(catName));
                allowedProductIds.addAll(
                        productRepository.findIdsByCategoryIdOrName(catId, catName != null ? catName : ""));
            }
            categoryLabel = String.join(", ", labels);
        }

        // ── 2. Lấy đơn hàng trong khoảng ────────────────────────────────────
        // Chỉ báo cáo 4 trạng thái: Đang chuẩn bị / Đang giao / Đã giao (chờ TT) / Hoàn thành.
        List<Order> orders = new ArrayList<>(orderRepository.findByCreatedAtBetween(fromMs, toMs));
        orders.removeIf(o -> o.getStatus() == null || !REPORTED_STATUSES.contains(o.getStatus()));
        orders.sort((a, b) -> {
            long ca = a.getCreatedAt() == null ? Long.MAX_VALUE : a.getCreatedAt();
            long cb = b.getCreatedAt() == null ? Long.MAX_VALUE : b.getCreatedAt();
            if (ca != cb) return Long.compare(ca, cb);
            long ia = a.getId() == null ? 0L : a.getId();
            long ib = b.getId() == null ? 0L : b.getId();
            return Long.compare(ia, ib);
        });

        // ── 3. Dựng danh sách dòng ──────────────────────────────────────────
        // Gom theo ĐƠN: mỗi đơn = 1 nhóm, các cột thuộc đơn sẽ được merge (rowspan)
        List<Group> groups = new ArrayList<>();
        int itemRowCount = 0;

        for (Order o : orders) {
            if (o.getOrderItems() == null || o.getOrderItems().isEmpty()) continue;

            List<Item> items = new ArrayList<>();
            for (OrderItem it : o.getOrderItems()) {
                if (!isAllowed(it, allowedProductIds, allowedCategoryNames)) continue;
                items.add(new Item(
                        nz(it.getProductName()),
                        buildUnitHtml(it),
                        buildQuantity(it)
                ));
            }
            if (items.isEmpty()) continue;   // đơn không có SP thuộc danh mục đã chọn → bỏ

            groups.add(new Group(
                    buildCustomerName(o),
                    nz(o.getOrderCode()),
                    fmtTs(o.getCreatedAt()),
                    buildStatusHtml(o),
                    statusClass(o.getStatus()),
                    items
            ));
            itemRowCount += items.size();
        }

        // ── 4. Render HTML → PDF ────────────────────────────────────────────
        String html = buildHtml(groups, itemRowCount, fromMs, toMs, categoryLabel, exportedBy);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HtmlConverter.convertToPdf(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                out,
                buildConverterProperties());

        return addPageNumbers(out.toByteArray());
    }

    // ════════════════════════════════════════════════════════════════════════
    // LỌC DANH MỤC
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sản phẩm có thuộc danh mục được chọn không.
     * Ưu tiên khớp theo productId (bảng product hiện tại), fallback sang
     * categorySnapshot của dòng đơn hàng — vì sản phẩm có thể đã bị xoá/đổi
     * danh mục sau khi đơn được tạo.
     */
    private boolean isAllowed(OrderItem item, Set<Long> allowedProductIds, Set<String> allowedCategoryNames) {
        if (allowedProductIds == null) return true;                 // không lọc
        if (item.getProductId() != null && allowedProductIds.contains(item.getProductId())) return true;
        String snap = item.getCategorySnapshot();
        return snap != null && allowedCategoryNames != null && allowedCategoryNames.contains(norm(snap));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // ════════════════════════════════════════════════════════════════════════
    // FORMAT DỮ LIỆU DÒNG
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Nhãn trạng thái đơn (HTML đã escape sẵn).
     *
     * <p>PENDING_PAYMENT tách 2 dòng: dòng 1 "Đã giao hàng", dòng 2 là tình
     * trạng thanh toán — UNPAID → "Chưa thanh toán", PARTIAL → "Đã thanh toán
     * 1 phần". Các trạng thái khác chỉ 1 dòng.</p>
     */
    private String buildStatusHtml(Order o) {
        OrderStatus st = o.getStatus();
        if (st == null) return "";
        switch (st) {
            case PREPARING:  return "Đang chuẩn bị";
            case DELIVERING: return "Đang giao hàng";
            case COMPLETED:  return "Hoàn thành";
            case PENDING_PAYMENT: {
                String second = null;
                PaymentStatus ps = o.getPaymentStatus();
                if (ps == PaymentStatus.UNPAID)       second = "Chưa thanh toán";
                else if (ps == PaymentStatus.PARTIAL) second = "Đã thanh toán 1 phần";
                return second == null
                        ? "Đã giao hàng"
                        : "Đã giao hàng<br/><span class='st2'>" + esc(second) + "</span>";
            }
            default: return esc(st.name());
        }
    }

    /** Class CSS quyết định màu của cả dòng theo trạng thái đơn. */
    private String statusClass(OrderStatus st) {
        if (st == null) return "";
        switch (st) {
            case PREPARING:       return "st-prep";   // tím
            case DELIVERING:      return "st-deli";   // xanh dương
            case PENDING_PAYMENT: return "st-pend";   // cam đỏ
            case COMPLETED:       return "st-done";   // xanh lá
            default:              return "";
        }
    }

    /** Ưu tiên tên công ty, sau đó tên khách hàng. */
    private String buildCustomerName(Order o) {
        if (o.getCompanyName() != null && !o.getCompanyName().isBlank()) return o.getCompanyName();
        if (o.getCustomerName() != null && !o.getCustomerName().isBlank()) return o.getCustomerName();
        return "(Không rõ)";
    }

    /**
     * ĐVT — trả về HTML ĐÃ escape (không esc() lại ở nơi gọi).
     *
     * <p>Bán lẻ  → đơn vị của sản phẩm, vd "Kg", "Hộp".<br>
     * Bán theo quy cách (saleType = BOX) → hiển thị đúng quy cách như phiếu đặt
     * hàng: "Thùng" kèm dòng nhỏ "(×12 Hộp)" để người nhận biết 1 thùng gồm bao
     * nhiêu đơn vị lẻ.</p>
     */
    private String buildUnitHtml(OrderItem it) {
        String base = capitalizeWords(nz(it.getUnit()));
        boolean isBox = "BOX".equalsIgnoreCase(it.getSaleType());

        if (!isBox) return esc(base);

        Integer perBox = it.getUnitsPerBox();
        if (perBox != null && perBox > 0) {
            String inner = "×" + perBox + (base.isEmpty() ? "" : " " + base);
            return "Thùng<br/><span class='qc'>(" + esc(inner) + ")</span>";
        }
        return "Thùng";
    }

    /** Số lượng — KG giữ 2 số lẻ, còn lại làm tròn về số nguyên (giống phiếu đặt hàng). */
    private String buildQuantity(OrderItem it) {
        BigDecimal qty = it.getQuantity() == null ? BigDecimal.ZERO : it.getQuantity();
        boolean isBox = "BOX".equalsIgnoreCase(it.getSaleType());
        if (!isBox && "KG".equalsIgnoreCase(nz(it.getUnit()))) {
            return qty.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return qty.setScale(0, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String fmtTs(Long ts) {
        if (ts == null) return "";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), VN).format(ROW_DT_FMT);
    }

    private static String capitalizeWords(String s) {
        if (s == null || s.isBlank()) return "";
        String[] parts = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ════════════════════════════════════════════════════════════════════════
    // HTML
    // ════════════════════════════════════════════════════════════════════════

    private String buildHtml(List<Group> groups, int itemRowCount, long fromMs, long toMs,
                             String categoryLabel, String exportedBy) {

        String fromStr = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMs), VN).format(DATE_FMT);
        String toStr   = LocalDateTime.ofInstant(Instant.ofEpochMilli(toMs), VN).format(DATE_FMT);
        String nowStr  = LocalDateTime.now(VN).format(EXPORT_DT_FMT);

        String logo = loadLogoBase64();
        if (logo == null) logo = LOGO_FALLBACK_URL;

        StringBuilder body = new StringBuilder(itemRowCount * 240 + 4096);

        body.append("<!DOCTYPE html><html lang='vi'><head><meta charset='UTF-8'>")
                .append("<title>Bao cao san pham</title><style>")
                .append(CSS)
                .append("</style></head><body>");

        // ── Letterhead ──────────────────────────────────────────────────────
        body.append("<table class='hdr'><tr>")
                .append("<td class='hdr-l'><img src='").append(logo).append("' alt='Logo'></td>")
                .append("<td class='hdr-c'><div class='doc-title'>Báo Cáo Sản Phẩm</div>")
                .append("<div class='doc-sub'>Theo đơn hàng</div></td>")
                .append("<td class='hdr-r'>Từ ngày <b>").append(esc(fromStr)).append("</b><br/>")
                .append("Đến ngày <b>").append(esc(toStr)).append("</b></td>")
                .append("</tr></table>");

        // ── Khối thông tin đầu file ─────────────────────────────────────────
        body.append("<table class='info'>")
                .append("<tr><td class='lbl'>Danh mục:</td><td class='val'>").append(esc(categoryLabel)).append("</td>")
                .append("<td class='lbl'>Người xuất:</td><td class='val'>").append(esc(nz(exportedBy))).append("</td></tr>")
                .append("<tr><td class='lbl'>Số đơn hàng:</td><td class='val'>").append(groups.size()).append("</td>")
                .append("<td class='lbl'>Xuất lúc:</td><td class='val'>").append(esc(nowStr)).append("</td></tr>")
                .append("</table>");

        // ── Bảng chi tiết ───────────────────────────────────────────────────
        body.append("<table class='items'><thead><tr>")
                .append("<th style='width:4%'>STT</th>")
                .append("<th style='width:19%' class='tl'>Tên khách hàng / Công ty</th>")
                .append("<th style='width:12%'>Số phiếu đặt hàng</th>")
                .append("<th style='width:11%'>Ngày</th>")
                .append("<th style='width:13%'>Trạng thái</th>")
                .append("<th style='width:23%' class='tl'>Tên sản phẩm</th>")
                .append("<th style='width:9%'>ĐVT</th>")
                .append("<th style='width:9%'>Số lượng</th>")
                .append("</tr></thead><tbody>");

        if (groups.isEmpty()) {
            body.append("<tr><td colspan='8' class='empty'>")
                    .append("Không có dữ liệu trong khoảng thời gian / danh mục đã chọn.")
                    .append("</td></tr>");
        } else {
            // STT đếm theo ĐƠN (1, 2, 3...) — không dùng id của order.
            // Đơn có nhiều sản phẩm: STT / khách hàng / số phiếu / ngày được
            // merge (rowspan) trải qua tất cả các dòng sản phẩm của đơn đó.
            int stt = 1;
            for (Group g : groups) {
                int span = g.items().size();
                // Màu tô cho CẢ dòng của đơn — đặt trên <tr> để mọi <td> cùng nền
                String trCls = g.statusClass().isEmpty() ? "" : " class='" + g.statusClass() + "'";
                for (int i = 0; i < span; i++) {
                    Item it = g.items().get(i);
                    body.append("<tr").append(trCls).append(">");
                    if (i == 0) {
                        String rs = span > 1 ? " rowspan='" + span + "'" : "";
                        body.append("<td class='tc grp'").append(rs).append(">").append(stt).append("</td>")
                                .append("<td class='tl grp'").append(rs).append(">").append(esc(g.customerName())).append("</td>")
                                .append("<td class='tc grp'").append(rs).append(">").append(esc(g.orderCode())).append("</td>")
                                .append("<td class='tc grp'").append(rs).append(">").append(esc(g.date())).append("</td>")
                                .append("<td class='tc grp st'").append(rs).append(">").append(g.statusHtml()).append("</td>");
                    }
                    body.append("<td class='tl'>").append(esc(it.productName())).append("</td>")
                            .append("<td class='tc'>").append(it.unitHtml()).append("</td>")
                            .append("<td class='tr b'>").append(esc(it.quantity())).append("</td>")
                            .append("</tr>");
                }
                stt++;
            }
        }

        body.append("</tbody></table>");

        // ── Dòng tổng kết cuối báo cáo ──────────────────────────────────────
        body.append("<div class='foot'>Tổng cộng: <b>").append(groups.size())
                .append("</b> đơn hàng / <b>").append(itemRowCount).append("</b> dòng sản phẩm.</div>");

        body.append("</body></html>");
        return body.toString();
    }

    /**
     * CSS — A4 dọc, chừa lề trái/phải để khi in không bị sát mép giấy;
     * bảng luôn full-width trong vùng in.
     */
    private static final String CSS = """
            * { box-sizing: border-box; margin: 0; padding: 0; }
            @page { size: A4 portrait; margin: 12mm 10mm 14mm 10mm; }

            body {
                font-family: 'Roboto', Arial, sans-serif;
                font-size: 11px;
                color: #111;
                background: #fff;
            }

            /* ── LETTERHEAD ── */
            table.hdr { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
            table.hdr td { vertical-align: middle; padding: 0; }
            .hdr-l { width: 24%; }
            .hdr-l img { height: 44px; width: auto; display: block; }
            .hdr-c { width: 52%; text-align: center; }
            .hdr-r { width: 24%; text-align: right; font-size: 11px; line-height: 1.5; }
            .doc-title {
                font-size: 24px; font-weight: 900;
                text-transform: uppercase; letter-spacing: 2px;
            }
            .doc-sub { font-size: 11px; color: #555; margin-top: 2px; letter-spacing: 1px; }

            /* ── THÔNG TIN ĐẦU FILE ── */
            table.info {
                width: 100%; border-collapse: collapse;
                margin-bottom: 8px; font-size: 11px;
            }
            table.info td { padding: 2px 6px 2px 0; vertical-align: top; }
            table.info td.lbl { font-weight: 700; white-space: nowrap; width: 1%; }
            table.info td.val { border-bottom: 1px dotted #777; padding-right: 14px; }

            /* ── BẢNG CHI TIẾT — full width ── */
            table.items {
                width: 100%;
                border-collapse: collapse;
                font-size: 11px;
                border: 1px solid #222;
                table-layout: fixed;
            }
            table.items th {
                border: 1px solid #222;
                background: #ECECEC;
                padding: 6px 4px;
                font-weight: 700;
                text-align: center;
                font-size: 11px;
            }
            table.items td {
                border: 1px solid #444;
                padding: 4px;
                text-align: center;
                word-wrap: break-word;
                word-break: break-word;
            }
            /* Ô đã merge (rowspan) — căn giữa theo chiều dọc cho cân đối */
            table.items td.grp { vertical-align: middle; }
            /* Dòng quy cách nhỏ dưới ĐVT, vd (×12 Hộp) */
            table.items span.qc { font-size: 9px; line-height: 1.2; color: #444; }

            /* ── Ô TRẠNG THÁI ── */
            table.items td.st { font-weight: 700; font-size: 10px; line-height: 1.25; }
            /* Dòng 2 của PENDING_PAYMENT (tình trạng thanh toán) */
            table.items span.st2 { font-weight: 400; font-size: 9px; }

            /* ── MÀU CẢ DÒNG THEO TRẠNG THÁI ĐƠN ──
               Nền nhạt để in không tốn mực và chữ vẫn rõ; chữ ô trạng thái đậm màu. */
            tr.st-prep td { background: #F1ECFE; }   /* Đang chuẩn bị  — tím       */
            tr.st-deli td { background: #E3EDFD; }   /* Đang giao hàng — xanh dương */
            tr.st-pend td { background: #FDE9DE; }   /* Đã giao hàng   — cam đỏ    */
            tr.st-done td { background: #E1F7EC; }   /* Hoàn thành     — xanh lá   */

            tr.st-prep td.st { color: #5B21B6; }
            tr.st-deli td.st { color: #1D4ED8; }
            tr.st-pend td.st { color: #C2410C; }
            tr.st-done td.st { color: #047857; }

            table.items td.tl, table.items th.tl { text-align: left; padding-left: 5px; }
            table.items td.tc { text-align: center; }
            table.items td.tr { text-align: right; padding-right: 5px; }
            table.items td.b  { font-weight: 700; }
            table.items td.empty {
                text-align: center; padding: 18px 4px; font-style: italic; color: #666;
            }

            /* ── FOOTER ── */
            .foot { margin-top: 8px; font-size: 11px; text-align: right; }
            """;

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ════════════════════════════════════════════════════════════════════════
    // PDF PLUMBING
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Nạp font Roboto trực tiếp từ resources (html2pdf không decode được
     * @font-face base64 dài) — bắt buộc để chữ tiếng Việt không bị mất dấu.
     */
    private ConverterProperties buildConverterProperties() {
        FontProvider fontProvider = new FontProvider();
        for (String path : new String[]{"fonts/Roboto-Regular.ttf", "fonts/Roboto-Bold.ttf"}) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    log.warn("[ProductReport] Không tìm thấy font resource: {}", path);
                    continue;
                }
                fontProvider.addFont(FontProgramFactory.createFont(is.readAllBytes()));
            } catch (Exception e) {
                log.warn("[ProductReport] Lỗi khi nạp font {}: {}", path, e.getMessage());
            }
        }
        ConverterProperties props = new ConverterProperties();
        props.setFontProvider(fontProvider);
        return props;
    }

    private String loadLogoBase64() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("logo.png")) {
            if (is == null) return null;
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    /** Đánh số trang "Trang x / y" ở giữa chân trang. */
    private byte[] addPageNumbers(byte[] pdf) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PdfDocument doc = new PdfDocument(
                    new PdfReader(new ByteArrayInputStream(pdf)), new PdfWriter(out))) {

                PdfFont font = loadPdfFont();
                int total = doc.getNumberOfPages();
                for (int p = 1; p <= total; p++) {
                    PdfPage page = doc.getPage(p);
                    Rectangle size = page.getPageSize();
                    Canvas cv = new Canvas(new PdfCanvas(page), size);
                    cv.showTextAligned(
                            new Paragraph("Trang " + p + " / " + total).setFont(font).setFontSize(8f),
                            (size.getLeft() + size.getRight()) / 2f, 18f, TextAlignment.CENTER);
                    cv.close();
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("[ProductReport] Không đánh số trang được: {}", e.getMessage());
            return pdf;   // vẫn trả PDF gốc, không làm hỏng chức năng
        }
    }

    private PdfFont loadPdfFont() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("fonts/Roboto-Regular.ttf")) {
            if (is != null) {
                return PdfFontFactory.createFont(is.readAllBytes(), PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
            }
        } catch (Exception ignored) { /* fallback bên dưới */ }
        return PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
    }

    // ════════════════════════════════════════════════════════════════════════

    /** 1 dòng sản phẩm. {@code unitHtml} đã được escape sẵn (có thể chứa thẻ HTML). */
    private record Item(String productName, String unitHtml, String quantity) {}

    /** 1 đơn hàng = 1 nhóm dòng; các cột thuộc đơn được merge trên nhóm này. */
    private record Group(String customerName, String orderCode, String date,
                         String statusHtml, String statusClass, List<Item> items) {}
}