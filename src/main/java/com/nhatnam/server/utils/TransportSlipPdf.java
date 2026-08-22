package com.nhatnam.server.utils;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.layout.font.FontProvider;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * PHIẾU ĐI ĐƯỜNG — "GIẤY THÔNG TIN NGUỒN GỐC ĐỘNG VẬT, SẢN PHẨM ĐỘNG VẬT
 * KINH DOANH, VẬN CHUYỂN".
 *
 * <p>Dựng lại đúng bố cục bản in giấy đang dùng, đổ dữ liệu từ PHIẾU CHUYỂN KHO RA
 * (TRANSFER_OUT):
 * <ul>
 *   <li>Tiêu ngữ, tiêu đề, chủ hàng, địa chỉ xuất bán, điện thoại/fax/email: cố định.</li>
 *   <li>Bảng "Xuất bán số lượng lô": mỗi nguyên liệu chuyển đi là 1 dòng.
 *       Quy cách bao gói và Khối lượng để TRỐNG; Mục đích sử dụng ghi HSD xa nhất
 *       của các lô được chuyển.</li>
 *   <li>Tổng cộng = tổng cột Số lượng.</li>
 *   <li>Cột "Khối lượng (kg)" đã BỎ (08/2026): nó luôn trống vì hệ thống không
 *       theo dõi khối lượng tách rời số lượng — hàng tính bằng kg thì hai cột
 *       trùng nhau, hàng tính bằng hộp/bó thì không quy đổi được.
 *       Bề rộng cột đó, cộng phần cắt bớt từ "Mục đích sử dụng" (cột này chỉ
 *       chứa một dòng "HSD: dd/MM/yyyy" nên trước đây thừa nhiều), dồn hết sang
 *       cột tên sản phẩm — chỗ thực sự cần, vì tên như "Cheddar Cheese Sausage,
 *       5 x 100gm" đang bị xuống ba dòng.</li>
 *
 *   <li><b>CẢNH BÁO khi sửa template HTML:</b> chuỗi template đi qua
 *       {@link String#format}, nên MỌI dấu phần trăm trong đó phải viết nhân đôi
 *       — kể cả trong comment HTML. Viết một dấu sẽ bị đọc thành format
 *       specifier và ném FormatFlagsConversionMismatchException lúc chạy, không
 *       phải lúc biên dịch.</li>
 *   <li>Tổ chức/cá nhân nhận hàng = tên kho đích; Nơi đến = địa chỉ kho đích;
 *       Phương tiện vận chuyển = "Xe tải".</li>
 * </ul>
 */
@Service
@Log4j2
public class TransportSlipPdf {

    private static final DateTimeFormatter D_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Thông tin cố định của công ty trên đầu phiếu (giống bản in giấy). */
    private static final String OWNER_NAME =
            "CÔNG TY TNHH SẢN XUẤT THỰC PHẨM THƯƠNG MẠI DỊCH VỤ NHẤT NAM";
    private static final String OWNER_ADDRESS =
            "16/11 Trương Văn Thành, Phường Hiệp Phú, Thành phố Thủ Đức, TP. Hồ Chí Minh";
    private static final String OWNER_PHONE = "028-38479216";
    private static final String OWNER_FAX   = "028-38479215";
    private static final String OWNER_EMAIL = "info@nhatnamfinefoods.com.vn";

    /** Số dòng tối thiểu của bảng — kẻ thêm dòng trống cho giống mẫu giấy (giữ phiếu gọn 1 trang A4). */
    private static final int MIN_TABLE_ROWS = 12;

    // ── Dữ liệu đầu vào ───────────────────────────────────────────────────────

    /** Một dòng hàng trên phiếu. */
    public record SlipItem(
            String name,             // Loại ĐV, SPĐV xuất bán
            BigDecimal quantity,     // Số lượng
            String unit,             // đơn vị tính (hiển thị cạnh số lượng)
            LocalDate furthestExpiry // HSD xa nhất trong các lô chuyển đi
    ) {}

    /** Dữ liệu dựng phiếu. */
    public record SlipData(
            String receiptCode,
            Long createdAt,
            String receiverName,     // Tổ chức/cá nhân nhận hàng = tên kho đích
            String destinationAddr,  // Nơi đến (cuối cùng) = địa chỉ kho đích
            String vehicle,          // Phương tiện vận chuyển
            String issuedByName,     // Người xuất kho — KHÔNG in trên phiếu, giữ để tra cứu/log
            List<SlipItem> items
    ) {}

    // ══════════════════════════════════════════════════════════════════════════

    public byte[] generate(SlipData data) throws Exception {
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
     * Nạp font Roboto trực tiếp vào FontProvider — giống {@code InvoicePdf}.
     * html2pdf KHÔNG đọc được @font-face base64 trong CSS nên phải nạp bằng cách này,
     * nếu không tiếng Việt có dấu sẽ bị mất/hiển thị sai.
     */
    private ConverterProperties buildConverterProperties() {
        FontProvider fontProvider = new FontProvider();
        for (String path : new String[]{"fonts/Roboto-Regular.ttf", "fonts/Roboto-Bold.ttf"}) {
            try (InputStream is = TransportSlipPdf.class.getClassLoader().getResourceAsStream(path)) {
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

    private String buildHtml(SlipData d) {
        List<SlipItem> items = d.items() == null ? List.of() : d.items();

        BigDecimal total = items.stream()
                .map(i -> i.quantity() == null ? BigDecimal.ZERO : i.quantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder rows = new StringBuilder();
        for (SlipItem it : items) {
            rows.append("<tr>")
                    .append("<td class=\"l\">").append(esc(it.name())).append("</td>")
                    .append("<td></td>")                                   // Quy cách bao gói — để trống
                    .append("<td class=\"c\">").append(qty(it.quantity()))
                    .append(it.unit() == null || it.unit().isBlank()
                            ? "" : " " + esc(it.unit()))
                    .append("</td>")
                    .append("<td class=\"l\">").append(expiryNote(it.furthestExpiry())).append("</td>")
                    .append("</tr>");
        }
        // Kẻ thêm dòng trống cho giống mẫu giấy
        for (int i = items.size(); i < MIN_TABLE_ROWS; i++) {
            rows.append("<tr><td>&nbsp;</td><td></td><td></td><td></td></tr>");
        }

        LocalDate now = d.createdAt() != null
                ? java.time.Instant.ofEpochMilli(d.createdAt())
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
                : LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

        String htmlTemplate = """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/>
                <style>
                  /* GHI CHÚ BỐ CỤC: phiếu phải gói gọn trong ĐÚNG 1 TRANG A4.
                     Phần đầu (tiêu ngữ, tiêu đề, thông tin chủ hàng) và bảng hàng giữ
                     cỡ chữ dễ đọc; các phần phụ sau bảng (cam kết ATTP, dòng tiêm phòng,
                     nơi đến, khối chữ ký) nhỏ hơn một chút để không tràn sang trang 2.
                     Số dòng kẻ của bảng + chiều cao .space được canh sao cho khối chữ ký
                     rơi xuống SÁT ĐÁY trang, không để khoảng trắng lớn ở cuối phiếu. */
                  @page { size: A4; margin: 14mm 15mm 11mm 15mm; }
                  * { font-family: Roboto, sans-serif; }
                  body { font-size: 10.5pt; color: #000; line-height: 1.4; }
                  .c { text-align: center; }
                  .l { text-align: left; }
                  .b { font-weight: bold; }
                  .nation { text-align: center; margin-bottom: 2px; }
                  .nation .n1 { font-size: 11pt; font-weight: bold; }
                  .nation .n2 { font-size: 10.5pt; font-weight: bold; }
                  .rule { width: 150px; border-bottom: 1px solid #000; margin: 3px auto 8px auto; }
                  .title { text-align: center; font-weight: bold; font-size: 12pt; line-height: 1.3; }
                  .meta { margin-top: 8px; }
                  .meta p { margin: 2px 0; }
                  /* Căn chỉnh dòng thông tin liên lạc - fullwidth và cách đều */
                  .meta .contact-line { 
                    display: flex; 
                    justify-content: space-between; 
                    width: 100%%;
                    margin: 2px 0;
                  }
                  .meta .contact-line span {
                    display: inline-block;
                  }
                  table.grid { width: 100%%; border-collapse: collapse; margin-top: 5px; }
                  table.grid th, table.grid td {
                    border: 1px solid #000; padding: 3px 5px; font-size: 10.5pt; height: 22px;
                  }
                  table.grid th { text-align: center; font-weight: normal; }
                  /* ── Phần phụ sau bảng: cỡ chữ nhỏ hơn ── */
                  .sec { font-size: 9.5pt; line-height: 1.45; }
                  .sec p { margin: 2px 0; }
                  /* Khối thú y: giãn dòng thoáng hơn, thụt lề các dòng tiêm phòng */
                  .sec-vet { margin-top: 7px; }
                  .sec-vet .tick { margin: 4px 0; }
                  .sec-vet .lead { margin: 5px 0 3px 0; }
                  
                  .sec-vet .dots .left-part {
                    flex: 0 0 auto;
                  }
                  .sec-vet .dots .right-part {
                    flex: 0 0 auto;
                  }
                  /* Khối nơi nhận/nơi đến/phương tiện: nhỏ hơn khối trên ~5%% */
                  .sec-dest { margin-top: 7px; font-size: 9pt; }
                  .sec-dest p { margin: 2px 0; }
                  .sec-dest .oath { margin-top: 4px; }
                  /* Ô tick: Roboto không có glyph U+2610 nên vẽ bằng viền */
                  .box { display: inline-block; width: 8px; height: 8px; border: 1px solid #000; margin-right: 2px; }
                  /* Khối chữ ký: .space được kéo cao để đẩy phần ký xuống SÁT ĐÁY trang,
                     tránh khoảng trắng thừa ở cuối phiếu. */
                  .sign { margin-top: 6px; width: 100%%; }
                  .sign td { vertical-align: top; font-size: 9pt; }
                  .sign .role { font-weight: bold; text-align: center; }
                  .sign .hint { font-style: italic; font-size: 8.5pt; text-align: center; }
                  .sign .space { height: 44px; }
                </style></head>
                <body>

                  <div class="nation">
                    <div class="n1">CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</div>
                    <div class="n2">Độc lập – Tự Do- Hạnh Phúc</div>
                  </div>
                  <div class="rule"></div>

                  <div class="title">
                    GIẤY THÔNG TIN NGUỒN GỐC ĐỘNG VẬT<br/>
                    SẢN PHẨM ĐỘNG VẬT KINH DOANH, VẬN CHUYỂN
                  </div>

                  <div class="meta">
                    <p>Họ tên chủ hàng <i>(hoặc người đại diện)</i>: <span class="b">%s</span></p>
                    <p>Địa chỉ xuất bán: %s</p>
                    <div class="contact-line">
                      <span>Điện thoại: %s</span>
                      <span>Fax: %s</span>
                      <span>Email: %s</span>
                    </div>
                  </div>

                  <p style="margin:10px 0 0 0;"><span class="b">Xuất bán số lượng lô như sau:</span>
                     <span style="font-size:9.5pt;">(Phiếu chuyển kho: %s — ngày %s)</span></p>

                  <table class="grid">
                    <thead>
                      <tr>
                        <th style="width:51%%;">Loại ĐV, SPĐV<br/>Xuất bán</th>
                        <th style="width:15%%;">Quy cách<br/>bao gói</th>
                        <th style="width:14%%;">Số lượng<sup>(2)</sup></th>
                        <th style="width:20%%;">Mục đích sử dụng</th>
                      </tr>
                    </thead>
                    <tbody>
                      %s
                      <tr>
                        <td class="c b">Tổng cộng:</td>
                        <td></td>
                        <td class="c b">%s</td>
                        <td></td>
                      </tr>
                    </tbody>
                  </table>

                  <!-- Khối thú y: các ô tick -->
                  <div class="sec sec-vet">
                    <p class="tick">*Cơ sở xuất phát động vật, sản phẩm động vật: <span class="box"></span> An toàn dịch bệnh; <span class="box"></span> Viet GAP</p>
                    <p class="tick"><span class="box"></span> Đủ điều kiện VSTY hoặc ATTP; <span class="box"></span> Có cam kết ATTP</p>
                    <p class="lead">Số động vật trên đã được tiêm phòng vắc xin với các bệnh sau ( loại vắc xin, nơi sản xuất):</p>
                    <p class="dots"><span class="left-part">1/ ....................................................................................................</span><span class="right-part">tiêm phòng ngày ................................/......................</span></p>
                    <p class="dots"><span class="left-part">2/ ....................................................................................................</span><span class="right-part">tiêm phòng ngày ................................/......................</span></p>
                    <p class="dots"><span class="left-part">3/ ....................................................................................................</span><span class="right-part">tiêm phòng ngày ................................/......................</span></p>
                  </div>

                  <!-- Khối nơi nhận / nơi đến / phương tiện — cỡ chữ nhỏ hơn ~5%% -->
                  <div class="sec sec-dest">
                    <p>Tổ chức/ cá nhân nhận lô hàng: <span class="b">%s</span></p>
                    <p>Nơi đến <i>(cuối cùng)</i>: %s</p>
                    <p>Phương tiện vận chuyển: %s</p>
                    <p class="oath">Tôi xin cam đoan các thông tin trên đây hoàn toàn đúng sự thật, nếu có sai sót tôi sẽ hoàn toàn chịu trách
                       nhiệm trước pháp luật.</p>
                  </div>

                  <table class="sign">
                    <tr>
                      <td style="width:45%%;"></td>
                      <td style="width:55%%;">
                        <div class="c"><i>Ngày ....... tháng ....... năm %s</i></div>
                        <div class="role">TỔ CHỨC/ CÁ NHÂN XUẤT BÁN</div>
                        <div class="hint">(Ký, đóng dấu, ghi rõ họ tên)</div>
                        <div class="space"></div>
                        <div class="line"></div>
                      </td>
                    </tr>
                  </table>

                </body></html>
                """;

        return String.format(htmlTemplate,
                esc(OWNER_NAME), esc(OWNER_ADDRESS),
                OWNER_PHONE, OWNER_FAX, OWNER_EMAIL,
                esc(d.receiptCode()), now.format(D_SLASH),
                rows.toString(), qty(total),
                esc(d.receiverName()), esc(d.destinationAddr()), esc(d.vehicle()),
                now.getYear()
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Ghi chú HSD cho cột "Mục đích sử dụng" — hạn xa nhất trong các lô chuyển đi. */
    private String expiryNote(LocalDate expiry) {
        return expiry == null ? "" : "HSD: " + expiry.format(D_SLASH);
    }

    /**
     * Định dạng số lượng theo chuẩn VIỆT NAM: dấu chấm ngăn hàng nghìn, dấu phẩy
     * ngăn phần thập phân, và bỏ các số 0 thừa ở cuối.
     *
     * <pre>
     *   34034.135  →  34.034,135
     *   100.000    →  100
     *   2.500      →  2,5
     * </pre>
     */
    private String qty(BigDecimal v) {
        if (v == null) return "";
        BigDecimal n = v.abs().stripTrailingZeros();
        if (n.scale() < 0) n = n.setScale(0);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator('.');   // ngăn hàng nghìn
        sym.setDecimalSeparator(',');    // ngăn phần thập phân

        // '#' ở phần thập phân = chỉ hiện khi có giá trị (không đệm 0 thừa)
        DecimalFormat df = new DecimalFormat("#,##0.###", sym);
        df.setMaximumFractionDigits(Math.max(n.scale(), 0));
        return df.format(n);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}