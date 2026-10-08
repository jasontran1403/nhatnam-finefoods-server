package com.nhatnam.server.utils;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.layout.font.FontProvider;
import com.nhatnam.server.dto.WarehouseDTO.ReceiptItemResponse;
import com.nhatnam.server.dto.WarehouseDTO.ReceiptResponse;
import com.nhatnam.server.entity.WarehouseReceipt.ReceiptType;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * BÁO CÁO PHIẾU KHO (PDF) — in chi tiết một phiếu Nhập / Xuất / Điều chỉnh / Chuyển kho.
 *
 * <p>Khác với {@link TransportSlipPdf} (chỉ dùng cho TRANSFER_OUT và in theo mẫu giấy
 * "Giấy thông tin nguồn gốc động vật"), file này là báo cáo NỘI BỘ dùng cho MỌI loại
 * phiếu — được gọi từ nút "Xuất báo cáo" ở trang Lịch sử của WAREHOUSE.
 *
 * <p>Bố cục cố định:
 * <ol>
 *   <li>Tiêu đề: "BÁO CÁO PHIẾU KHO" + tên loại phiếu bằng tiếng Việt.</li>
 *   <li>Bảng thông tin: mã phiếu, loại, kho, kho đối tác (nếu chuyển), người tạo,
 *       thời gian, tham chiếu / lý do, ghi chú.</li>
 *   <li>Bảng chi tiết dòng hàng: nguyên liệu, đơn vị, SL, tồn trước, tồn sau.
 *       Với ADJUST hiện thêm cột chênh lệch + kết quả kiểm.</li>
 *   <li>Chữ ký người lập / thủ kho (để trống, người dùng ký tay khi in).</li>
 * </ol>
 *
 * <p><b>CẢNH BÁO khi sửa template HTML:</b> chuỗi template đi qua {@link String#format},
 * nên MỌI dấu phần trăm trong đó phải viết nhân đôi ({@code %%}), kể cả trong CSS. Sai
 * chỗ này sinh {@code FormatFlagsConversionMismatchException} lúc chạy — không phải lúc
 * biên dịch. Giống pattern trong {@link TransportSlipPdf}.
 */
@Service
@Log4j2
public class ReceiptReportPdf {

    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final ZoneId VN_TZ = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Thông tin cố định trên đầu báo cáo. */
    private static final String OWNER_NAME =
            "CÔNG TY TNHH SẢN XUẤT THỰC PHẨM THƯƠNG MẠI DỊCH VỤ NHẤT NAM";
    private static final String OWNER_ADDRESS =
            "16/11 Trương Văn Thành, Phường Hiệp Phú, Thành phố Thủ Đức, TP. Hồ Chí Minh";

    /** Số dòng tối thiểu của bảng — kẻ thêm dòng trống để phiếu trông cân đối. */
    private static final int MIN_TABLE_ROWS = 8;

    // ══════════════════════════════════════════════════════════════════════════

    public byte[] generate(ReceiptResponse data) throws Exception {
        String html = buildHtml(data);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HtmlConverter.convertToPdf(
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                    out,
                    buildConverterProperties());
            return out.toByteArray();
        }
    }

    /**
     * Nạp font Roboto trực tiếp vào FontProvider — html2pdf KHÔNG đọc được @font-face
     * base64 trong CSS, không có bước này thì tiếng Việt có dấu bị mất/hiển thị sai.
     * Cùng cách với {@link TransportSlipPdf#buildConverterProperties()}.
     */
    private ConverterProperties buildConverterProperties() {
        FontProvider fontProvider = new FontProvider();
        for (String path : new String[]{"fonts/Roboto-Regular.ttf", "fonts/Roboto-Bold.ttf"}) {
            try (InputStream is = ReceiptReportPdf.class.getClassLoader().getResourceAsStream(path)) {
                if (is == null) {
                    log.warn("Không tìm thấy font resource: {}", path);
                    continue;
                }
                fontProvider.addFont(FontProgramFactory.createFont(is.readAllBytes()));
            } catch (Exception e) {
                log.warn("Lỗi khi nạp font {}: {}", path, e.getMessage());
            }
        }
        ConverterProperties props = new ConverterProperties();
        props.setFontProvider(fontProvider);
        return props;
    }

    // ── Dựng HTML ─────────────────────────────────────────────────────────────

    private String buildHtml(ReceiptResponse r) {
        boolean isAdjust   = r.getReceiptType() == ReceiptType.ADJUST;
        boolean isTransfer = r.getReceiptType() == ReceiptType.TRANSFER_IN
                          || r.getReceiptType() == ReceiptType.TRANSFER_OUT;

        // ── Bảng chi tiết ─────────────────────────────────────────────────────
        List<ReceiptItemResponse> items = r.getItems() == null ? List.of() : r.getItems();

        // Cột phụ thuộc loại phiếu: ADJUST hiện SL kiểm tra + chênh lệch;
        // các loại còn lại hiện SL + tồn trước + tồn sau.
        String headerCols;
        StringBuilder rows = new StringBuilder();
        int colCount;

        if (isAdjust) {
            // LƯU Ý: chuỗi này được truyền dưới dạng ARGUMENT vào String.format bên dưới
            // (qua %s), KHÔNG phải là format string — nên KHÔNG được escape "%" thành "%%".
            // Trước đây viết "%%" ở đây làm CSS output ra "width:6%%;" → parser CSS của
            // html2pdf tách sai và ném NumberFormatException: For input string: "6%".
            headerCols = ""
                    + "<th style=\"width:6%;\">STT</th>"
                    + "<th style=\"width:34%;\">Nguyên liệu</th>"
                    + "<th style=\"width:10%;\">ĐVT</th>"
                    + "<th style=\"width:12%;\">Tồn sổ</th>"
                    + "<th style=\"width:12%;\">SL thực tế</th>"
                    + "<th style=\"width:12%;\">Chênh lệch</th>"
                    + "<th style=\"width:14%;\">Kết quả</th>";
            colCount = 7;

            int idx = 1;
            for (ReceiptItemResponse it : items) {
                rows.append("<tr>")
                        .append("<td class=\"c\">").append(idx++).append("</td>")
                        .append("<td class=\"l\">").append(esc(it.getIngredientName())).append("</td>")
                        .append("<td class=\"c\">").append(esc(it.getUnit())).append("</td>")
                        .append("<td class=\"r\">").append(qty(it.getQuantityBefore())).append("</td>")
                        .append("<td class=\"r\">").append(qty(it.getPhysicalQty())).append("</td>")
                        .append("<td class=\"r\">").append(qty(it.getDifference())).append("</td>")
                        .append("<td class=\"c\">").append(adjustResultLabel(it.getAdjustResult())).append("</td>")
                        .append("</tr>");
            }
        } else {
            headerCols = ""
                    + "<th style=\"width:6%;\">STT</th>"
                    + "<th style=\"width:44%;\">Nguyên liệu</th>"
                    + "<th style=\"width:10%;\">ĐVT</th>"
                    + "<th style=\"width:13%;\">Số lượng</th>"
                    + "<th style=\"width:13%;\">Tồn trước</th>"
                    + "<th style=\"width:14%;\">Tồn sau</th>";
            colCount = 6;

            int idx = 1;
            for (ReceiptItemResponse it : items) {
                // Phiếu XUẤT / CHUYỂN RA lưu số lượng ÂM trong DB — báo cáo hiện dương cho dễ đọc.
                BigDecimal quantityDisplay = it.getQuantity() == null
                        ? BigDecimal.ZERO
                        : it.getQuantity().abs();
                rows.append("<tr>")
                        .append("<td class=\"c\">").append(idx++).append("</td>")
                        .append("<td class=\"l\">").append(esc(it.getIngredientName())).append("</td>")
                        .append("<td class=\"c\">").append(esc(it.getUnit())).append("</td>")
                        .append("<td class=\"r\">").append(qty(quantityDisplay)).append("</td>")
                        .append("<td class=\"r\">").append(qty(it.getQuantityBefore())).append("</td>")
                        .append("<td class=\"r\">").append(qty(it.getQuantityAfter())).append("</td>")
                        .append("</tr>");
            }
        }

        // Kẻ thêm dòng trống để phiếu cân đối khi ít nguyên liệu
        for (int i = items.size(); i < MIN_TABLE_ROWS; i++) {
            rows.append("<tr>");
            for (int c = 0; c < colCount; c++) rows.append("<td>&nbsp;</td>");
            rows.append("</tr>");
        }

        // ── Metadata ──────────────────────────────────────────────────────────
        String createdAtStr = r.getCreatedAt() == null ? "—"
                : Instant.ofEpochMilli(r.getCreatedAt()).atZone(VN_TZ).format(DATETIME_FMT);
        String nowStr = Instant.now().atZone(VN_TZ).format(DATETIME_FMT);

        String warehouseCell = esc(nullSafe(r.getWarehouseName()));
        // Với phiếu chuyển kho: đổi nhãn thành "Kho nguồn → Kho đích"
        String warehouseLabel = "Kho";
        if (isTransfer && r.getPartnerWarehouseName() != null) {
            if (r.getReceiptType() == ReceiptType.TRANSFER_OUT) {
                warehouseLabel = "Chuyển từ / đến";
                warehouseCell = esc(nullSafe(r.getWarehouseName()))
                        + "  →  " + esc(r.getPartnerWarehouseName());
            } else {
                warehouseLabel = "Nhận từ / vào";
                warehouseCell = esc(r.getPartnerWarehouseName())
                        + "  →  " + esc(nullSafe(r.getWarehouseName()));
            }
        }

        // Tham chiếu / lý do — ưu tiên referenceCode, sau đó reason
        String referenceLine = "";
        if (r.getReferenceCode() != null && !r.getReferenceCode().isBlank()) {
            referenceLine = "<tr><td class=\"lbl\">Tham chiếu</td><td>" + esc(r.getReferenceCode()) + "</td></tr>";
        }
        String reasonLine = "";
        if (r.getReason() != null && !r.getReason().isBlank()) {
            reasonLine = "<tr><td class=\"lbl\">Lý do</td><td>" + esc(r.getReason()) + "</td></tr>";
        }
        String noteLine = "";
        if (r.getNote() != null && !r.getNote().isBlank()) {
            noteLine = "<tr><td class=\"lbl\">Ghi chú</td><td>" + esc(r.getNote()) + "</td></tr>";
        }

        String htmlTemplate = """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/>
                <style>
                  @page { size: A4; margin: 22mm 16mm 20mm 16mm; }
                  * { box-sizing: border-box; }
                  body { font-family: "Roboto", sans-serif; font-size: 11pt; color: #000; line-height: 1.4; }
                  .hdr { text-align: center; margin-bottom: 6px; }
                  .hdr .company { font-weight: bold; font-size: 11pt; }
                  .hdr .addr { font-size: 9pt; font-style: italic; }
                  .rule { border-top: 1px solid #000; margin: 6px auto 12px; width: 40%%; }
                  h1.title { text-align: center; font-size: 16pt; margin: 6px 0 2px 0; }
                  .subtitle { text-align: center; font-size: 10.5pt; margin-bottom: 12px; color: #444; }
                  .code-line { text-align: center; font-size: 10pt; margin-bottom: 10px; }
                  .code-line .code { font-weight: bold; font-family: "Courier New", monospace; font-size: 11pt; }
                  table.meta { width: 100%%; border-collapse: collapse; margin: 8px 0 14px; font-size: 10.5pt; }
                  table.meta td { padding: 4px 6px; vertical-align: top; }
                  table.meta td.lbl { width: 24%%; color: #444; font-weight: bold; }
                  table.grid { width: 100%%; border-collapse: collapse; margin-top: 4px; }
                  table.grid th, table.grid td {
                    border: 1px solid #000; padding: 5px 6px; font-size: 10.5pt; height: 22px;
                  }
                  table.grid th { text-align: center; font-weight: bold; background: #eee; }
                  .l { text-align: left; }
                  .r { text-align: right; }
                  .c { text-align: center; }
                  .footer { margin-top: 16px; font-size: 9.5pt; color: #666; text-align: right; font-style: italic; }
                  .sign { margin-top: 24px; width: 100%%; }
                  .sign td { vertical-align: top; text-align: center; font-size: 10pt; padding: 0 6px; }
                  .sign .role { font-weight: bold; margin-top: 4px; }
                  .sign .hint { font-style: italic; font-size: 9pt; color: #444; }
                  .sign .space { height: 60px; }
                </style></head>
                <body>

                  <div class="hdr">
                    <div class="company">%s</div>
                    <div class="addr">%s</div>
                  </div>
                  <div class="rule"></div>

                  <h1 class="title">BÁO CÁO PHIẾU KHO</h1>
                  <div class="subtitle">%s</div>
                  <div class="code-line">Mã phiếu: <span class="code">%s</span></div>

                  <table class="meta">
                    <tr><td class="lbl">%s</td><td>%s</td></tr>
                    <tr><td class="lbl">Người thao tác</td><td>%s</td></tr>
                    <tr><td class="lbl">Thời gian</td><td>%s</td></tr>
                    %s
                    %s
                    %s
                  </table>

                  <table class="grid">
                    <thead><tr>%s</tr></thead>
                    <tbody>%s</tbody>
                  </table>

                  <table class="sign">
                    <tr>
                      <td style="width:33%%;">
                        <div><i>Ngày ....... tháng ....... năm .......</i></div>
                        <div class="role">Người lập phiếu</div>
                        <div class="hint">(Ký, ghi rõ họ tên)</div>
                        <div class="space"></div>
                      </td>
                      <td style="width:34%%;">
                        <div><i>Ngày ....... tháng ....... năm .......</i></div>
                        <div class="role">Thủ kho</div>
                        <div class="hint">(Ký, ghi rõ họ tên)</div>
                        <div class="space"></div>
                      </td>
                      <td style="width:33%%;">
                        <div><i>Ngày ....... tháng ....... năm .......</i></div>
                        <div class="role">Kế toán trưởng</div>
                        <div class="hint">(Ký, ghi rõ họ tên)</div>
                        <div class="space"></div>
                      </td>
                    </tr>
                  </table>

                  <div class="footer">Xuất báo cáo lúc %s</div>

                </body></html>
                """;

        return String.format(htmlTemplate,
                esc(OWNER_NAME),
                esc(OWNER_ADDRESS),
                esc(receiptTypeLabel(r.getReceiptType())),
                esc(nullSafe(r.getReceiptCode())),
                esc(warehouseLabel), warehouseCell,
                esc(nullSafe(r.getCreatedByName())),
                createdAtStr,
                referenceLine,
                reasonLine,
                noteLine,
                headerCols,
                rows.toString(),
                nowStr
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String receiptTypeLabel(ReceiptType type) {
        if (type == null) return "";
        return switch (type) {
            case IMPORT       -> "Phiếu nhập kho";
            case EXPORT_ORDER -> "Phiếu xuất kho (bán hàng)";
            case EXPORT_OTHER -> "Phiếu xuất kho khác";
            case ADJUST       -> "Phiếu điều chỉnh tồn kho";
            case TRANSFER_OUT -> "Phiếu chuyển kho (đi)";
            case TRANSFER_IN  -> "Phiếu chuyển kho (nhận)";
        };
    }

    private String adjustResultLabel(String result) {
        if (result == null) return "—";
        return switch (result) {
            case "MATCH"   -> "Khớp";
            case "SURPLUS" -> "Thừa";
            case "SHORTAGE", "MISSING" -> "Thiếu";
            default        -> esc(result);
        };
    }

    /** Định dạng số theo chuẩn Việt Nam: {@code 1.234,56}. Bỏ số 0 thừa ở cuối. */
    private String qty(BigDecimal v) {
        if (v == null) return "";
        BigDecimal n = v.stripTrailingZeros();
        if (n.scale() < 0) n = n.setScale(0);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator('.');
        sym.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("#,##0.###", sym);
        df.setMaximumFractionDigits(Math.max(n.scale(), 0));
        return df.format(n);
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
