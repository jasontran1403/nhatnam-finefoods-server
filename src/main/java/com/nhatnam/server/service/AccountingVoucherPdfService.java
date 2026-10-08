package com.nhatnam.server.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.DottedBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.entity.IncomeVoucher;
import com.nhatnam.server.repository.ExpenseVoucherRepository;
import com.nhatnam.server.repository.IncomeVoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

@Service @RequiredArgsConstructor @Slf4j
public class AccountingVoucherPdfService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String COMPANY = "CÔNG TY TNHH NHẤT NAM FINE FOODS";
    private static final String ADDRESS = "199 Phan Đình Phùng, Phường 15, Quận Phú Nhuận, TP.HCM";
    private static final String DIRECTOR_NAME = "Tạ Lê Nam Đức";
    private static final com.itextpdf.kernel.colors.DeviceRgb GREY =
            new com.itextpdf.kernel.colors.DeviceRgb(150, 150, 150);

    private final IncomeVoucherRepository incomeRepo;
    private final ExpenseVoucherRepository expenseRepo;
    private final DecimalFormat money =
            new DecimalFormat("#,##0", new DecimalFormatSymbols(new Locale("vi", "VN")));

    public byte[] generateIncomeVoucher(Long id) throws Exception {
        IncomeVoucher v = incomeRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu thu #" + id));
        BigDecimal total = v.getItems().stream()
                .map(i -> i.getAmount() != null ? i.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ═══ THAY ĐỔI ═══
        // personName = tên KHÁCH HÀNG (hiển thị ở dòng "Họ tên người nộp tiền")
        // signName   = tên NGƯỜI NỘP thực tế (hiển thị ở phần chữ ký)
        String personName = s(v.getCustomerName(), s(v.getPayerName(), ""));
        String signName   = s(v.getPayerName(), "");

        return buildPdf("PHIẾU THU", "Mẫu số 01 - TT",
                "(Ban hành theo Thông tư số 200/2014/TT-BTC\nNgày 22/12/2014 của Bộ Tài chính)",
                s(v.getReceiptNumber(), v.getVoucherCode()),
                Instant.ofEpochMilli(v.getCreatedAt()).atZone(VN),
                personName,                          // ← tên KH ở header
                "Họ và tên người nộp tiền",
                "Lý do nộp",
                s(v.getReason(), ""),
                total,
                s(v.getCreatedByName(), ""),
                "Người nộp tiền",
                signName.replaceAll("\\[NN\\]", ""));                           // ← tên người nộp ở chữ ký
    }


    public byte[] generateExpenseVoucher(Long id) throws Exception {
        ExpenseVoucher v = expenseRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy phiếu chi #" + id));
        BigDecimal total = v.getItems().stream()
                .map(i -> i.getAmount() != null ? i.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return buildPdf("PHIẾU CHI", "Mẫu số 02 - TT",
                "(Ban hành theo Thông tư số 133/2016/TT-BTC\nNgày 26/8/2016 của Bộ Tài chính)",
                s(v.getPaymentNumber(), v.getVoucherCode()),
                Instant.ofEpochMilli(v.getCreatedAt()).atZone(VN),
                s(v.getVendorName(), ""), "Họ tên người nhận tiền", "Lý do chi",
                s(v.getReason(), ""), total, s(v.getCreatedByName(), ""),
                "Người nhận tiền",
                v.getVendorName().replaceAll("\\[NN\\]", ""));

    }

    private byte[] buildPdf(String title, String formCode, String regulation,
                            String number, ZonedDateTime dt, String personName, String personLabel,
                            String reasonLabel, String reason, BigDecimal amount, String createdBy,
                            String signLabel, String signPersonName) throws Exception {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdf = new PdfDocument(new PdfWriter(baos));

        // CHUYỂN SANG KHỔ A5 NGANG (A5.LANDSCAPE)
        Document doc = new Document(pdf, PageSize.A5.rotate());
        doc.setMargins(20, 28, 20, 50);

        PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
        PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

        int day = dt.getDayOfMonth(), mo = dt.getMonthValue(), yr = dt.getYear();
        String amtNum = money.format(amount.setScale(0, RoundingMode.HALF_UP));
        String amtWords = numWords(amount.setScale(0, RoundingMode.HALF_UP).longValue());
        String dateStr = String.format("Ngày %02d tháng %02d năm %d", day, mo, yr);

        // ── HEADER: Đơn vị trái | Mẫu số phải ─────────────────────────────
        Table h1 = tbl(60, 40);
        h1.addCell(nb().add(p("Đơn vị: " + COMPANY, bd, 7)).add(p("Địa chỉ: " + ADDRESS, rg, 6)));
        h1.addCell(nb().setTextAlignment(TextAlignment.RIGHT)
                .add(p(formCode, bd, 8).setTextAlignment(TextAlignment.RIGHT))
                .add(p(regulation, rg, 5).setTextAlignment(TextAlignment.RIGHT)));
        doc.add(h1);
        doc.add(sp(4));

        // ── TITLE ──────────────────────────────────────────────────────────
        doc.add(p(title, bd, 15).setTextAlignment(TextAlignment.CENTER));
        doc.add(p(dateStr, rg, 9).setTextAlignment(TextAlignment.CENTER));
        doc.add(sp(3));


        // ── Quyển số / Số / Nợ / Có (4 dòng riêng, canh trái) ──────────
        Table info = tbl(80);
        float leading = 10f;

        String dotsQS = "....................................";
        String dotsSo = "..................................................";
        String dotsNo = "..................................................";
        String dotsCo = "..................................................";

        // SỬA: Dùng canh trái và padding trái để các label thẳng hàng
        // Dòng 1: Quyển số - dấu chấm
        Paragraph qsLine = new Paragraph()
                .add(new Text("Quyển số: ").setFont(bd).setFontSize(8))
                .add(new Text(dotsQS).setFont(rg).setFontSize(7)
                        .setTextRise(-1))
                .setFixedLeading(leading)
                .setMarginBottom(0);

// Dòng 2: Số - có dấu chấm ở dưới chân
        Paragraph soDots = new Paragraph()
                .add(new Text("Số: ").setFont(bd).setFontSize(8))
                .add(new Text(dotsSo).setFont(rg).setFontSize(7)
                        .setTextRise(-1))
                .setFixedLeading(leading)
                .setMarginBottom(0);

        Paragraph soNum = new Paragraph()
                .add(new Text("Số: ")
                        .setFont(bd).setFontSize(8))
                .add(new Text(number)
                        .setFont(bd).setFontSize(8)
                        .setTextRise(2))
                .setFixedLeading(leading)
                .setMarginTop(-leading)
                .setMarginBottom(0);

// Dòng 3: Nợ - dấu chấm
        Paragraph noLine = new Paragraph()
                .add(new Text("Nợ: ").setFont(bd).setFontSize(8))
                .add(new Text(dotsNo).setFont(rg).setFontSize(7)
                        .setTextRise(-1))
                .setFixedLeading(leading)
                .setMarginBottom(0);

        // Dòng 4: Có - dấu chấm
        Paragraph coLine = new Paragraph()
                .add(new Text("Có: ").setFont(bd).setFontSize(8))
                .add(new Text(dotsCo).setFont(rg).setFontSize(7)
                        .setTextRise(-1))
                .setFixedLeading(leading)
                .setMarginBottom(0);

        Cell infoCell = nb().setTextAlignment(TextAlignment.LEFT)  // SỬA: canh trái
                .setPaddingLeft(350)  // SỬA: padding trái để tất cả thẳng hàng
                .add(qsLine)
                .add(sp(1))
                .add(soDots)
                .add(soNum)
                .add(sp(1))
                .add(noLine)
                .add(sp(1))
                .add(coLine);

        info.addCell(infoCell);
        doc.add(info);
        doc.add(sp(4));

        // ── BODY: Các field trên từng dòng riêng ──────────────────────────
        // Dòng 1: Họ tên người nhận/nộp
        doc.add(fieldLine(personLabel + ":", personName, rg, 8, 25, 100));
        doc.add(sp(1));

        // Dòng 2: Địa chỉ
        doc.add(fieldLine("Địa chỉ:", "", rg, 8, 25, 100));
        doc.add(sp(1));

        // Dòng 3: Lý do
        float reasonSize = autoSizeForReason(reason, 8f);
        doc.add(fieldLine(reasonLabel + ":", reason, rg, reasonSize, 25, 100));
        doc.add(sp(1));

        // Dòng 4: Số tiền
        doc.add(fieldLine("Số tiền:", amtNum, bd, 9, 25, 100));
        doc.add(sp(1));

        // Dòng 5: Viết bằng chữ
        doc.add(fieldLine("(Viết bằng chữ):", amtWords, rg, 8, 25, 100));
        doc.add(sp(1));

        // ── Kèm theo (full width) ──────────────────────────────────────
        Table kemTheo = tbl(28, 20, 60, 30);
        kemTheo.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p("Kèm theo:", rg, 7).setPaddingTop(0).setPaddingBottom(0)));
        kemTheo.addCell(nb()
                .setBorderBottom(new DottedBorder(ColorConstants.BLACK, 0.5f))
                .setPaddingTop(0).setPaddingBottom(1));
        kemTheo.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p("chứng từ gốc.", rg, 7).setPaddingTop(0).setPaddingBottom(0)));
        kemTheo.addCell(nb());  // trống
        doc.add(kemTheo);
        doc.add(sp(2));

        // ── Ngày tháng (bên phải) ─────────────────────────────────────
        Table dtLine = tbl(70, 30);
        dtLine.addCell(nb());
        dtLine.addCell(nb().setTextAlignment(TextAlignment.RIGHT)
                .add(p(dateStr, rg, 7).setTextAlignment(TextAlignment.RIGHT))
                .setPaddingRight(20));
        doc.add(dtLine);
        doc.add(sp(1));

        // ── CHỮ KÝ (4 cột) ─────────────────────────────────────────────
        Table sig = tbl(25, 25, 25, 25);
        sig.addCell(sigCellWithName("Giám đốc", "(Ký, họ tên, đóng dấu)",
                DIRECTOR_NAME, bd, rg, 60));
        sig.addCell(sigCell("Kế toán trưởng", "(Ký, họ tên)", bd, rg, 60));
        // ═══ THAY ĐỔI: hiển thị tên người nộp dưới phần ký ═══
        sig.addCell(sigCellWithName(signLabel, "(Ký, họ tên)", signPersonName, bd, rg, 60));
        sig.addCell(sigCellWithName("Người lập phiếu", "(Ký, họ tên)",
                createdBy != null ? createdBy.replaceAll("\\[NN\\]\\s*", "") : null, bd, rg, 60));
        doc.add(sig);


        // ── FOOTER ──────────────────────────────────────────────────────
        doc.add(sp(4));
        doc.add(fieldLine("Đã nhận đủ số tiền (viết bằng chữ):", amtWords, rg, 7, 35, 100));
        doc.add(sp(2));
        doc.add(p("+ Tỷ giá ngoại tệ (vàng, bạc, đá quý):...........................", rg, 6));
        doc.add(sp(2));
        doc.add(p("+ Số tiền quy đổi:.....................................................", rg, 6));
        doc.add(sp(2));
        doc.add(p("(Liên gửi ra ngoài phải đóng dấu)", rg, 6));

        doc.close();
        return baos.toByteArray();
    }

// ═════ Helpers (Thêm mới) ═══════════════════════════════════════════════

    private float autoSizeForReason(String reason, float base) {
        if (reason == null) return base;
        int len = reason.length();
        if (len <= 90)   return base;        // 8
        if (len <= 140)  return base - 1f;   // 7
        if (len <= 200)  return base - 1.5f; // 6.5
        if (len <= 280)  return base - 2f;   // 6
        return base - 2.5f;                  // 5.5
    }


    /**
     * Field dạng 1 dòng riêng (label + value trên 1 dòng)
     */
    private Table fieldLine(String label, String value, PdfFont font, float size, float labelPct, float valuePct) {
        Table t = tbl(labelPct, valuePct);
        t.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p(label, font, size).setPaddingTop(0).setPaddingBottom(0)));
        Cell val = nb()
                .setBorderBottom(new DottedBorder(ColorConstants.BLACK, 0.5f))
                .setPaddingTop(0).setPaddingBottom(1);
        val.add(new Paragraph(value != null && !value.isEmpty() ? value : " ")
                .setFont(font).setFontSize(size).setMargin(0).setPaddingTop(0).setPaddingBottom(0));
        t.addCell(val);
        return t;
    }

    private Cell sigCellWithName(String title, String hint, String name,
                                 PdfFont bd, PdfFont rg, float minHeight) {
        Cell c = nb().setTextAlignment(TextAlignment.CENTER).setMinHeight(minHeight);
        c.add(p(title, bd, 7).setTextAlignment(TextAlignment.CENTER));
        c.add(p(hint, rg, 6).setTextAlignment(TextAlignment.CENTER).setFontColor(GREY));
        c.add(sp(24));
        // Ghi tên người nộp nếu có
        if (name != null && !name.isBlank()) {
            c.add(p(name, bd, 7).setTextAlignment(TextAlignment.CENTER));
        }
        return c;
    }

    // Giữ lại các helper cũ để tương thích
    private Paragraph p(String t, PdfFont f, float s) {
        return new Paragraph(t).setFont(f).setFontSize(s).setMargin(0).setPadding(0);
    }

    private Paragraph sp(float pt) {
        return new Paragraph("\n").setFontSize(pt).setMargin(0).setPadding(0);
    }

    private Cell nb() {
        return new Cell().setBorder(Border.NO_BORDER).setPadding(1);
    }

    private Table tbl(float... c) {
        return new Table(UnitValue.createPercentArray(c)).useAllAvailableWidth();
    }

    private String s(String v, String fb) {
        return v != null && !v.isBlank() ? v : fb;
    }

    /**
     * Field dạng inline (không xuống dòng) cho bố cục ngang.
     * Label + Value trên cùng 1 dòng với dotted border dưới value.
     * Thêm tham số labelPct và valuePct để điều chỉnh tỉ lệ
     */
    private Table fieldInline(String label, String value, PdfFont font, float size, float labelPct, float valuePct) {
        Table t = tbl(labelPct, valuePct);
        t.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p(label, font, size).setPaddingTop(0).setPaddingBottom(0)));
        Cell val = nb()
                .setBorderBottom(new DottedBorder(ColorConstants.BLACK, 0.5f))
                .setPaddingTop(0).setPaddingBottom(1);
        val.add(new Paragraph(value != null && !value.isEmpty() ? value : " ")
                .setFont(font).setFontSize(size).setMargin(0).setPaddingTop(0).setPaddingBottom(0));
        t.addCell(val);
        return t;
    }

    // Overload giữ nguyên cho tương thích ngược
    private Table fieldInline(String label, String value, PdfFont font, float size) {
        return fieldInline(label, value, font, size, 30, 70);
    }

    /**
     * Field full width cho label dài
     * Thêm tham số labelPct và valuePct để điều chỉnh tỉ lệ
     */
    private Table fieldFull(String label, String value, PdfFont font, float size, float labelPct, float valuePct) {
        Table t = tbl(labelPct, valuePct);
        t.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p(label, font, size).setPaddingTop(0).setPaddingBottom(0)));
        Cell val = nb()
                .setBorderBottom(new DottedBorder(ColorConstants.BLACK, 0.5f))
                .setPaddingTop(0).setPaddingBottom(1);
        val.add(new Paragraph(value != null && !value.isEmpty() ? value : " ")
                .setFont(font).setFontSize(size).setMargin(0).setPaddingTop(0).setPaddingBottom(0));
        t.addCell(val);
        return t;
    }

    // Overload giữ nguyên cho tương thích ngược
    private Table fieldFull(String label, String value, PdfFont font, float size) {
        return fieldFull(label, value, font, size, 25, 75);
    }

    private Cell sigCell(String title, String hint, PdfFont bd, PdfFont rg, float minHeight) {
        Cell c = nb().setTextAlignment(TextAlignment.CENTER).setMinHeight(minHeight);
        c.add(p(title, bd, 7).setTextAlignment(TextAlignment.CENTER));
        c.add(p(hint, rg, 6).setTextAlignment(TextAlignment.CENTER).setFontColor(GREY));
        c.add(sp(4));
        c.add(sp(4));
        return c;
    }

    private PdfFont loadFont(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(), PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.warn("Font {} fail: {}", path, e.getMessage());
            try {
                return PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    // ═════ Số → chữ tiếng Việt (GIỮ NGUYÊN) ═══════════════════════════════
    private static final String[] DG = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};

    private String numWords(long n) {
        if (n == 0) return "Không đồng";
        if (n < 0) return "Âm " + numWords(-n);
        StringBuilder sb = new StringBuilder();
        long ty = n / 1_000_000_000L;
        long tr = (n % 1_000_000_000L) / 1_000_000L;
        long ng = (n % 1_000_000L) / 1_000L;
        long dv = n % 1_000L;
        if (ty > 0) {
            grp(sb, (int) ty, false);
            sb.append(" tỷ");
        }
        if (tr > 0) {
            sep(sb);
            grp(sb, (int) tr, sb.length() > 0 && tr < 100);
            sb.append(" triệu");
        }
        if (ng > 0) {
            sep(sb);
            grp(sb, (int) ng, sb.length() > 0 && ng < 100);
            sb.append(" nghìn");
        }
        if (dv > 0) {
            sep(sb);
            grp(sb, (int) dv, sb.length() > 0 && dv < 100);
        }
        sb.append(" đồng");
        String r = sb.toString().trim();
        return r.isEmpty() ? "Không đồng" : Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }

    private void sep(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" ");
    }

    private void grp(StringBuilder sb, int g, boolean forceTram) {
        int t = g / 100, c = (g % 100) / 10, d = g % 10;
        if (t > 0) {
            sb.append(DG[t]).append(" trăm");
        } else if (forceTram) {
            sb.append("không trăm");
        }
        if (c > 1) {
            sb.append(" ").append(DG[c]).append(" mươi");
            if (d == 1) sb.append(" mốt");
            else if (d == 4) sb.append(" tư");
            else if (d == 5) sb.append(" lăm");
            else if (d > 0) sb.append(" ").append(DG[d]);
        } else if (c == 1) {
            sb.append(" mười");
            if (d == 1) sb.append(" một");
            else if (d == 5) sb.append(" lăm");
            else if (d > 0) sb.append(" ").append(DG[d]);
        } else if (d > 0) {
            if (t > 0 || forceTram) sb.append(" lẻ ");
            else if (sb.length() > 0) sb.append(" ");
            sb.append(DG[d]);
        }
    }
}
