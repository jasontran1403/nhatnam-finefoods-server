package com.nhatnam.server.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
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
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.nhatnam.server.dto.supply.OfficeSupplyDtos.DetailRowDto;
import com.nhatnam.server.dto.supply.OfficeSupplyDtos.OrderSummaryDto;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XUẤT PHIẾU ĐẶT VĂN PHÒNG PHẨM RA PDF.
 *
 * <p>Đầu vào là {@link OrderSummaryDto} — chính data đang hiển thị trên trang
 * "Danh sách yêu cầu VPP" của Owner. PDF gộp {@code detail} theo TỪNG nhân viên:
 * mỗi nhân viên 1 block gồm họ tên + bộ phận + chức vụ + bảng vật dụng đã yêu cầu
 * (tên, ĐVT, số lượng, ghi chú). Chỉ in yêu cầu ĐANG PENDING (nội dung của
 * summary lấy từ {@code request} chưa đặt) — sau khi bấm "Đặt hàng & xác nhận"
 * dữ liệu sẽ reset về 0, đúng spec "chỉ in yêu cầu gần nhất, không lịch sử".
 *
 * <p>Font sử dụng chung DejaVuSans để render tiếng Việt có dấu — cùng font
 * với các PDF khác trong hệ thống (Voucher, Quotation, Cashflow) để có sẵn
 * trong classpath, không cần add dependency.
 */
@Service
@Log4j2
public class OfficeSupplyOrderPdfService {

    // ── Bảng màu ─────────────────────────────────────────────────────────────
    private static final DeviceRgb GOLD       = new DeviceRgb(201, 168,  76);
    private static final DeviceRgb NAVY       = new DeviceRgb( 26,  39,  68);
    private static final DeviceRgb INK        = new DeviceRgb( 40,  45,  55);
    private static final DeviceRgb INK_SOFT   = new DeviceRgb(105, 110, 120);
    private static final DeviceRgb PAPER_TINT = new DeviceRgb(250, 247, 238);
    private static final DeviceRgb HAIRLINE   = new DeviceRgb(220, 220, 225);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final String COMPANY_NAME =
            "CÔNG TY TNHH SẢN XUẤT THỰC PHẨM TMDV NHẤT NAM";

    /**
     * Sinh PDF byte[] từ tổng hợp yêu cầu VPP. Không throw ra ngoài; nếu lỗi
     * font/IO sẽ log và fallback về file 1 trang thông báo lỗi để controller
     * vẫn có payload trả về (giữ UX rõ ràng thay vì 500).
     */
    public byte[] generate(OrderSummaryDto summary) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(PageSize.A4);

            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(40, 40, 40, 40);

                PdfFont regular = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bold    = loadFont("fonts/DejaVuSans-Bold.ttf");

                // Header
                doc.add(headerBlock(summary, regular, bold));

                // Gom detail theo user (giữ thứ tự BE trả)
                Map<Long, EmployeeGroup> groups = groupByUser(summary);
                if (groups.isEmpty()) {
                    doc.add(new Paragraph("Chưa có phiếu yêu cầu nào.")
                            .setFont(regular).setFontSize(11)
                            .setFontColor(INK_SOFT)
                            .setMarginTop(24));
                } else {
                    int idx = 0;
                    for (EmployeeGroup g : groups.values()) {
                        idx++;
                        doc.add(employeeBlock(idx, g, regular, bold));
                    }
                }

                // Footer chữ ký
                doc.add(signatureBlock(regular, bold));
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[OfficeSupplyOrderPdf] Lỗi sinh PDF: {}", e.getMessage(), e);
            return fallbackErrorPdf(e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Các block bố cục
    // ══════════════════════════════════════════════════════════════════════════

    private Table headerBlock(OrderSummaryDto s, PdfFont regular, PdfFont bold) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{1f}))
                .useAllAvailableWidth();

        t.addCell(cell(new Paragraph(COMPANY_NAME)
                .setFont(bold).setFontSize(11).setFontColor(NAVY)
                .setTextAlignment(TextAlignment.CENTER)));

        t.addCell(cell(new Paragraph("PHIẾU YÊU CẦU VĂN PHÒNG PHẨM")
                .setFont(bold).setFontSize(16).setFontColor(GOLD)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(6).setMarginBottom(2)));

        String meta = "Ngày in: " + LocalDateTime.now().format(TS_FMT)
                + "     ·     Số nhân viên: " + s.getEmployeeCount()
                + "     ·     Số loại vật dụng: "
                + (s.getSummary() == null ? 0 : s.getSummary().size());
        t.addCell(cell(new Paragraph(meta)
                .setFont(regular).setFontSize(9).setFontColor(INK_SOFT)
                .setTextAlignment(TextAlignment.CENTER)));

        t.setMarginBottom(16);
        return t;
    }

    private Table employeeBlock(int idx, EmployeeGroup g, PdfFont regular, PdfFont bold) {
        Table wrapper = new Table(UnitValue.createPercentArray(new float[]{1f}))
                .useAllAvailableWidth()
                .setMarginBottom(12)
                .setBorder(new SolidBorder(HAIRLINE, 0.7f));

        // Header xám cho nhân viên
        Cell headCell = new Cell()
                .setBackgroundColor(PAPER_TINT)
                .setPadding(8f)
                .setBorder(Border.NO_BORDER);
        headCell.add(new Paragraph(idx + ". " + safe(g.fullName))
                .setFont(bold).setFontSize(11).setFontColor(NAVY));
        String subtitle = String.join("  ·  ",
                nonBlankOrDash(g.department, "Bộ phận: "),
                nonBlankOrDash(g.position,   "Chức vụ: "));
        headCell.add(new Paragraph(subtitle)
                .setFont(regular).setFontSize(9).setFontColor(INK_SOFT));
        wrapper.addCell(headCell);

        // Bảng vật dụng
        Table items = new Table(UnitValue.createPercentArray(new float[]{6f, 44f, 16f, 12f, 22f}))
                .useAllAvailableWidth();

        addHeader(items, "#",             bold);
        addHeader(items, "Tên vật dụng",  bold);
        addHeader(items, "ĐVT",           bold);
        addHeader(items, "Số lượng",      bold, TextAlignment.RIGHT);
        addHeader(items, "Ghi chú",       bold);

        int i = 0;
        for (DetailRowDto r : g.rows) {
            i++;
            items.addCell(bodyCell(String.valueOf(i), regular, TextAlignment.CENTER));
            items.addCell(bodyCell(safe(r.getItemName()), regular));
            items.addCell(bodyCell(safe(r.getUnit()), regular));
            items.addCell(bodyCell(fmtQty(r.getQuantity()), bold, TextAlignment.RIGHT));
            items.addCell(bodyCell(safe(r.getNote()), regular));
        }

        Cell tableCell = new Cell().add(items)
                .setBorder(Border.NO_BORDER)
                .setPadding(0f);
        wrapper.addCell(tableCell);

        return wrapper;
    }

    private Table signatureBlock(PdfFont regular, PdfFont bold) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{1f, 1f}))
                .useAllAvailableWidth()
                .setMarginTop(24);

        t.addCell(sigColumn("Người lập phiếu", regular, bold));
        t.addCell(sigColumn("Người duyệt",     regular, bold));

        return t;
    }

    private Cell sigColumn(String label, PdfFont regular, PdfFont bold) {
        Cell c = new Cell()
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.CENTER)
                .setPaddingTop(4f);
        c.add(new Paragraph(label).setFont(bold).setFontSize(10).setFontColor(INK));
        c.add(new Paragraph("(Ký, ghi rõ họ tên)")
                .setFont(regular).setFontSize(8).setFontColor(INK_SOFT));
        c.add(new Paragraph("\n\n\n").setFontSize(10));
        return c;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private Map<Long, EmployeeGroup> groupByUser(OrderSummaryDto s) {
        Map<Long, EmployeeGroup> map = new LinkedHashMap<>();
        List<DetailRowDto> rows = s.getDetail() == null ? List.of() : s.getDetail();
        for (DetailRowDto r : rows) {
            // Fallback về hash tên nếu userId null — hiếm, chỉ xảy ra khi data cũ.
            Long key = r.getUserId() != null ? r.getUserId()
                    : (long) (r.getUserFullName() == null ? 0 : r.getUserFullName().hashCode());
            EmployeeGroup g = map.computeIfAbsent(key, k -> new EmployeeGroup(
                    r.getUserFullName(), r.getUserDepartment(), r.getUserPosition()));
            g.rows.add(r);
        }
        return map;
    }

    private void addHeader(Table t, String label, PdfFont bold) {
        addHeader(t, label, bold, TextAlignment.LEFT);
    }

    private void addHeader(Table t, String label, PdfFont bold, TextAlignment align) {
        Cell c = new Cell()
                .setBackgroundColor(new DeviceRgb(245, 245, 248))
                .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                .setPadding(5f)
                .setTextAlignment(align);
        c.add(new Paragraph(label).setFont(bold).setFontSize(9).setFontColor(INK));
        t.addCell(c);
    }

    private Cell bodyCell(String text, PdfFont font) {
        return bodyCell(text, font, TextAlignment.LEFT);
    }

    private Cell bodyCell(String text, PdfFont font, TextAlignment align) {
        Cell c = new Cell()
                .setBorder(new SolidBorder(HAIRLINE, 0.4f))
                .setPadding(5f)
                .setTextAlignment(align);
        c.add(new Paragraph(text).setFont(font).setFontSize(9.5f).setFontColor(INK));
        return c;
    }

    private Cell cell(Paragraph p) {
        return new Cell().add(p).setBorder(Border.NO_BORDER).setPadding(0f);
    }

    private String safe(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private String nonBlankOrDash(String s, String prefix) {
        return (s == null || s.isBlank()) ? prefix + "—" : prefix + s;
    }

    private String fmtQty(BigDecimal q) {
        if (q == null) return "0";
        // Bỏ zero cuối: "1.00" → "1", "1.50" → "1.5"
        return q.stripTrailingZeros().toPlainString();
    }

    /**
     * Load font iText từ classpath. Fallback về Helvetica nếu file font không
     * đọc được — Helvetica không render tiếng Việt có dấu nhưng vẫn cứu được
     * file khỏi crash toàn bộ; xem lỗi trong log để thay file font.
     */
    private PdfFont loadFont(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(),
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.warn("[OfficeSupplyOrderPdf] Không load được font {}: {}. Fallback Helvetica.",
                    path, e.getMessage());
            try {
                return PdfFontFactory.createFont(
                        com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
    }

    private byte[] fallbackErrorPdf(String errorMessage) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            try (Document doc = new Document(pdfDoc)) {
                doc.add(new Paragraph("Không tạo được PDF phiếu đặt hàng.")
                        .setFontColor(ColorConstants.RED));
                doc.add(new Paragraph("Chi tiết: " + errorMessage));
            }
            return bos.toByteArray();
        } catch (Exception ignore) {
            return new byte[0];
        }
    }

    /** Struct nội bộ giữ 1 nhóm nhân viên. */
    private static final class EmployeeGroup {
        final String fullName;
        final String department;
        final String position;
        final List<DetailRowDto> rows = new ArrayList<>();

        EmployeeGroup(String fullName, String department, String position) {
            this.fullName = fullName;
            this.department = department;
            this.position = position;
        }
    }
}