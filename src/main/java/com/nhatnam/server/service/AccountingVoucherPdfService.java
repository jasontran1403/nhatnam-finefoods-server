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
        return buildPdf("PHIẾU THU", "Mẫu số 01 - TT",
                "(Ban hành theo Thông tư số 200/2014/TT-BTC\nNgày 22/12/2014 của Bộ Tài chính)",
                s(v.getReceiptNumber(), v.getVoucherCode()),
                Instant.ofEpochMilli(v.getCreatedAt()).atZone(VN),
                s(v.getPayerName(),""), "Họ và tên người nộp tiền", "Lý do nộp",
                s(v.getReason(),""), total, s(v.getCreatedByName(),""), "Người nộp tiền");
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
                s(v.getVendorName(),""), "Họ tên người nhận tiền", "Lý do chi",
                s(v.getReason(),""), total, s(v.getCreatedByName(),""), "Người nhận tiền");
    }

    private byte[] buildPdf(String title, String formCode, String regulation,
                            String number, ZonedDateTime dt, String personName, String personLabel,
                            String reasonLabel, String reason, BigDecimal amount, String createdBy,
                            String signLabel) throws Exception {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfDocument pdf = new PdfDocument(new PdfWriter(baos));
        Document doc = new Document(pdf, PageSize.A4);
        doc.setMargins(28, 36, 28, 36);

        PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
        PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

        int day = dt.getDayOfMonth(), mo = dt.getMonthValue(), yr = dt.getYear();
        String amtNum = money.format(amount.setScale(0, RoundingMode.HALF_UP));
        String amtWords = numWords(amount.setScale(0, RoundingMode.HALF_UP).longValue());
        String dateStr = String.format("Ngày %02d tháng %02d năm %d", day, mo, yr);

        // ── HEADER: Đơn vị trái | Mẫu số phải ─────────────────────────────
        Table h1 = tbl(55, 45);
        h1.addCell(nb().add(p("Đơn vị: " + COMPANY, bd, 8)).add(p("Địa chỉ: " + ADDRESS, rg, 7)));
        h1.addCell(nb().setTextAlignment(TextAlignment.RIGHT)
                .add(p(formCode, bd, 9).setTextAlignment(TextAlignment.RIGHT))
                .add(p(regulation, rg, 6).setTextAlignment(TextAlignment.RIGHT)));
        doc.add(h1);
        doc.add(sp(6));

        // ── TITLE ──────────────────────────────────────────────────────────
        doc.add(p(title, bd, 18).setTextAlignment(TextAlignment.CENTER));
        doc.add(p(dateStr, rg, 10).setTextAlignment(TextAlignment.CENTER));
        doc.add(sp(4));

        // ── Quyển số / Số / Nợ / Có ───────────────────────────────────────
        Table info = tbl(100);
        float leading = 11f;

        String dotsQS = "..........................";
        String dotsSo = "...................................";
        String dotsNo = "....................................";
        String dotsCo = "....................................";

        // Layer 1: "Số:" in đậm + chấm (chấm dịch xuống 1pt)
        Paragraph soDots = new Paragraph()
                .add(new Text("Số: ").setFont(bd).setFontSize(9))
                .add(new Text(dotsSo).setFont(rg).setFontSize(8)
                        .setTextRise(-1))
                .setFixedLeading(leading)
                .setMarginBottom(0);

        // Layer 2: number đè lên chấm (spacer ẩn + số dịch lên 1pt)
        Paragraph soNum = new Paragraph()
                .add(new Text("Số: ")
                        .setFont(bd).setFontSize(9))
                .add(new Text(number)
                        .setFont(bd).setFontSize(9)
                        .setTextRise(2))
                .setFixedLeading(leading)
                .setMarginTop(-leading)
                .setMarginBottom(0);

        info.addCell(nb().setTextAlignment(TextAlignment.LEFT)
                .setPaddingLeft(410)
                .add(p("Quyển số: " + dotsQS, rg, 8))
                .add(sp(2))
                .add(soDots)
                .add(soNum)
                .add(sp(2))
                .add(p("Nợ: " + dotsNo, rg, 8))
                .add(sp(2))
                .add(p("Có: " + dotsCo, rg, 8)));

        doc.add(info);
        doc.add(sp(6));

        // ── BODY: Các field ──────────────────────────────────────────────
        float lblW = 24f;
        doc.add(field(personLabel + ":", personName, rg, 9, lblW));
        doc.add(sp(4));
        doc.add(field("Địa chỉ:", "", rg, 9, lblW));
        doc.add(sp(4));
        doc.add(field(reasonLabel + ":", reason, rg, 9, lblW));
        doc.add(sp(4));
        doc.add(field("Số tiền:", amtNum, bd, 10, lblW));
        doc.add(sp(4));
        doc.add(field("(Viết bằng chữ):", amtWords, rg, 9, lblW));
        doc.add(sp(2));

        // ── Kèm theo: chấm chỉ đến "chứng từ gốc." ──────────────────
        Table kemTheo = tbl(lblW, 8, 20, 100 - lblW - 8 - 20);
        kemTheo.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p("Kèm theo:", rg, 8).setPaddingTop(0).setPaddingBottom(0)));
        kemTheo.addCell(nb()
                .setBorderBottom(new DottedBorder(ColorConstants.BLACK, 0.5f))
                .setPaddingTop(0).setPaddingBottom(1));  // chỉ chấm, không text
        kemTheo.addCell(nb().setPaddingTop(0).setPaddingBottom(0)
                .add(p("chứng từ gốc.", rg, 8).setPaddingTop(0).setPaddingBottom(0)));
        kemTheo.addCell(nb());  // trống
        doc.add(kemTheo);

        doc.add(sp(6));

        // ── Ngày tháng canh phải và Người lập phiếu ──────────────────────
        Table dtLine = tbl(50, 50);
        dtLine.addCell(nb());
        dtLine.addCell(nb().setTextAlignment(TextAlignment.RIGHT)
                .add(p(dateStr, rg, 8).setTextAlignment(TextAlignment.RIGHT))
                .setPaddingRight(12));
        doc.add(dtLine);
        doc.add(sp(2));

        // ── CHỮ KÝ ─────────────────────────────────────────────────────────
        Table sig = tbl(28, 28, 28, 28);
        sig.addCell(sigCell("Giám đốc", "(Ký, họ tên, đóng dấu)", bd, rg));
        sig.addCell(sigCell("Kế toán trưởng", "(Ký, họ tên)", bd, rg));
        sig.addCell(sigCell(signLabel, "(Ký, họ tên)", bd, rg));
        sig.addCell(sigCell("Người lập phiếu", "(Ký, họ tên)", bd, rg));
        doc.add(sig);

        // ── FOOTER: Dịch xuống 3 dòng ────────────────────────────────────
        doc.add(sp(6));
        doc.add(sp(6));
        doc.add(sp(6));
        doc.add(sp(6));
        doc.add(sp(6));
        doc.add(field("Đã nhận đủ số tiền (viết bằng chữ):", amtWords, rg, 9, 32));
        doc.add(sp(3));
        doc.add(p("+ Tỷ giá ngoại tệ (vàng, bạc, đá quý):......................................................................", rg, 7));
        doc.add(sp(3));
        doc.add(p("+ Số tiền quy đổi:............................................................................................................", rg, 7));
        doc.add(sp(3));
        doc.add(p("(Liên gửi ra ngoài phải đóng dấu)", rg, 7));

        doc.close();
        return baos.toByteArray();
    }

    // ═════ Helpers ═══════════════════════════════════════════════════════════

    private Paragraph p(String t, PdfFont f, float s) {
        return new Paragraph(t).setFont(f).setFontSize(s).setMargin(0).setPadding(0);
    }
    private Paragraph sp(float pt) { return new Paragraph("\n").setFontSize(pt).setMargin(0).setPadding(0); }
    private Cell nb() { return new Cell().setBorder(Border.NO_BORDER).setPadding(2); }
    private Table tbl(float... c) { return new Table(UnitValue.createPercentArray(c)).useAllAvailableWidth(); }
    private String s(String v, String fb) { return v != null && !v.isBlank() ? v : fb; }

    /**
     * Label (cột trái, không chấm) + Value (cột phải, DottedBorder dưới chân sát text).
     * {@code lblPct} = % chiều rộng label, canh cho label dài nhất thẳng hàng.
     */
    private Table field(String label, String value, PdfFont font, float size, float lblPct) {
        Table t = tbl(lblPct, 100 - lblPct);
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

    private Cell sigCell(String title, String hint, PdfFont bd, PdfFont rg) {
        Cell c = nb().setTextAlignment(TextAlignment.CENTER).setMinHeight(70);
        c.add(p(title, bd, 8).setTextAlignment(TextAlignment.CENTER));
        c.add(p(hint, rg, 7).setTextAlignment(TextAlignment.CENTER).setFontColor(GREY));
        c.add(sp(6)); c.add(sp(6)); c.add(sp(6));
        return c;
    }

    private PdfFont loadFont(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(), PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.warn("Font {} fail: {}", path, e.getMessage());
            try { return PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        }
    }

    // ═════ Số → chữ tiếng Việt (VIẾT HOA chữ đầu) ══════════════════════════
    private static final String[] DG = {"không","một","hai","ba","bốn","năm","sáu","bảy","tám","chín"};

    private String numWords(long n) {
        if (n == 0) return "Không đồng";
        if (n < 0) return "Âm " + numWords(-n);
        StringBuilder sb = new StringBuilder();
        long ty = n / 1_000_000_000L;
        long tr = (n % 1_000_000_000L) / 1_000_000L;
        long ng = (n % 1_000_000L) / 1_000L;
        long dv = n % 1_000L;
        if (ty > 0) { grp(sb, (int)ty, false); sb.append(" tỷ"); }
        if (tr > 0) { sep(sb); grp(sb, (int)tr, sb.length()>0 && tr<100); sb.append(" triệu"); }
        if (ng > 0) { sep(sb); grp(sb, (int)ng, sb.length()>0 && ng<100); sb.append(" nghìn"); }
        if (dv > 0) { sep(sb); grp(sb, (int)dv, sb.length()>0 && dv<100); }
        sb.append(" đồng");
        String r = sb.toString().trim();
        return r.isEmpty() ? "Không đồng" : Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }
    private void sep(StringBuilder sb) { if (sb.length() > 0) sb.append(" "); }
    private void grp(StringBuilder sb, int g, boolean forceTram) {
        int t = g/100, c = (g%100)/10, d = g%10;
        if (t > 0) { sb.append(DG[t]).append(" trăm"); }
        else if (forceTram) { sb.append("không trăm"); }
        if (c > 1) {
            sb.append(" ").append(DG[c]).append(" mươi");
            if (d==1) sb.append(" mốt"); else if (d==4) sb.append(" tư");
            else if (d==5) sb.append(" lăm"); else if (d>0) sb.append(" ").append(DG[d]);
        } else if (c == 1) {
            sb.append(" mười");
            if (d==1) sb.append(" một"); else if (d==5) sb.append(" lăm");
            else if (d>0) sb.append(" ").append(DG[d]);
        } else if (d > 0) {
            if (t>0||forceTram) sb.append(" lẻ ");
            else if (sb.length()>0) sb.append(" ");
            sb.append(DG[d]);
        }
    }
}