package com.nhatnam.server.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.nhatnam.server.dto.cashflow.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Xuất PDF báo cáo dòng tiền theo kiểu sao kê ngân hàng. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CashflowPdfService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VN);
    private static final DateTimeFormatter D  = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(VN);
    private static final DeviceRgb GOLD  = new DeviceRgb(201, 168, 76);
    private static final DeviceRgb DARK  = new DeviceRgb(28, 28, 30);
    private static final DeviceRgb GREEN = new DeviceRgb(21, 128, 61);
    private static final DeviceRgb RED   = new DeviceRgb(190, 40, 40);
    private static final DeviceRgb GREY  = new DeviceRgb(142, 136, 120);
    private static final DeviceRgb LIGHT = new DeviceRgb(245, 241, 232);

    private final DecimalFormat money =
            new DecimalFormat("#,##0", new DecimalFormatSymbols(new Locale("vi", "VN")));

    public byte[] generate(CashflowSummaryDto s, String exportedBy) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf, PageSize.A4);
        doc.setMargins(36, 36, 36, 36);

        PdfFont reg  = loadFont("fonts/DejaVuSans.ttf");
        PdfFont bold = loadFont("fonts/DejaVuSans-Bold.ttf");
        doc.setFont(reg).setFontSize(9);

        // ── Tiêu đề ───────────────────────────────────────────────────────────
        doc.add(new Paragraph("BÁO CÁO DÒNG TIỀN").setFont(bold).setFontSize(16)
                .setFontColor(DARK).setTextAlignment(TextAlignment.CENTER).setMarginBottom(2));
        doc.add(new Paragraph("Từ " + D.format(Instant.ofEpochMilli(s.getFrom()))
                + " đến " + DT.format(Instant.ofEpochMilli(s.getTo())))
                .setFontColor(GREY).setTextAlignment(TextAlignment.CENTER).setMarginBottom(12));

        // ── I. Số dư (Đầu kỳ · Phát sinh · Cuối kỳ) ──────────────────────────
        sectionTitle(doc, bold, "I. SỐ DƯ");
        balanceMatrixTable(doc, reg, bold, s.getOpening(), s.getClosing());

        // ── II. Phát sinh trong kỳ ───────────────────────────────────────────
        sectionTitle(doc, bold, "II. PHÁT SINH TRONG KỲ");

        doc.add(new Paragraph("Thu").setFont(bold).setFontColor(GREEN).setMarginTop(4).setMarginBottom(2));
        flowTable(doc, reg, bold, s.getIncomes(), true);
        doc.add(new Paragraph("Tổng thu: tiền mặt " + money.format(nz(s.getIncomeCashTotal()))
                + " đ · chuyển khoản " + money.format(nz(s.getIncomeBankTotal())) + " đ")
                .setFont(bold).setFontColor(GREEN).setFontSize(9).setMarginBottom(6));

        doc.add(new Paragraph("Chi (đã duyệt)").setFont(bold).setFontColor(RED).setMarginTop(4).setMarginBottom(2));
        flowTable(doc, reg, bold, s.getExpenses(), false);
        doc.add(new Paragraph("Tổng chi: tiền mặt " + money.format(nz(s.getExpenseCashTotal()))
                + " đ · chuyển khoản " + money.format(nz(s.getExpenseBankTotal())) + " đ")
                .setFont(bold).setFontColor(RED).setFontSize(9).setMarginBottom(6));

        // ── Marker xác nhận ───────────────────────────────────────────────────
        if (s.getConfirmations() != null && !s.getConfirmations().isEmpty()) {
            sectionTitle(doc, bold, "III. XÁC NHẬN DÒNG TIỀN");
            for (CashflowConfirmationDto c : s.getConfirmations()) confirmRow(doc, reg, bold, c);
        }

        doc.add(new Paragraph("Người xuất báo cáo: " + (exportedBy == null ? "—" : exportedBy)
                + " · " + DT.format(Instant.now()))
                .setFontColor(GREY).setFontSize(8).setMarginTop(16).setTextAlignment(TextAlignment.RIGHT));

        doc.close();
        return baos.toByteArray();
    }

    private void sectionTitle(Document doc, PdfFont bold, String text) {
        doc.add(new Paragraph(text).setFont(bold).setFontSize(11).setFontColor(GOLD)
                .setMarginTop(10).setMarginBottom(4));
    }

    /**
     * Bảng SỐ DƯ 4 cột: Nhãn | Đầu kỳ | Phát sinh | Cuối kỳ.
     * Dòng: Tiền mặt, Ngân hàng (tổng), từng ngân hàng con, và dòng TỔNG.
     * Cột "Phát sinh" = Cuối kỳ − Đầu kỳ của từng loại.
     */
    private void balanceMatrixTable(Document doc, PdfFont reg, PdfFont bold,
                                    CashPositionDto opening, CashPositionDto closing) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{34, 22, 22, 22})).useAllAvailableWidth();

        // Header (dọc): cột 1 là nhãn, 3 cột số
        header(t, bold, "");
        header(t, bold, "Đầu kỳ");
        header(t, bold, "Phát sinh");
        header(t, bold, "Cuối kỳ");

        // Tiền mặt
        matrixRow(t, reg, bold, "Tiền mặt", 0,
                nz(opening.getCash()), nz(closing.getCash()), true);

        // Ngân hàng (tổng)
        matrixRow(t, reg, bold, "Ngân hàng", 0,
                nz(opening.getBankTotal()), nz(closing.getBankTotal()), true);

        // Từng ngân hàng con (căn theo tên)
        if (opening.getBanks() != null) {
            for (BankBalanceDto ob : opening.getBanks()) {
                BigDecimal open = nz(ob.getBalance());
                BigDecimal close = bankBalanceByName(closing, ob.getName());
                matrixRow(t, reg, bold, "•  " + ob.getName(), 1, open, close, false);
            }
        }
        // Ngân hàng chỉ có ở cuối kỳ (nếu có)
        if (closing.getBanks() != null) {
            for (BankBalanceDto cb : closing.getBanks()) {
                boolean existedInOpening = opening.getBanks() != null &&
                        opening.getBanks().stream().anyMatch(x -> eqName(x.getName(), cb.getName()));
                if (!existedInOpening) {
                    matrixRow(t, reg, bold, "•  " + cb.getName(), 1, BigDecimal.ZERO, nz(cb.getBalance()), false);
                }
            }
        }

        // TỔNG (tiền mặt + ngân hàng)
        BigDecimal openTotal  = nz(opening.getCash()).add(nz(opening.getBankTotal()));
        BigDecimal closeTotal = nz(closing.getCash()).add(nz(closing.getBankTotal()));
        matrixTotalRow(t, bold, "TỔNG", openTotal, closeTotal.subtract(openTotal), closeTotal);

        doc.add(t.setMarginBottom(6));
    }

    private void matrixRow(Table t, PdfFont reg, PdfFont bold, String label, int indent,
                           BigDecimal open, BigDecimal close, boolean strong) {
        BigDecimal delta = close.subtract(open);
        PdfFont f = strong ? bold : reg;
        t.addCell(labelCell(f, label, strong, indent));
        t.addCell(numCell(f, open, strong, null));
        t.addCell(numCell(f, delta, strong, deltaColor(delta)));
        t.addCell(numCell(f, close, strong, null));
    }

    private void matrixTotalRow(Table t, PdfFont bold, String label,
                                BigDecimal open, BigDecimal delta, BigDecimal close) {
        Cell lbl = new Cell()
                .add(new Paragraph(label).setFont(bold).setFontColor(new DeviceRgb(255, 255, 255)))
                .setBackgroundColor(GOLD).setBorder(new SolidBorder(LIGHT, 0.5f)).setPadding(4);
        t.addCell(lbl);
        t.addCell(numCell(bold, open, true, null));
        t.addCell(numCell(bold, delta, true, deltaColor(delta)));
        t.addCell(numCell(bold, close, true, new DeviceRgb(184, 146, 62)));
    }

    private DeviceRgb deltaColor(BigDecimal delta) {
        int sign = delta.signum();
        if (sign > 0) return GREEN;
        if (sign < 0) return RED;
        return GREY;
    }

    private Cell labelCell(PdfFont f, String text, boolean strong, int indent) {
        Cell c = new Cell().add(new Paragraph(text == null ? "" : text).setFont(f))
                .setBorder(new SolidBorder(LIGHT, 0.5f))
                .setPaddingLeft(4 + indent * 10f).setPaddingTop(3).setPaddingBottom(3).setPaddingRight(4);
        if (strong) c.setBackgroundColor(LIGHT);
        return c;
    }

    private Cell numCell(PdfFont f, BigDecimal v, boolean strong, DeviceRgb color) {
        String txt = money.format(nz(v)) + " đ";
        Paragraph p = new Paragraph(txt).setFont(f);
        if (color != null) p.setFontColor(color);
        Cell c = new Cell().add(p).setTextAlignment(TextAlignment.RIGHT)
                .setBorder(new SolidBorder(LIGHT, 0.5f)).setPadding(3);
        if (strong) c.setBackgroundColor(LIGHT);
        return c;
    }

    private BigDecimal bankBalanceByName(CashPositionDto p, String name) {
        if (p.getBanks() == null) return BigDecimal.ZERO;
        return p.getBanks().stream()
                .filter(b -> eqName(b.getName(), name))
                .map(b -> nz(b.getBalance()))
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private boolean eqName(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private void flowTable(Document doc, PdfFont reg, PdfFont bold, List<CashflowFlowDto> flows, boolean income) {
        Table t = new Table(UnitValue.createPercentArray(
                income ? new float[]{14, 30, 14, 20, 22} : new float[]{12, 26, 13, 17, 16, 16}))
                .useAllAvailableWidth().setFontSize(8);
        header(t, bold, "Số phiếu"); header(t, bold, "Lý do"); header(t, bold, "Số tiền");
        header(t, bold, "Hình thức"); header(t, bold, "Người tạo");
        if (!income) header(t, bold, "Người duyệt");

        if (flows == null || flows.isEmpty()) {
            Cell c = new Cell(1, income ? 5 : 6).add(new Paragraph("Không có phát sinh"))
                    .setFontColor(GREY).setTextAlignment(TextAlignment.CENTER).setPadding(6);
            c.setBorder(new SolidBorder(LIGHT, 0.5f));
            t.addCell(c);
        } else {
            for (CashflowFlowDto f : flows) {
                cell(t, reg, f.getNumber() != null ? f.getNumber() : f.getVoucherCode());
                cell(t, reg, f.getReason());
                cellRight(t, reg, money.format(nz(f.getAmount())) + " đ");
                cell(t, reg, "BANK_TRANSFER".equals(f.getPaymentType())
                        ? "CK" + (f.getBankName() != null ? " · " + f.getBankName() : "") : "Tiền mặt");
                cell(t, reg, f.getCreatedByName());
                if (!income) cell(t, reg, f.getApprovedByName());
            }
        }
        doc.add(t.setMarginBottom(2));
    }

    private void confirmRow(Document doc, PdfFont reg, PdfFont bold, CashflowConfirmationDto c) {
        boolean ok = Boolean.TRUE.equals(c.getMatched());
        String head = "✔ Đã xác nhận " + DT.format(Instant.ofEpochMilli(c.getConfirmedAt()))
                + " bởi " + c.getConfirmedByName();
        doc.add(new Paragraph(head).setFont(bold).setFontSize(9)
                .setFontColor(ok ? GREEN : RED).setMarginTop(4).setMarginBottom(0));
        String line = ok
                ? "Số tiền khớp hệ thống."
                : "LỆCH — dòng tiền mới: tiền mặt " + money.format(nz(c.getCashCounted())) + " đ. Lý do: " + c.getReason();
        doc.add(new Paragraph(line).setFontSize(8).setFontColor(ok ? GREY : RED).setMarginBottom(4));
    }

    // ── cell helpers ──────────────────────────────────────────────────────────
    private void header(Table t, PdfFont bold, String s) {
        t.addHeaderCell(new Cell().add(new Paragraph(s).setFont(bold).setFontColor(new DeviceRgb(255,255,255)))
                .setBackgroundColor(GOLD).setPadding(4).setBorder(Border.NO_BORDER));
    }
    private void cell(Table t, PdfFont f, String s) {
        t.addCell(new Cell().add(new Paragraph(s == null ? "" : s).setFont(f))
                .setBorder(new SolidBorder(LIGHT, 0.5f)).setPadding(3));
    }
    private void cellRight(Table t, PdfFont f, String s) {
        t.addCell(new Cell().add(new Paragraph(s == null ? "" : s).setFont(f))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(LIGHT, 0.5f)).setPadding(3));
    }

    private BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private PdfFont loadFont(String path) throws Exception {
        ClassPathResource res = new ClassPathResource(path);
        try (InputStream is = res.getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(), PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        }
    }
}
