package com.nhatnam.server.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.*;
import com.nhatnam.server.dto.request.QuotationRequest;
import com.nhatnam.server.entity.Product;
import com.nhatnam.server.entity.ProductPriceTier;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.ProductRepository;
import com.nhatnam.server.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class QuotationPdfService {

    private final ProductRepository productRepository;
    private final FileStorageService fileStorageService;

    // ── Màu ──────────────────────────────────────────────────────────────────
    private static final DeviceRgb COLOR_TABLE_HEAD = new DeviceRgb(26,  39,  68);
    private static final DeviceRgb COLOR_ROW_ALT    = new DeviceRgb(242, 242, 242);
    private static final DeviceRgb COLOR_NAVY       = new DeviceRgb(26,  39,  68);
    private static final DeviceRgb COLOR_BORDER     = new DeviceRgb(180, 180, 180);

    // ── Thông tin công ty ────────────────────────────────────────────────────
    private static final String COMPANY_NAME    = "CÔNG TY TNHH SẢN XUẤT THỰC PHẨM TMDV NHẤT NAM";
    private static final String COMPANY_EMAIL   = "Email: info@nhatnamfinefoods.com.vn";
    private static final String COMPANY_WEBSITE = "www.nhatnamfinefoods.com";
    private static final String COMPANY_PHONE   = "Điện thoại: (028) 38479216 - 217-218-219";
    private static final String COMPANY_FB      = "FB: /nhatnamfinefoods.com.vn";

    // ── Layout ───────────────────────────────────────────────────────────────
    private static final float MARGIN_L = 36f;
    private static final float MARGIN_R = 36f;
    private static final float MARGIN_T = 36f;
    private static final float MARGIN_B = 50f;

    // ── Độ rộng cột bảng (point) ─────────────────────────────────────────────
    // Mã SP | Ảnh | Tên SP | Quy cách | Trước thuế | VAT% | Sau thuế | ĐVT
    private static final float[] COL_WIDTHS = {38f, 52f, 183f, 72f, 68f, 36f, 70f, 36f};

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════
    public byte[] generate(QuotationRequest request, User creator) throws Exception {

        // Load fonts — iText 9 không có setItalic(); italic phải dùng font riêng
        PdfFont fontReg  = loadFont("fonts/DejaVuSans.ttf");
        PdfFont fontBold = loadFont("fonts/DejaVuSans-Bold.ttf");
        // Dùng fontBold cho cả "Kính gửi" (thay vì bold-italic)

        byte[] logoBytes = loadResource("logo.png");

        List<ResolvedItem> items = resolveItems(request.getItems());
        if (items.isEmpty()) throw new IllegalArgumentException("Danh sách sản phẩm trống");

        // Người tạo
        String creatorName  = creator != null && creator.getFullName() != null && !creator.getFullName().isBlank()
                ? creator.getFullName() : (creator != null ? creator.getUsername() : "");
        String creatorPhone = creator != null && creator.getPhoneNumber() != null ? formatVietnamesePhone(creator.getPhoneNumber()) : "";
        String creatorEmail = creator != null && creator.getEmail()       != null ? creator.getEmail()       : "";

        String customerDisplay = (request.getCustomerName() != null && !request.getCustomerName().isBlank())
                ? request.getCustomerName() : "QUÝ KHÁCH HÀNG";
        String contentDisplay  = (request.getQuotationContent() != null && !request.getQuotationContent().isBlank())
                ? request.getQuotationContent().toUpperCase() : "";

        String nowStr = DateTimeFormatter
                .ofPattern("HH:mm dd/MM/yyyy")
                .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(Instant.now());

        // ── Pass 1: render nội dung ───────────────────────────────────────────
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
        pdfDoc.setDefaultPageSize(PageSize.A4);

        Document doc = new Document(pdfDoc, PageSize.A4);
        doc.setMargins(MARGIN_T, MARGIN_R, MARGIN_B, MARGIN_L);
        doc.setFont(fontReg).setFontSize(9f);

        addFirstPageHeader(doc, fontReg, fontBold, logoBytes,
                nowStr, customerDisplay, creatorName, creatorPhone, creatorEmail, contentDisplay);

        // ── Trang 1: tối đa MAX_ROWS_PAGE1 dòng ─────────────────────────────────
        final int MAX_ROWS_PAGE1 = 8;
        int totalItems = items.size();

        Table table1 = buildTableHeader(fontReg, fontBold);
        int endPage1 = Math.min(MAX_ROWS_PAGE1, totalItems);
        for (int i = 0; i < endPage1; i++) {
            addProductRow(table1, items.get(i), i % 2 == 1, fontReg, fontBold);
        }
        doc.add(table1);

        // ── Trang 2+: phần còn lại ───────────────────────────────────────────
        if (totalItems > MAX_ROWS_PAGE1) {
            // Ngắt trang — đảm bảo trang 2 luôn bắt đầu với ít nhất 1 sản phẩm
            doc.add(new com.itextpdf.layout.element.AreaBreak(
                    com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE));

            Table table2 = buildTableHeader(fontReg, fontBold);
            for (int i = MAX_ROWS_PAGE1; i < totalItems; i++) {
                addProductRow(table2, items.get(i), i % 2 == 1, fontReg, fontBold);
            }
            doc.add(table2);
        }

        addFooter(doc, fontReg, fontBold);
        doc.close();

        // ── Pass 2: stamp số trang "X/Y" ─────────────────────────────────────
        return stampPageNumbers(bos.toByteArray());
    }

    // ════════════════════════════════════════════════════════════════════════
    // PASS 2 — ghi số trang
    // Font PHẢI load mới trong PdfDocument này — không được reuse từ Pass 1
    // vì iText 9 không cho share PdfObject giữa 2 PdfDocument khác nhau
    // ════════════════════════════════════════════════════════════════════════
    private byte[] stampPageNumbers(byte[] pdfBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(
                new PdfReader(new java.io.ByteArrayInputStream(pdfBytes)),
                new PdfWriter(bos));

        // Load font hoàn toàn mới trong PdfDocument này
        PdfFont font;
        try {
            ClassPathResource res = new ClassPathResource("fonts/DejaVuSans.ttf");
            try (InputStream is = res.getInputStream()) {
                font = PdfFontFactory.createFont(is.readAllBytes(),
                        PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
            }
        } catch (Exception e) {
            font = PdfFontFactory.createFont(
                    com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
        }

        int total = pdfDoc.getNumberOfPages();
        for (int i = 1; i <= total; i++) {
            PdfPage   page   = pdfDoc.getPage(i);
            Rectangle sz     = page.getPageSize();
            PdfCanvas canvas = new PdfCanvas(page);
            String    text   = "Trang " + i + "/" + total;
            float     x      = sz.getRight() - MARGIN_R - 40f;
            float     y      = MARGIN_B - 20f;

            canvas.beginText()
                    .setFontAndSize(font, 8f)
                    .moveText(x, y)
                    .showText(text)
                    .endText()
                    .release();
        }
        pdfDoc.close();
        return bos.toByteArray();
    }

    // ════════════════════════════════════════════════════════════════════════
    // HEADER TRANG 1
    // ════════════════════════════════════════════════════════════════════════
    private void addFirstPageHeader(Document doc, PdfFont reg, PdfFont bold,
                                    byte[] logoBytes, String nowStr, String customer,
                                    String creatorName, String creatorPhone, String creatorEmail, String content) {

        // ── Header: logo | divider | thông tin công ty ────────────────────────
        Table hdr = new Table(new float[]{80f, 1.5f, 428f})
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 1f));

        // Logo
        Cell logoCell = new Cell().setBorder(Border.NO_BORDER)
                .setPadding(4f).setVerticalAlignment(VerticalAlignment.MIDDLE);
        if (logoBytes != null) {
            try {
                Image logo = new Image(ImageDataFactory.create(logoBytes));
                logo.setAutoScale(true).setMaxWidth(72f).setMaxHeight(55f);
                logoCell.add(logo);
            } catch (Exception e) {
                logoCell.add(new Paragraph("LOGO").setFont(reg).setFontSize(8f));
            }
        }
        hdr.addCell(logoCell);

        // Divider dọc
        hdr.addCell(new Cell().setBackgroundColor(ColorConstants.BLACK)
                .setPadding(0).setBorder(Border.NO_BORDER));

        // Thông tin công ty
        Cell info = new Cell().setBorder(Border.NO_BORDER).setPadding(6f);

        info.add(new Paragraph(COMPANY_NAME)
                .setFont(bold).setFontSize(11f).setFontColor(COLOR_NAVY)
                .setTextAlignment(TextAlignment.CENTER).setMarginBottom(3f));

        // Email | Website
        Table r1 = twoColRow();
        r1.addCell(noCell().add(new Paragraph(COMPANY_EMAIL).setFont(reg).setFontSize(8f)));
        r1.addCell(noCell().setTextAlignment(TextAlignment.RIGHT)
                .add(new Paragraph(COMPANY_WEBSITE)
                        .setFont(reg).setFontSize(8f)
                        .setFontColor(new DeviceRgb(0, 0, 200))));
        info.add(r1);

        // Phone | FB
        Table r2 = twoColRow();
        r2.addCell(noCell().add(new Paragraph(COMPANY_PHONE).setFont(reg).setFontSize(8f)));
        r2.addCell(noCell().setTextAlignment(TextAlignment.RIGHT)
                .add(new Paragraph(COMPANY_FB).setFont(reg).setFontSize(8f)));
        info.add(r2);

        hdr.addCell(info);
        doc.add(hdr);

        // ── BẢNG BÁO GIÁ ──────────────────────────────────────────────────────
        doc.add(new Paragraph("BẢNG BÁO GIÁ")
                .setFont(bold).setFontSize(14f).setFontColor(COLOR_NAVY)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(8f).setMarginBottom(2f));

        // Thời gian — iText 9 không có setItalic() → dùng fontReg thường, chú thích "(hh:mm dd/MM/yyyy)"
        doc.add(new Paragraph(nowStr)
                .setFont(reg).setFontSize(9f)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(5f));

        // ── Kính gửi / Từ / Phone / Email ────────────────────────────────────────
        // Giải pháp: outer table 2 cột cố định (trái 262pt, phải 261pt)
        // Mỗi cột là inner table 2 cột (label + value) — wrap độc lập
        // → "Từ:" và "Email:" luôn bắt đầu tại x=262pt, không bị đẩy
        //
        // Tổng nội dung = 523pt → trái 262pt | phải 261pt

        // Inner left: [label 52pt][value 210pt] = 262pt
        // Inner right: [label 40pt][value 221pt] = 261pt

        // ── Bảng thông tin trái (Kính gửi + Phone) ───────────────────────────
        Table leftInfo = new Table(new float[]{52f, 210f})
                .setBorder(Border.NO_BORDER);
        // Dòng 1 trái
        leftInfo.addCell(noCell().add(new Paragraph(
                new Text("Kính gửi: ").setFont(bold).setFontSize(9f))));
        leftInfo.addCell(noCell().add(new Paragraph(
                new Text(customer).setFont(bold).setFontSize(9f))));
        // Dòng 2 trái
        leftInfo.addCell(noCell().add(new Paragraph(
                new Text("Phone: ").setFont(bold).setFontSize(9f))));
        leftInfo.addCell(noCell().add(new Paragraph(
                new Text(creatorPhone).setFont(reg).setFontSize(9f))));

        // ── Bảng thông tin phải (Từ + Email) ─────────────────────────────────
        Table rightInfo = new Table(new float[]{40f, 221f})
                .setBorder(Border.NO_BORDER);
        // Dòng 1 phải
        rightInfo.addCell(noCell().add(new Paragraph(
                new Text("Từ: ").setFont(bold).setFontSize(9f))));
        rightInfo.addCell(noCell().add(new Paragraph(
                new Text(creatorName).setFont(reg).setFontSize(9f))));
        // Dòng 2 phải
        rightInfo.addCell(noCell().add(new Paragraph(
                new Text("Email: ").setFont(bold).setFontSize(9f))));
        rightInfo.addCell(noCell().add(new Paragraph(
                new Text(creatorEmail).setFont(reg).setFontSize(8.5f))));

        // ── Outer table: ghép trái + phải ─────────────────────────────────────
        // setKeepTogether(true) giữ 2 dòng cùng nhau khi wrap
        Table outerMeta = new Table(new float[]{262f, 261f})
                .setBorder(Border.NO_BORDER)
                .setMarginBottom(3f);

        // Cell trái: chứa leftInfo, vertical align TOP
        Cell outerLeft = new Cell()
                .setBorder(Border.NO_BORDER)
                .setPadding(0f)
                .setVerticalAlignment(VerticalAlignment.TOP)
                .add(leftInfo);
        outerMeta.addCell(outerLeft);

        // Cell phải: chứa rightInfo, vertical align TOP
        Cell outerRight = new Cell()
                .setBorder(Border.NO_BORDER)
                .setPadding(0f)
                .setVerticalAlignment(VerticalAlignment.TOP)
                .add(rightInfo);
        outerMeta.addCell(outerRight);

        doc.add(outerMeta);

        // ── Nội dung ──────────────────────────────────────────────────────────
        doc.add(new Paragraph()
                .add(new Text("Nội dung: ").setFont(bold).setFontSize(9f))
                .add(new Text(content).setFont(bold).setFontSize(9f))
                .setMarginTop(2f).setMarginBottom(6f));
    }

    // ════════════════════════════════════════════════════════════════════════
    // BẢNG SẢN PHẨM
    // ════════════════════════════════════════════════════════════════════════
    private Table buildTableHeader(PdfFont reg, PdfFont bold) {
        Table table = new Table(UnitValue.createPointArray(COL_WIDTHS))
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(new SolidBorder(COLOR_BORDER, 0.5f));

        String[] cols = {
                "Mã SP", "Hình ảnh", "Tên sản phẩm",
                "Quy Cách\nđóng gói",
                "Đơn giá\ntrước thuế",
                "VAT\n(%)",
                "Giá sau\nthuế",
                "ĐVT"
        };
        for (String h : cols) {
            table.addHeaderCell(new Cell()
                    .setBackgroundColor(COLOR_TABLE_HEAD)
                    .setBorder(new SolidBorder(COLOR_BORDER, 0.5f))
                    .setPadding(4f)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .add(new Paragraph(h)
                            .setFont(bold).setFontSize(8f)
                            .setFontColor(ColorConstants.WHITE)
                            .setTextAlignment(TextAlignment.CENTER)));
        }
        return table;
    }

    private void addProductRow(Table table, ResolvedItem item, boolean alt,
                               PdfFont reg, PdfFont bold) {
        DeviceRgb bg  = alt ? COLOR_ROW_ALT : null;
        Border    brd = new SolidBorder(COLOR_BORDER, 0.3f);

        // Mã SP
        table.addCell(cell(bg, brd).setTextAlignment(TextAlignment.CENTER)
                .add(new Paragraph(s(item.sku)).setFont(bold).setFontSize(8f)));

        // Ảnh
        Cell imgCell = cell(bg, brd).setTextAlignment(TextAlignment.CENTER).setPadding(3f);
        if (item.imageBytes != null) {
            try {
                Image img = new Image(ImageDataFactory.create(item.imageBytes));
                img.setAutoScale(true).setMaxWidth(48f).setMaxHeight(48f);
                imgCell.add(img);
            } catch (Exception ignored) {}
        }
        table.addCell(imgCell);

        // Tên + bảo quản
        Cell nameCell = cell(bg, brd);
        nameCell.add(new Paragraph(s(item.productName))
                .setFont(bold).setFontSize(9f).setMarginBottom(1f));
        if (!s(item.storageInstruction).isBlank()) {
            nameCell.add(new Paragraph(s(item.storageInstruction))
                    .setFont(bold).setFontSize(7.5f)
                    .setFontColor(new DeviceRgb(80, 80, 80)));
        }
        table.addCell(nameCell);

        // Quy cách
        table.addCell(cell(bg, brd).setTextAlignment(TextAlignment.CENTER)
                .add(new Paragraph(s(item.packagingDescription)).setFont(reg).setFontSize(8.5f)));

        // Giá trước thuế
        table.addCell(cell(bg, brd).setTextAlignment(TextAlignment.RIGHT)
                .add(new Paragraph(fmtMoney(item.preTaxPrice)).setFont(reg).setFontSize(8.5f)));

        // VAT %
        table.addCell(cell(bg, brd).setTextAlignment(TextAlignment.CENTER)
                .add(new Paragraph(item.vatRate + "%").setFont(reg).setFontSize(8.5f)));

        // Giá sau thuế
        table.addCell(cell(bg, brd).setTextAlignment(TextAlignment.RIGHT)
                .add(new Paragraph(fmtMoney(item.postTaxPrice)).setFont(bold).setFontSize(8.5f)));

        // ĐVT
        table.addCell(cell(bg, brd).setTextAlignment(TextAlignment.CENTER)
                .add(new Paragraph(s(item.unit)).setFont(reg).setFontSize(8.5f)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // FOOTER TRANG CUỐI
    // ════════════════════════════════════════════════════════════════════════
    private void addFooter(Document doc, PdfFont reg, PdfFont bold) {
        doc.add(new Paragraph()
                .add(new Text("* Giá trên đã bao gồm thuế VAT").setFont(bold).setFontSize(8.5f))
                .setMarginTop(10f));
        doc.add(new Paragraph(
                "* Giá trên chưa bao gồm phí vận chuyển, phí thùng xốp… nếu giao hàng tại các tỉnh thành ngoài")
                .setFont(reg).setFontSize(8.5f).setMarginTop(0f));
        doc.add(new Paragraph("khu vực TP. Hồ Chí Minh ( Bán kính trên 9Km).")
                .setFont(reg).setFontSize(8.5f).setMarginTop(0f));
        doc.add(new Paragraph(
                "   Chúng tôi xin chân thành cảm ơn Quý khách hàng đã tin dùng các sản phẩm của Nhất Nam trong")
                .setFont(reg).setFontSize(8.5f).setMarginTop(3f));
        doc.add(new Paragraph(
                "thời gian vừa qua và rất mong được hợp tác lâu dài với Quý khách hàng.")
                .setFont(reg).setFontSize(8.5f).setMarginTop(0f));
        doc.add(new Paragraph("Phòng Kinh Doanh")
                .setFont(bold).setFontSize(9f)
                .setTextAlignment(TextAlignment.RIGHT)
                .setMarginTop(8f).setMarginRight(20f));
    }

    // ════════════════════════════════════════════════════════════════════════
    // RESOLVE ITEMS — load product + tính giá
    // ════════════════════════════════════════════════════════════════════════
    private List<ResolvedItem> resolveItems(List<QuotationRequest.QuotationItem> reqs) {
        List<ResolvedItem> result = new ArrayList<>();
        if (reqs == null) return result;

        for (QuotationRequest.QuotationItem req : reqs) {
            Product p = productRepository.findById(req.getProductId()).orElse(null);
            if (p == null) continue;

            // Giá được chọn
            BigDecimal price;
            if (req.getTierId() != null) {
                price = p.getPriceTiers().stream()
                        .filter(t -> t.getId().equals(req.getTierId()))
                        .findFirst()
                        .map(ProductPriceTier::getPrice)
                        .orElse(p.getBasePrice());
            } else {
                price = p.getBasePrice();
            }
            if (price == null) price = BigDecimal.ZERO;

            // Tính giá trước / sau thuế — làm tròn hàng đơn vị HALF_UP
            int     vatRate = req.getVatRate();
            boolean inclsv  = "INCLUSIVE".equalsIgnoreCase(req.getVatMode());

            BigDecimal preTax, postTax;
            if (vatRate == 0) {
                preTax = postTax = price;
            } else if (inclsv) {
                // price = giá sau thuế; tách ngược
                BigDecimal div = BigDecimal.ONE
                        .add(BigDecimal.valueOf(vatRate).divide(BigDecimal.valueOf(100)));
                preTax  = price.divide(div, 10, RoundingMode.HALF_UP);
                postTax = price;
            } else {
                // price = giá trước thuế; cộng thêm
                preTax  = price;
                postTax = price.multiply(BigDecimal.ONE
                        .add(BigDecimal.valueOf(vatRate).divide(BigDecimal.valueOf(100))));
            }

            ResolvedItem ri = new ResolvedItem();
            ri.sku                  = s(p.getSku());
            ri.productName          = s(p.getName());
            ri.packagingDescription = s(p.getPackagingDescription());
            ri.storageInstruction   = s(p.getStorageInstruction());
            ri.unit                 = s(p.getUnit());
            ri.vatRate              = vatRate;
            ri.preTaxPrice          = preTax .setScale(0, RoundingMode.HALF_UP).longValue();
            ri.postTaxPrice         = postTax.setScale(0, RoundingMode.HALF_UP).longValue();
            ri.imageBytes           = loadProductImage(p.getImageUrl());
            result.add(ri);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private PdfFont loadFont(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            try (InputStream is = res.getInputStream()) {
                return PdfFontFactory.createFont(is.readAllBytes(),
                        PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
            }
        } catch (Exception e) {
            log.warn("[Quotation] Không load font {}: {}. Fallback Helvetica.", path, e.getMessage());
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
            log.warn("[Quotation] Không load resource {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Load ảnh sản phẩm từ imageUrl.
     * imageUrl có 2 dạng:
     *   - Local:  /images/product/xxx.png  → đọc qua fileStorageService
     *   - Remote: https://api.xxx/api/auth/images/product/xxx.png → extract path rồi đọc local
     */
    private byte[] loadProductImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        try {
            // Nếu là URL đầy đủ (http/https), extract phần path /images/...
            String filePath = imageUrl;
            if (imageUrl.startsWith("http")) {
                // Lấy phần sau /api/auth: /images/product/xxx.png
                int idx = imageUrl.indexOf("/api/auth");
                if (idx >= 0) {
                    filePath = imageUrl.substring(idx + "/api/auth".length());
                } else {
                    // Không có /api/auth → thử download trực tiếp
                    try (InputStream is = new java.net.URL(imageUrl).openStream()) {
                        return is.readAllBytes();
                    }
                }
            }
            // filePath = /images/product/xxx.png → fileStorageService.getFile()
            return fileStorageService.getFile(filePath);
        } catch (Exception e) {
            log.warn("[Quotation] Không load ảnh {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Chuẩn hóa số điện thoại Việt Nam về định dạng: 09 3812 1001
     * Chấp nhận: +84938121001, 84938121001, 0938121001
     */
    private String formatVietnamesePhone(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // Xóa tất cả ký tự không phải số
        String digits = raw.replaceAll("[^0-9]", "");
        // Nếu bắt đầu bằng 84 (mã quốc gia) → thay bằng 0
        if (digits.startsWith("84") && digits.length() >= 11) {
            digits = "0" + digits.substring(2);
        }
        // Chuẩn hóa về 10 chữ số
        if (digits.length() != 10) return raw; // không nhận dạng được → giữ nguyên
        // Format: XX XXXX XXXX
        return digits.substring(0, 2) + " " + digits.substring(2, 6) + " " + digits.substring(6);
    }

    private String fmtMoney(long amount) {
        return String.format("%,d", amount).replace(',', '.') + " đ";
    }

    private String s(String v) { return v != null ? v : ""; }

    private Cell cell(DeviceRgb bg, Border brd) {
        Cell c = new Cell().setBorder(brd)
                .setPaddingTop(4f).setPaddingBottom(4f)
                .setPaddingLeft(3f).setPaddingRight(3f)
                .setVerticalAlignment(VerticalAlignment.TOP);
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    private Cell noCell() {
        return new Cell().setBorder(Border.NO_BORDER).setPaddingBottom(2f);
    }

    private Table twoColRow() {
        return new Table(new float[]{1f, 1f})
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(Border.NO_BORDER);
    }

    // ── DTO nội bộ ────────────────────────────────────────────────────────────
    private static class ResolvedItem {
        String sku, productName, packagingDescription, storageInstruction, unit;
        int    vatRate;
        long   preTaxPrice, postTaxPrice;
        byte[] imageBytes;
    }
}