package com.nhatnam.server.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.*;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.Voucher;
import com.nhatnam.server.utils.AnniversaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * XUẤT PHIẾU VOUCHER RA PDF ĐỂ GỬI IN.
 *
 * <p>Khổ A5 NGANG (595 × 420pt) — cỡ phiếu quà tặng cầm tay, in 2 phiếu vừa một tờ A4.
 * Bố cục: viền vàng kép, dải màu tiêu đề, mệnh giá cỡ lớn ở giữa, thông tin khách +
 * điều kiện áp dụng ở hai cột dưới, mã voucher đặt ở góc để nhân viên thu ngân đọc nhanh.
 *
 * <p>Màu thay đổi theo dịp tặng (sinh nhật hồng-đỏ, khai trương xanh-vàng, còn lại vàng
 * thương hiệu) để người nhận nhìn là biết ngay phiếu gì mà không cần đọc chữ.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class VoucherPdfService {

    // ── Khổ giấy & lề ────────────────────────────────────────────────────────
    private static final PageSize VOUCHER_SIZE = new PageSize(595f, 420f);
    private static final float MARGIN = 28f;

    // ── Bảng màu ─────────────────────────────────────────────────────────────
    private static final DeviceRgb GOLD        = new DeviceRgb(201, 168,  76);
    private static final DeviceRgb GOLD_DEEP   = new DeviceRgb(163, 132,  44);
    private static final DeviceRgb NAVY        = new DeviceRgb( 26,  39,  68);
    private static final DeviceRgb INK_SOFT    = new DeviceRgb( 90,  95, 110);
    private static final DeviceRgb PAPER_TINT  = new DeviceRgb(252, 249, 240);
    private static final DeviceRgb ROSE        = new DeviceRgb(190,  60,  95);
    private static final DeviceRgb ROSE_DEEP   = new DeviceRgb(146,  38,  70);
    private static final DeviceRgb EMERALD     = new DeviceRgb( 27, 122,  95);
    private static final DeviceRgb EMERALD_DP  = new DeviceRgb( 18,  92,  71);

    /** Mực dấu: đỏ cho voucher còn giá trị, xanh lá cho voucher đã đóng. */
    private static final DeviceRgb STAMP_RED   = new DeviceRgb(198,  40,  40);
    private static final DeviceRgb STAMP_GREEN = new DeviceRgb( 27, 122,  95);

    private static final String COMPANY_NAME = "CÔNG TY TNHH SẢN XUẤT THỰC PHẨM TMDV NHẤT NAM";
    private static final String COMPANY_INFO =
            "(028) 38479216 - 217 - 218 - 219  •  info@nhatnamfinefoods.com.vn  •  www.nhatnamfinefoods.com";

    private static final DateTimeFormatter D_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] generate(Voucher v) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(VOUCHER_SIZE);
            Document doc = new Document(pdfDoc);
            doc.setMargins(MARGIN, MARGIN, MARGIN, MARGIN);

            PdfFont regular = loadFont("fonts/DejaVuSans.ttf");
            PdfFont bold    = loadFont("fonts/DejaVuSans-Bold.ttf");
            PdfFont italic  = loadItalicFont(regular);

            Theme theme = Theme.of(v.getReason());

            // Trang phải tồn tại trước khi vẽ nền, nếu không getPage(1) sẽ ném lỗi.
            pdfDoc.addNewPage();
            drawBackground(pdfDoc.getPage(1), theme);

            doc.add(buildHeader(v, theme, regular, bold));
            doc.add(buildAmountBlock(v, theme, regular, bold, italic));
            doc.add(buildDetailBlock(v, theme, regular, bold));
            doc.add(buildFooter(v, regular, bold, italic));
            drawStamp(pdfDoc.getPage(1), v, bold, regular);

            doc.close();
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[Voucher] Lỗi tạo PDF voucher #{}", v.getId(), e);
            throw new RuntimeException("Không tạo được phiếu voucher: " + e.getMessage(), e);
        }
    }

    /** In nhiều voucher vào một file — mỗi voucher một trang. */
    public byte[] generateBatch(List<Voucher> vouchers) {
        if (vouchers == null || vouchers.isEmpty())
            throw new IllegalArgumentException("Không có voucher nào để in");

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(VOUCHER_SIZE);
            Document doc = new Document(pdfDoc);
            doc.setMargins(MARGIN, MARGIN, MARGIN, MARGIN);

            PdfFont regular = loadFont("fonts/DejaVuSans.ttf");
            PdfFont bold    = loadFont("fonts/DejaVuSans-Bold.ttf");
            PdfFont italic  = loadItalicFont(regular);

            for (int i = 0; i < vouchers.size(); i++) {
                Voucher v = vouchers.get(i);
                Theme theme = Theme.of(v.getReason());

                if (i > 0) doc.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
                // Trang thứ i+1 đã tồn tại sau AreaBreak (trang 1 do Document tự tạo).
                drawBackground(pdfDoc.getPage(i + 1), theme);

                doc.add(buildHeader(v, theme, regular, bold));
                doc.add(buildAmountBlock(v, theme, regular, bold, italic));
                doc.add(buildDetailBlock(v, theme, regular, bold));
                doc.add(buildFooter(v, regular, bold, italic));
                drawStamp(pdfDoc.getPage(i + 1), v, bold, regular);
            }

            doc.close();
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[Voucher] Lỗi tạo PDF hàng loạt", e);
            throw new RuntimeException("Không tạo được phiếu voucher: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NỀN & VIỀN
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Vẽ nền kem, viền kép và hai "góc trang trí" trực tiếp lên canvas.
     *
     * <p>Dùng canvas thay vì element của layout vì viền phải chạy sát mép giấy,
     * còn layout thì luôn bị chặn bởi lề trang.
     */
    private void drawBackground(PdfPage page, Theme theme) {
        Rectangle sz = page.getPageSize();
        PdfCanvas canvas = new PdfCanvas(page);

        // Nền kem toàn trang
        canvas.saveState()
                .setFillColor(PAPER_TINT)
                .rectangle(0, 0, sz.getWidth(), sz.getHeight())
                .fill()
                .restoreState();

        // Dải màu đầu trang
        canvas.saveState()
                .setFillColor(theme.primary)
                .rectangle(0, sz.getHeight() - 10f, sz.getWidth(), 10f)
                .fill()
                .restoreState();

        // Viền ngoài đậm
        canvas.saveState()
                .setStrokeColor(theme.primary)
                .setLineWidth(2.2f)
                .rectangle(14f, 14f, sz.getWidth() - 28f, sz.getHeight() - 28f)
                .stroke()
                .restoreState();

        // Viền trong mảnh — tạo cảm giác "phiếu quà tặng" cổ điển
        canvas.saveState()
                .setStrokeColor(theme.accent)
                .setLineWidth(0.6f)
                .rectangle(20f, 20f, sz.getWidth() - 40f, sz.getHeight() - 40f)
                .stroke()
                .restoreState();

        // Bốn góc trang trí
        float c = 26f;
        float[][] corners = {
                {20f, 20f, 1, 1},
                {sz.getWidth() - 20f, 20f, -1, 1},
                {20f, sz.getHeight() - 20f, 1, -1},
                {sz.getWidth() - 20f, sz.getHeight() - 20f, -1, -1},
        };
        canvas.saveState().setStrokeColor(theme.primary).setLineWidth(1.6f);
        for (float[] p : corners) {
            canvas.moveTo(p[0], p[1]).lineTo(p[0] + c * p[2], p[1]).stroke();
            canvas.moveTo(p[0], p[1]).lineTo(p[0], p[1] + c * p[3]).stroke();
        }
        canvas.restoreState();
    }

    // ════════════════════════════════════════════════════════════════════════
    // CÁC KHỐI NỘI DUNG
    // ════════════════════════════════════════════════════════════════════════

    private Table buildHeader(Voucher v, Theme theme, PdfFont regular, PdfFont bold) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{22f, 78f}))
                .useAllAvailableWidth()
                .setBorder(Border.NO_BORDER)
                .setMarginTop(6f);

        Cell logoCell = new Cell().setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
        byte[] logo = loadResource("logo.png");
        if (logo != null) {
            try {
                // Dùng đúng cách scale ảnh như QuotationPdfService đang chạy ổn định:
                // setAutoScale + giới hạn khung, thay vì ép cứng chiều rộng.
                Image img = new Image(ImageDataFactory.create(logo));
                img.setAutoScale(true).setMaxWidth(72f).setMaxHeight(52f);
                logoCell.add(img);
            } catch (Exception ignored) { /* thiếu logo không phải lý do để hỏng cả phiếu */ }
        }
        t.addCell(logoCell);

        Cell titleCell = new Cell().setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setTextAlignment(TextAlignment.RIGHT);

        titleCell.add(new Paragraph(COMPANY_NAME)
                .setFont(bold).setFontSize(7.5f).setFontColor(INK_SOFT)
                .setMultipliedLeading(1.1f).setMargin(0));

        titleCell.add(new Paragraph(theme.title)
                .setFont(bold).setFontSize(19f).setFontColor(theme.primary)
                .setCharacterSpacing(1.4f).setMarginTop(4f).setMarginBottom(0));

        // Chỉ in phụ đề khi người tạo tự nhập tiêu đề. Không tự sinh câu chúc theo dịp:
        // phiếu in lại cho khách mất phiếu mà vẫn ghi "Chúc mừng sinh nhật" thì lạc lõng,
        // vì lúc in lại có thể đã qua dịp từ lâu.
        if (v.getTitle() != null && !v.getTitle().isBlank()) {
            titleCell.add(new Paragraph(v.getTitle())
                    .setFont(regular).setFontSize(9f).setFontColor(NAVY)
                    .setMarginTop(2f).setMarginBottom(0));
        }

        t.addCell(titleCell);
        return t;
    }

    private Table buildAmountBlock(Voucher v, Theme theme,
                                   PdfFont regular, PdfFont bold, PdfFont italic) {
        Table t = new Table(1).useAllAvailableWidth()
                .setBorder(Border.NO_BORDER).setMarginTop(10f);

        Cell cell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderTop(new SolidBorder(theme.accent, 0.8f))
                .setBorderBottom(new SolidBorder(theme.accent, 0.8f))
                .setPaddingTop(9f).setPaddingBottom(9f)
                .setTextAlignment(TextAlignment.CENTER);

        cell.add(new Paragraph("TRỊ GIÁ")
                .setFont(regular).setFontSize(8f).setFontColor(INK_SOFT)
                .setCharacterSpacing(2.5f).setMargin(0));

        cell.add(new Paragraph(formatMoney(v.getAmount()) + " đ")
                .setFont(bold).setFontSize(34f).setFontColor(theme.primary)
                .setMarginTop(1f).setMarginBottom(1f));

        cell.add(new Paragraph(moneyInWords(v.getAmount()))
                .setFont(italic).setFontSize(8f).setFontColor(INK_SOFT)
                .setMargin(0));

        t.addCell(cell);
        return t;
    }

    private Table buildDetailBlock(Voucher v, Theme theme, PdfFont regular, PdfFont bold) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{50f, 50f}))
                .useAllAvailableWidth()
                .setBorder(Border.NO_BORDER)
                .setMarginTop(12f);

        // ── Cột trái: khách hàng ────────────────────────────────────────────
        Cell left = new Cell().setBorder(Border.NO_BORDER).setPaddingRight(12f);
        left.add(label("KHÁCH HÀNG", regular));

        Customer c = v.getCustomer();
        String name = "—";
        if (c != null) {
            name = c.getCustomerType() == Customer.CustomerType.COMPANY
                    && c.getCompanyName() != null && !c.getCompanyName().isBlank()
                    ? c.getCompanyName()
                    : (c.getName() != null ? c.getName() : "—");
        }
        left.add(new Paragraph(name)
                .setFont(bold).setFontSize(11f).setFontColor(NAVY)
                .setMultipliedLeading(1.15f).setMarginTop(1f).setMarginBottom(0));

        if (c != null) {
            String phone = c.getPhone() != null ? c.getPhone() : c.getCompanyPhone();
            if (phone != null && !phone.isBlank())
                left.add(value("ĐT: " + phone, regular));
        }

        t.addCell(left);

        // ── Cột phải: hạn dùng + điều kiện ──────────────────────────────────
        Cell right = new Cell().setBorder(Border.NO_BORDER)
                .setBorderLeft(new SolidBorder(theme.accent, 0.6f))
                .setPaddingLeft(12f);

        right.add(label("HẠN SỬ DỤNG", regular));
        right.add(new Paragraph(formatDate(v.getValidFrom()) + "  —  " + formatDate(v.getValidTo()))
                .setFont(bold).setFontSize(10f).setFontColor(NAVY)
                .setMarginTop(1f).setMarginBottom(5f));

        right.add(label("ĐIỀU KIỆN ÁP DỤNG", regular));
        right.add(new Paragraph(describeScope(v))
                .setFont(regular).setFontSize(8.5f).setFontColor(NAVY)
                .setMultipliedLeading(1.2f).setMarginTop(1f).setMarginBottom(0));

        t.addCell(right);
        return t;
    }

    private Table buildFooter(Voucher v, PdfFont regular, PdfFont bold, PdfFont italic) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{40f, 20f, 40f}))
                .useAllAvailableWidth()
                .setBorder(Border.NO_BORDER)
                .setMarginTop(14f);

        Cell left = new Cell().setBorder(Border.NO_BORDER);
        left.add(new Paragraph("MÃ VOUCHER")
                .setFont(regular).setFontSize(7f).setFontColor(INK_SOFT)
                .setCharacterSpacing(1.8f).setMargin(0));
        left.add(new Paragraph(v.getCode())
                .setFont(bold).setFontSize(14f).setFontColor(NAVY)
                .setCharacterSpacing(2f).setMarginTop(1f).setMarginBottom(0));
        left.add(new Paragraph(COMPANY_INFO)
                .setFont(regular).setFontSize(6.2f).setFontColor(INK_SOFT)
                .setMarginTop(5f).setMarginBottom(0));
        t.addCell(left);

        // ── QR để quét khi thanh toán ────────────────────────────────────────
        // Nội dung QR CHỈ là mã voucher, không phải URL hay JSON. Nhân viên quét bằng
        // màn hình thanh toán trong app, mà màn hình đó chỉ cần đúng chuỗi mã; nhét URL
        // vào sẽ khiến ai đó quét bằng camera điện thoại thường bị đẩy ra trình duyệt.
        Cell qrCell = new Cell().setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);
        try {
            BarcodeQRCode qr = new BarcodeQRCode(v.getCode());
            Image qrImage = new Image(qr.createFormXObject(NAVY, null));
            qrImage.setWidth(58f).setHeight(58f);
            qrCell.add(qrImage);
            qrCell.add(new Paragraph("Quét để thanh toán")
                    .setFont(regular).setFontSize(5.5f).setFontColor(INK_SOFT)
                    .setMarginTop(2f).setMarginBottom(0));
        } catch (Exception e) {
            // Không có QR thì vẫn nhập tay được mã bên trái — không đáng làm hỏng cả phiếu.
            log.warn("[Voucher] Không tạo được QR cho {}: {}", v.getCode(), e.getMessage());
        }
        t.addCell(qrCell);

        // Ô bên phải để TRỐNG — con dấu tròn được vẽ đè lên vùng này bằng canvas
        // (xem drawStamp). Vẽ bằng canvas thay vì element vì con dấu phải NGHIÊNG và
        // chồng một phần lên nội dung, hai việc mà layout engine không làm được.
        t.addCell(new Cell().setBorder(Border.NO_BORDER));

        return t;
    }

    // ════════════════════════════════════════════════════════════════════════
    // CON DẤU
    // ════════════════════════════════════════════════════════════════════════

    /**
     * VẼ CON DẤU MỘC TRÒN, NGHIÊNG — thay cho ô ký tên.
     *
     * <p>Trạng thái quyết định chữ và màu:
     * <ul>
     *   <li>Chưa dùng đồng nào → "ĐÃ PHÁT HÀNH", màu đỏ.</li>
     *   <li>Đã dùng một phần → "ĐÃ SỬ DỤNG MỘT PHẦN" + số dư còn lại, màu đỏ.</li>
     *   <li>Hết hạn / dùng hết → "ĐÃ HẾT HẠN" hoặc "ĐÃ SỬ DỤNG HẾT", màu xanh lá.</li>
     * </ul>
     *
     * <p><b>Mục đích của con dấu là IN LẠI phiếu cho khách bị mất.</b> Phiếu in lại phải
     * nói rõ voucher còn giá trị bao nhiêu, nếu không khách cầm bản in lại đi tiêu tiếp
     * phần đã dùng và nhân viên quầy không có cách nào biết.
     *
     * <p>Vẽ bằng canvas chứ không phải element: con dấu cần xoay nghiêng và chồng lên
     * nội dung bên dưới — layout engine của iText không làm được cả hai việc này.
     */
    private void drawStamp(PdfPage page, Voucher v, PdfFont bold, PdfFont regular) {
        StampInfo info = StampInfo.of(v, System.currentTimeMillis());

        Rectangle sz = page.getPageSize();
        float cx = sz.getWidth() - 112f;
        float cy = 92f;
        float rOuter = 48f;
        float rInner = 37f;

        PdfCanvas canvas = new PdfCanvas(page);
        canvas.saveState();

        // ── Xoay cả con dấu quanh tâm ────────────────────────────────────────
        // Toàn bộ hình vẽ nằm TRONG một hệ toạ độ đã xoay, nên vòng tròn, vòng chữ và
        // chữ giữa luôn nghiêng cùng một góc. Bản trước vẽ chữ giữa bằng layout Canvas
        // riêng — nó xoay quanh khung của chính nó nên lệch khỏi vòng tròn.
        double rot = Math.toRadians(-14);
        float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
        canvas.concatMatrix(cos, sin, -sin, cos,
                cx - cx * cos + cy * sin, cy - cx * sin - cy * cos);

        // Mực dấu thật không bao giờ đặc — để hơi trong cho giống bản in đóng dấu.
        canvas.setExtGState(new PdfExtGState().setStrokeOpacity(0.72f).setFillOpacity(0.72f));

        canvas.setStrokeColor(info.color).setLineWidth(2.4f)
                .circle(cx, cy, rOuter).stroke();
        canvas.setLineWidth(0.9f).circle(cx, cy, rInner).stroke();

        // ── Vòng chữ ─────────────────────────────────────────────────────────
        // Tên công ty phủ cung TRÊN, dấu sao phủ cung DƯỚI — cộng lại thành một vòng
        // khép kín. Bản trước để chữ tự dài bao nhiêu thì chiếm bấy nhiêu góc, nên chỉ
        // phủ khoảng 100° và nhìn như bị cụt.
        float rText = (rOuter + rInner) / 2f;
        drawArcText(canvas, STAMP_COMPANY, cx, cy, rText, bold, 5.4f, info.color,
                200f, -220f);                       // từ 200° quét ngược 220° qua đỉnh
        drawArcText(canvas, "★  ★  ★", cx, cy, rText, bold, 5.4f, info.color,
                -68f, 136f);                        // cung dưới, chữ dựng ngược lại

        // ── Chữ giữa ─────────────────────────────────────────────────────────
        // Vẽ thẳng bằng canvas và tự canh giữa theo bề rộng thật của từng dòng.
        float lineHeight = info.fontSize + 2.2f;
        String[] lines = info.label.split("\n");
        int totalLines = lines.length + (info.subLabel != null ? 1 : 0);
        float blockTop = cy + (totalLines * lineHeight) / 2f - info.fontSize;

        for (int i = 0; i < lines.length; i++)
            drawCenteredText(canvas, lines[i], cx, blockTop - i * lineHeight,
                    bold, info.fontSize, info.color);

        if (info.subLabel != null)
            drawCenteredText(canvas, info.subLabel, cx,
                    blockTop - lines.length * lineHeight, regular, 5.6f, info.color);

        canvas.restoreState();
    }

    /** Vẽ một dòng chữ canh giữa quanh trục x = {@code cx}. */
    private void drawCenteredText(PdfCanvas canvas, String text, float cx, float y,
                                  PdfFont font, float fontSize, Color color) {
        float w = font.getWidth(text, fontSize);
        canvas.saveState()
                .setFillColor(color)
                .beginText()
                .setFontAndSize(font, fontSize)
                .setTextMatrix(1, 0, 0, 1, cx - w / 2f, y)
                .showText(text)
                .endText()
                .restoreState();
    }

    /**
     * Rải từng ký tự dọc theo một cung tròn.
     *
     * <p>iText không có API vẽ chữ theo đường cong nên phải tự xoay và đặt từng ký tự.
     *
     * <p>Góc mỗi ký tự chia theo BỀ RỘNG THẬT của nó rồi <b>chuẩn hoá cho vừa đúng
     * {@code sweepDeg}</b>. Chuẩn hoá là điểm mấu chốt: nếu để chữ tự chiếm góc theo
     * kích thước thật thì chuỗi ngắn chỉ phủ một đoạn nhỏ và vòng chữ trông như bị cắt,
     * còn chuỗi dài thì chạy vòng quá và chồng lên chính nó. Chia theo bề rộng (thay vì
     * chia đều) vẫn cần thiết để "I" và "M" không chiếm cùng khoảng.
     *
     * @param startDeg góc bắt đầu (độ, 0° = hướng 3 giờ, ngược chiều kim đồng hồ)
     * @param sweepDeg góc quét; ÂM = đi theo chiều kim đồng hồ (dùng cho cung trên),
     *                 DƯƠNG = ngược chiều kim đồng hồ (dùng cho cung dưới, để chữ không bị lộn ngược)
     */
    private void drawArcText(PdfCanvas canvas, String text, float cx, float cy, float radius,
                             PdfFont font, float fontSize, Color color,
                             float startDeg, float sweepDeg) {
        if (text == null || text.isEmpty()) return;

        float totalWidth = 0f;
        for (char ch : text.toCharArray())
            totalWidth += font.getWidth(String.valueOf(ch), fontSize);
        if (totalWidth <= 0) return;

        double sweep = Math.toRadians(sweepDeg);
        double angle = Math.toRadians(startDeg);
        boolean clockwise = sweepDeg < 0;

        canvas.setFillColor(color);
        for (char ch : text.toCharArray()) {
            String c = String.valueOf(ch);
            double step = sweep * (font.getWidth(c, fontSize) / totalWidth);
            double mid = angle + step / 2;

            float x = cx + (float) (radius * Math.cos(mid));
            float y = cy + (float) (radius * Math.sin(mid));

            // Cung trên: chân chữ hướng vào tâm. Cung dưới: lật lại, nếu không chữ
            // sẽ đứng lộn ngược so với người đọc.
            double charRot = clockwise ? mid - Math.PI / 2 : mid + Math.PI / 2;

            canvas.saveState()
                    .beginText()
                    .setFontAndSize(font, fontSize)
                    .setTextMatrix((float) Math.cos(charRot), (float) Math.sin(charRot),
                            (float) -Math.sin(charRot), (float) Math.cos(charRot), x, y)
                    .showText(c)
                    .endText()
                    .restoreState();

            angle += step;
        }
    }

    /** Tên công ty rút gọn chạy vòng quanh dấu — tên đầy đủ quá dài, chữ sẽ chồng nhau. */
    private static final String STAMP_COMPANY = "CÔNG TY TNHH TP NHẤT NAM";

    /** Nội dung + màu con dấu, suy từ trạng thái voucher. */
    private record StampInfo(String label, String subLabel, Color color, float fontSize) {

        static StampInfo of(Voucher v, long now) {
            Voucher.VoucherStatus st = v.effectiveStatus(now);
            long used = v.getUsedAmount() != null ? v.getUsedAmount() : 0L;

            if (st == Voucher.VoucherStatus.EXPIRED)
                return new StampInfo("ĐÃ\nHẾT HẠN", null, STAMP_GREEN, 9.5f);
            if (st == Voucher.VoucherStatus.USED)
                return new StampInfo("ĐÃ SỬ DỤNG\nHẾT", null, STAMP_GREEN, 9f);
            if (st == Voucher.VoucherStatus.CANCELLED)
                return new StampInfo("ĐÃ\nTHU HỒI", null, STAMP_GREEN, 9.5f);

            // Còn hiệu lực: phân biệt chưa dùng và dùng dở.
            if (used > 0)
                return new StampInfo("ĐÃ SỬ DỤNG\nMỘT PHẦN",
                        "Còn " + String.format("%,d", v.remaining()).replace(',', '.') + " đ",
                        STAMP_RED, 7.5f);

            return new StampInfo("ĐÃ\nPHÁT HÀNH", null, STAMP_RED, 9f);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private Paragraph label(String text, PdfFont font) {
        return new Paragraph(text)
                .setFont(font).setFontSize(7f).setFontColor(INK_SOFT)
                .setCharacterSpacing(1.6f).setMargin(0);
    }

    private Paragraph value(String text, PdfFont font) {
        return new Paragraph(text)
                .setFont(font).setFontSize(8.5f).setFontColor(NAVY)
                .setMarginTop(1f).setMarginBottom(0);
    }

    /** Diễn giải điều kiện áp dụng thành câu người bán đọc là hiểu. */
    private String describeScope(Voucher v) {
        if (v.getApplyScope() == null || v.getApplyScope() == Voucher.ApplyScope.ALL)
            return "Áp dụng cho toàn bộ sản phẩm của công ty.";
        int n = v.getApplyScope() == Voucher.ApplyScope.CATEGORY
                ? (v.getCategoryIds() != null ? v.getCategoryIds().size() : 0)
                : (v.getProductIds() != null ? v.getProductIds().size() : 0);
        String what = v.getApplyScope() == Voucher.ApplyScope.CATEGORY ? "danh mục" : "sản phẩm";
        return "Chỉ áp dụng cho " + n + " " + what + " được chỉ định.\n"
                + "Vui lòng xuất trình phiếu khi thanh toán.";
    }

    private String formatMoney(Long amount) {
        if (amount == null) return "0";
        return String.format("%,d", amount).replace(',', '.');
    }

    /**
     * Số tiền bằng chữ — bản rút gọn theo đơn vị nghìn/triệu, đủ để chống sửa chữ số
     * trên phiếu in mà không cần thư viện đọc số tiếng Việt đầy đủ.
     */
    private String moneyInWords(Long amount) {
        if (amount == null || amount <= 0) return "";
        if (amount % 1_000_000 == 0) return "(Bằng chữ: " + (amount / 1_000_000) + " triệu đồng)";
        if (amount % 1_000 == 0)     return "(Bằng chữ: " + formatMoney(amount / 1_000) + " nghìn đồng)";
        return "";
    }

    private String formatDate(Long millis) {
        LocalDate d = AnniversaryUtil.toLocalDate(millis);
        return d != null ? d.format(D_FMT) : "—";
    }

    /**
     * FONT NGHIÊNG.
     *
     * <p>iText 9 KHÔNG có {@code Paragraph.setItalic()} — muốn chữ nghiêng phải nạp một
     * file font nghiêng riêng. Project hiện chỉ đóng gói sẵn DejaVuSans thường và đậm,
     * nên hàm này thử nạp bản Oblique và <b>lùi về font thường</b> nếu không có.
     *
     * <p>Fallback phải là {@code regular} chứ KHÔNG dùng {@link #loadFont} — hàm đó lùi
     * về Helvetica, một font không có dấu tiếng Việt, sẽ làm dòng "Bằng chữ" và
     * "(Ký, ghi rõ họ tên)" mất dấu trên phiếu in.
     *
     * <p>Muốn có chữ nghiêng thật: thả {@code DejaVuSans-Oblique.ttf} vào
     * {@code src/main/resources/fonts/}, không cần sửa code.
     */
    private PdfFont loadItalicFont(PdfFont regular) {
        try (InputStream is = new ClassPathResource("fonts/DejaVuSans-Oblique.ttf").getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(),
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.debug("[Voucher] Không có font nghiêng, dùng font thường thay thế.");
            return regular;
        }
    }

    private PdfFont loadFont(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(),
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.warn("[Voucher] Không load được font {}: {}. Fallback Helvetica.", path, e.getMessage());
            try {
                return PdfFontFactory.createFont(
                        com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
    }

    private byte[] loadResource(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** Bảng màu + tiêu đề theo dịp tặng. */
    private record Theme(Color primary, Color accent, String title, String subtitle) {
        static Theme of(Voucher.VoucherReason reason) {
            if (reason == Voucher.VoucherReason.BIRTHDAY)
                return new Theme(ROSE, ROSE_DEEP, "PHIẾU QUÀ TẶNG", "Chúc mừng sinh nhật");
            if (reason == Voucher.VoucherReason.STORE_OPENING)
                return new Theme(EMERALD, EMERALD_DP, "PHIẾU QUÀ TẶNG", "Chúc mừng khai trương cửa hàng mới");
            return new Theme(GOLD, GOLD_DEEP, "PHIẾU QUÀ TẶNG", "Tri ân quý khách hàng");
        }
    }
}
