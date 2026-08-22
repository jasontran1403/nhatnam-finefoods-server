package com.nhatnam.server.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.DoubleBorder;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Báo cáo công nợ theo tuổi nợ (Aged Receivables).
 *
 * Nguồn dữ liệu: các đơn CHỜ THANH TOÁN (status = PENDING_PAYMENT) có
 * paymentStatus ∈ {UNPAID, PARTIAL}. KHÔNG lọc theo paymentMethod — đơn đã giao
 * mà chưa thu đủ tiền đều là công nợ phải thu, bất kể phương thức thanh toán.
 *
 * Quy tắc tính công nợ 1 đơn:
 *   - Chưa thanh toán (UNPAID)  → công nợ = finalAmount (toàn bộ)
 *   - Đã thu một phần (PARTIAL) → công nợ = finalAmount − paidAmount (phần còn lại)
 *   - LÀM TRÒN LÊN (CEILING) công nợ từng đơn TRƯỚC khi cộng tổng, khớp với cột
 *     "Công nợ" ở màn hình khách hàng (AccountantController.unpaidDebtOf).
 *
 * Cột tuổi nợ (tính theo số ngày kể từ createdAt đến ngày xuất báo cáo):
 *   0–30 : 0  ≤ age ≤ 30
 *   31–60: 30 < age ≤ 60
 *   61–90: 60 < age ≤ 90
 *   >90  : age > 90
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class AgedReceivablesReportService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter ROW_DATE_FMT   = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter FOOTER_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
    private static final DateTimeFormatter AS_OF_FMT       = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    // Định dạng tiền: nhóm nghìn bằng dấu phẩy, không phần thập phân → 4,390,258
    private static final NumberFormat MONEY = NumberFormat.getIntegerInstance(Locale.US);

    private static final Collator VN_NAME_CMP;
    static {
        Collator c = Collator.getInstance(new Locale("vi", "VN"));
        c.setStrength(Collator.SECONDARY);   // "an" < "Ăn" < "b", bỏ qua hoa/thường
        VN_NAME_CMP = c;
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final float MARGIN_L = 30f;
    private static final float MARGIN_R = 30f;
    private static final float MARGIN_T = 92f;   // chừa chỗ vẽ tiêu đề 3 dòng ở đầu mỗi trang
    private static final float MARGIN_B = 46f;   // chừa chỗ vẽ footer

    // Customer | Invoice/CM # | Date | 0-30 | 31-60 | 61-90 | >90 | Amount Due
    private static final float[] COL_WIDTHS = {150f, 90f, 55f, 72f, 72f, 72f, 72f, 85f};
    private static final String[] HEADERS = {
            "Customer", "Invoice/CM #", "Date",
            "0-30", "31-60", "61-90", ">90", "Amount Due"
    };

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * @param asOf ngày xuất báo cáo (mặc định = hôm nay theo giờ VN nếu null)
     * @return byte[] file PDF
     */
    @Transactional(readOnly = true)
    public byte[] generatePdf(LocalDate asOf) {
        return generatePdf(asOf, null, null, null, null);
    }

    /**
     * Xuất báo cáo, chỉ gồm những khách hàng KHỚP bộ lọc đang hiển thị ở màn hình
     * khách hàng (giống hệt {@code searchAdmin}). Bỏ trống hết = toàn bộ (như cũ).
     *
     * @param q        từ khoá tìm kiếm (tên/điện thoại/email/mã KH...)
     * @param type     loại khách (COMPANY/INDIVIDUAL) — null = mọi loại
     * @param isActive lọc trạng thái hoạt động — null = mọi trạng thái
     * @param sellerId lọc theo nhân viên phụ trách (0 = chưa gán) — null = mọi seller
     */
    @Transactional(readOnly = true)
    public byte[] generatePdf(LocalDate asOf, String q, Customer.CustomerType type,
                              Boolean isActive, Long sellerId) {
        return generatePdf(asOf, q, type, isActive, sellerId, null);
    }

    /**
     * Như trên, nhưng cho phép CHỌN TRỰC TIẾP danh sách khách hàng cần đưa vào báo cáo
     * (modal "Chọn khách hàng" ở màn hình ACCOUNTANT / SUPER_ACCOUNTANT).
     *
     * @param customerIds danh sách ID khách hàng được chọn. Nếu khác null và không rỗng
     *                    → CHỈ xuất đúng các khách này, bỏ qua q/type/isActive/sellerId.
     *                    Nếu null/rỗng → giữ nguyên hành vi cũ (lọc theo bộ lọc màn hình).
     */
    @Transactional(readOnly = true)
    public byte[] generatePdf(LocalDate asOf, String q, Customer.CustomerType type,
                              Boolean isActive, Long sellerId, Collection<Long> customerIds) {
        final LocalDate reportDate = (asOf != null) ? asOf : LocalDate.now(VN);
        final Instant   exportedAt = Instant.now();

        Set<Long> allowedCustomerIds = resolveAllowedCustomerIds(q, type, isActive, sellerId, customerIds);
        List<CustomerGroup> groups = buildModel(reportDate, allowedCustomerIds);

        PdfFont fontReg  = loadFont("fonts/DejaVuSans.ttf");
        PdfFont fontBold = loadFont("fonts/DejaVuSans-Bold.ttf");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
        PageSize portrait = new PageSize(PageSize.A4); // khổ dọc

        // immediateFlush=false: giữ các trang trong bộ nhớ để vẽ tiêu đề/footer sau khi layout xong
        Document doc = new Document(pdfDoc, portrait, false);
        doc.setMargins(MARGIN_T, MARGIN_R, MARGIN_B, MARGIN_L);
        doc.setFont(fontReg).setFontSize(8f);

        // ── Bảng dữ liệu (header lặp lại mỗi trang) ─────────────────────────
        Table table = new Table(UnitValue.createPointArray(COL_WIDTHS))
                .setWidth(UnitValue.createPercentValue(100));

        for (int i = 0; i < HEADERS.length; i++) {
            boolean money = i >= 3;
            table.addHeaderCell(headerCell(HEADERS[i], fontBold, money ? TextAlignment.RIGHT : TextAlignment.LEFT));
        }

        if (groups.isEmpty()) {
            Cell empty = new Cell(1, COL_WIDTHS.length)
                    .add(new Paragraph("Không có công nợ tại thời điểm báo cáo.").setFont(fontReg))
                    .setBorder(Border.NO_BORDER)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setPaddingTop(20f);
            table.addCell(empty);
        }

        for (CustomerGroup g : groups) {
            // Các dòng hoá đơn — lặp tên trên từng dòng (giống mẫu)
            for (Row r : g.rows) {
                table.addCell(textCell(g.name, fontReg, TextAlignment.LEFT));
                table.addCell(textCell(r.orderCode, fontReg, TextAlignment.LEFT));
                table.addCell(textCell(r.date, fontReg, TextAlignment.LEFT));
                for (int b = 0; b < 4; b++) {
                    table.addCell(moneyCell(r.bucket == b ? r.amount : 0L, fontReg));
                }
                table.addCell(moneyCell(r.amount, fontReg));
            }

            // Dòng trống 1
            addBlankRow(table);

            // Dòng tổng của khách hàng
            // 3 cột trái (tên, mã đơn, ngày): KHÔNG border
            table.addCell(subtotalCell(g.name, fontBold, TextAlignment.LEFT, false));
            table.addCell(subtotalCell("", fontBold, TextAlignment.LEFT, false));
            table.addCell(subtotalCell("", fontBold, TextAlignment.LEFT, false));
            // Nửa phải (0-30 → Amount Due): border trên (hết data) + border dưới (hết dòng tổng)
            for (int b = 0; b < 4; b++) {
                table.addCell(subtotalMoneyCell(g.bucketTotals[b], fontBold));
            }
            table.addCell(subtotalMoneyCell(g.amountDueTotal, fontBold));

            // Dòng trống 2
            addBlankRow(table);
        }

        // ── DÒNG TỔNG CỘNG CUỐI CÙNG (tất cả khách hàng) ────────────────────
        // Cộng dồn từ DÒNG TỔNG của từng khách (mỗi khách đúng 1 lần) — KHÔNG cộng lại
        // các dòng hoá đơn chi tiết, nên không bị tính trùng.
        if (!groups.isEmpty()) {
            long[] grandBuckets = new long[4];
            long grandAmountDue = 0L;
            for (CustomerGroup g : groups) {
                for (int b = 0; b < 4; b++) grandBuckets[b] += g.bucketTotals[b];
                grandAmountDue += g.amountDueTotal;   // = tổng của các dòng "tổng cộng" mỗi khách
            }

            // 3 cột trái: nhãn "TOTAL", không border
            table.addCell(grandTotalCell("TOTAL", fontBold, TextAlignment.LEFT, false));
            table.addCell(grandTotalCell("", fontBold, TextAlignment.LEFT, false));
            table.addCell(grandTotalCell("", fontBold, TextAlignment.LEFT, false));
            // Nửa phải: kẻ double-line (border trên + dưới dày hơn) như mẫu báo cáo kế toán
            for (int b = 0; b < 4; b++) {
                table.addCell(grandTotalMoneyCell(grandBuckets[b], fontBold));
            }
            table.addCell(grandTotalMoneyCell(grandAmountDue, fontBold));
        }

        doc.add(table);

        // ── Vẽ tiêu đề + footer trên MỌI trang (post-process) ───────────────
        String titleCompany = "Nhat Nam Finefoods " + reportDate.getYear();
        String titleAsOf    = "As of " + reportDate.format(AS_OF_FMT);
        String footerLeft   = FOOTER_TIME_FMT.format(exportedAt.atZone(VN));
        int totalPages = pdfDoc.getNumberOfPages();

        for (int p = 1; p <= totalPages; p++) {
            PdfPage   page = pdfDoc.getPage(p);
            Rectangle size = page.getPageSize();
            PdfCanvas pc   = new PdfCanvas(page);
            Canvas    cv   = new Canvas(pc, size);

            float centerX = (size.getLeft() + size.getRight()) / 2f;
            float top     = size.getTop();

            // Tiêu đề (căn giữa, đầu trang)
            cv.showTextAligned(new Paragraph(titleCompany).setFont(fontBold).setFontSize(11f),
                    centerX, top - 24f, TextAlignment.CENTER);
            cv.showTextAligned(new Paragraph("Aged Receivables").setFont(fontBold).setFontSize(11f),
                    centerX, top - 40f, TextAlignment.CENTER);
            cv.showTextAligned(new Paragraph(titleAsOf).setFont(fontReg).setFontSize(9f),
                    centerX, top - 55f, TextAlignment.CENTER);

            // Footer: trái = thời điểm xuất, phải = số trang
            cv.showTextAligned(new Paragraph(footerLeft).setFont(fontReg).setFontSize(8f),
                    size.getLeft() + MARGIN_L, 26f, TextAlignment.LEFT);
            cv.showTextAligned(new Paragraph("Page " + p + " of " + totalPages).setFont(fontReg).setFontSize(8f),
                    size.getRight() - MARGIN_R, 26f, TextAlignment.RIGHT);

            cv.close();
        }

        doc.close();
        return bos.toByteArray();
    }

    // ════════════════════════════════════════════════════════════════════════
    // BUILD MODEL
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Trả về tập ID khách hàng khớp bộ lọc (giống {@code searchAdmin}).
     * Nếu KHÔNG có bất kỳ điều kiện lọc nào → trả {@code null} (nghĩa là lấy toàn bộ, như cũ).
     */
    private Set<Long> resolveAllowedCustomerIds(String q, Customer.CustomerType type,
                                                Boolean isActive, Long sellerId,
                                                Collection<Long> customerIds) {
        // Ưu tiên tuyệt đối cho danh sách khách hàng người dùng CHỌN TAY ở modal.
        if (customerIds != null && !customerIds.isEmpty()) {
            Set<Long> picked = new LinkedHashSet<>();
            for (Long id : customerIds) if (id != null) picked.add(id);
            if (!picked.isEmpty()) return picked;
        }

        boolean hasFilter = (q != null && !q.isBlank()) || type != null || isActive != null || sellerId != null;
        if (!hasFilter) return null;

        String qNorm = (q != null && !q.isBlank()) ? q.trim() : null;
        Set<Long> ids = new HashSet<>();
        customerRepository.searchAdmin(qNorm, type, isActive, sellerId, Pageable.unpaged())
                .forEach(c -> { if (c.getId() != null) ids.add(c.getId()); });
        return ids;
    }

    private List<CustomerGroup> buildModel(LocalDate reportDate) {
        return buildModel(reportDate, null);
    }

    private List<CustomerGroup> buildModel(LocalDate reportDate, Set<Long> allowedCustomerIds) {
        // Nguồn dữ liệu: đơn CHỜ THANH TOÁN (PENDING_PAYMENT) có paymentStatus ∈ {UNPAID, PARTIAL}
        // — KHÔNG phụ thuộc paymentMethod. Dùng chung định nghĩa với cột "Công nợ" ở màn hình
        // khách hàng (AccountantController.unpaidDebtOf) để hai nơi luôn khớp số.
        List<Order> orders = orderRepository.findPendingPaymentReceivables();

        // key nhóm → group
        Map<String, CustomerGroup> map = new LinkedHashMap<>();

        for (Order o : orders) {
            // Lọc theo bộ tìm kiếm KH đang hiển thị (null = không lọc, lấy tất cả)
            if (allowedCustomerIds != null) {
                Customer oc = o.getCustomer();
                if (oc == null || oc.getId() == null || !allowedCustomerIds.contains(oc.getId())) continue;
            }

            // Công nợ 1 đơn:
            //   UNPAID  → toàn bộ finalAmount
            //   PARTIAL → phần còn lại = finalAmount − paidAmount
            // Làm tròn LÊN (CEILING) từng đơn TRƯỚC khi cộng — giống unpaidDebtOf ở màn hình KH.
            BigDecimal fin  = o.getFinalAmount() != null ? o.getFinalAmount() : BigDecimal.ZERO;
            BigDecimal paid = o.getPaidAmount()  != null ? o.getPaidAmount()  : BigDecimal.ZERO;
            BigDecimal amt  = (o.getPaymentStatus() == PaymentStatus.PARTIAL) ? fin.subtract(paid) : fin;
            if (amt.signum() <= 0) continue;
            long debt = amt.setScale(0, RoundingMode.CEILING).longValue();
            if (debt <= 0) continue;

            // Tuổi nợ theo ngày từ createdAt → reportDate
            Long createdAtMs = o.getCreatedAt();
            if (createdAtMs == null) continue;
            LocalDate orderDate = Instant.ofEpochMilli(createdAtMs).atZone(VN).toLocalDate();
            long age = ChronoUnit.DAYS.between(orderDate, reportDate);
            if (age < 0) continue; // đơn tạo sau ngày báo cáo → bỏ

            int bucket = (age <= 30) ? 0 : (age <= 60) ? 1 : (age <= 90) ? 2 : 3;

            Customer c = o.getCustomer();
            String code = (c != null && c.getCustomerCode() != null) ? c.getCustomerCode() : "";
            String name = resolveName(o, c);
            String key  = (c != null) ? ("C" + c.getId()) : ("N:" + name);

            CustomerGroup g = map.computeIfAbsent(key, k -> new CustomerGroup(code, name));
            g.rows.add(new Row(
                    o.getOrderCode(),
                    orderDate.format(ROW_DATE_FMT),
                    createdAtMs,
                    bucket,
                    debt));
            g.bucketTotals[bucket] += debt;
            g.amountDueTotal       += debt;
        }

        List<CustomerGroup> groups = new ArrayList<>(map.values());

        // Sắp xếp: theo TÊN khách hàng tăng dần (tên rỗng xuống cuối), rồi theo mã KH
        groups.sort(Comparator
                .comparing((CustomerGroup g) -> g.name.isBlank())          // tên rỗng xuống cuối
                .thenComparing(g -> g.name, VN_NAME_CMP)                   // A→Z, chuẩn tiếng Việt
                .thenComparing(g -> g.code));

        // Trong mỗi nhóm: sắp hoá đơn theo ngày tăng dần
        for (CustomerGroup g : groups) {
            g.rows.sort(Comparator.comparingLong(r -> r.createdAt)); // theo thời gian đặt hàng tăng dần
        }
        return groups;
    }

    /** Khách công ty → tên công ty; khách lẻ → tên khách. Fallback snapshot trên đơn. */
    private String resolveName(Order o, Customer c) {
        if (c != null) {
            if (c.getCustomerType() == Customer.CustomerType.COMPANY
                    && c.getCompanyName() != null && !c.getCompanyName().isBlank()) {
                return c.getCompanyName();
            }
            if (c.getName() != null && !c.getName().isBlank()) return c.getName();
        }
        return o.getCustomerName() != null ? o.getCustomerName() : "";
    }

    // ════════════════════════════════════════════════════════════════════════
    // CELL HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private Cell headerCell(String text, PdfFont font, TextAlignment align) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(8f))
                .setTextAlignment(align)
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(ColorConstants.BLACK, 1f))
                .setPaddingBottom(2f);
    }

    private Cell textCell(String text, PdfFont font, TextAlignment align) {
        return new Cell()
                .add(new Paragraph(text == null ? "" : text).setFont(font).setFontSize(8f))
                .setTextAlignment(align)
                .setBorder(Border.NO_BORDER)
                .setPaddingTop(1f).setPaddingBottom(1f);
    }

    private Cell moneyCell(long value, PdfFont font) {
        // Ô trống nếu = 0 (giống mẫu)
        String s = (value == 0L) ? "" : MONEY.format(value);
        return textCell(s, font, TextAlignment.RIGHT);
    }

    /**
     * @param bordered true → kẻ border trên (hết data khách) + border dưới (hết dòng tổng);
     *                 false → không border (dùng cho 3 cột trái).
     *                 Chỉ nửa phải bảng (0-30 → Amount Due) mới bordered = true.
     */
    private Cell subtotalCell(String text, PdfFont font, TextAlignment align, boolean bordered) {
        Cell cell = new Cell()
                .add(new Paragraph(text == null ? "" : text).setFont(font).setFontSize(8f))
                .setTextAlignment(align)
                .setBorder(Border.NO_BORDER)
                .setPaddingTop(2f).setPaddingBottom(2f);
        if (bordered) {
            cell.setBorderTop(new SolidBorder(ColorConstants.BLACK, 0.75f))
                    .setBorderBottom(new SolidBorder(ColorConstants.BLACK, 0.75f));
        }
        return cell;
    }

    private Cell subtotalMoneyCell(long value, PdfFont font) {
        String s = (value == 0L) ? "" : MONEY.format(value);
        return subtotalCell(s, font, TextAlignment.RIGHT, true);
    }

    /** Ô cho DÒNG TỔNG CỘNG CUỐI (tất cả khách hàng) — border dày hơn dòng tổng từng khách. */
    private Cell grandTotalCell(String text, PdfFont font, TextAlignment align, boolean bordered) {
        Cell cell = new Cell()
                .add(new Paragraph(text == null ? "" : text).setFont(font).setFontSize(8.5f))
                .setTextAlignment(align)
                .setBorder(Border.NO_BORDER)
                .setPaddingTop(4f).setPaddingBottom(4f);
        if (bordered) {
            cell.setBorderTop(new SolidBorder(ColorConstants.BLACK, 1f))
                    .setBorderBottom(new DoubleBorder(ColorConstants.BLACK, 1.5f));
        }
        return cell;
    }

    private Cell grandTotalMoneyCell(long value, PdfFont font) {
        String s = (value == 0L) ? "" : MONEY.format(value);
        return grandTotalCell(s, font, TextAlignment.RIGHT, true);
    }

    private void addBlankRow(Table table) {
        for (int i = 0; i < COL_WIDTHS.length; i++) {
            table.addCell(new Cell()
                    .add(new Paragraph("\u00A0").setFontSize(6f))
                    .setBorder(Border.NO_BORDER)
                    .setPadding(0f)          // bỏ padding để dòng mỏng
                    .setMargin(0f));         // không set height cố định nữa
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FONT
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
            log.warn("[AgedReceivables] Không load được font {}: {}. Fallback Helvetica.", path, e.getMessage());
            try {
                return PdfFontFactory.createFont(
                        com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MODEL
    // ════════════════════════════════════════════════════════════════════════

    private static final class CustomerGroup {
        final String code;
        final String name;
        final List<Row> rows = new ArrayList<>();
        final long[] bucketTotals = new long[4]; // 0-30, 31-60, 61-90, >90
        long amountDueTotal = 0L;

        CustomerGroup(String code, String name) {
            this.code = code == null ? "" : code;
            this.name = name == null ? "" : name;
        }
    }

    /** Một dòng hoá đơn công nợ. */
    private static final class Row {
        final String orderCode;
        final String date;      // dd/MM/yy (hiển thị)
        final long   createdAt; // để sắp xếp theo thời gian thật
        final int    bucket;    // 0..3
        final long   amount;    // đã làm tròn

        Row(String orderCode, String date, long createdAt, int bucket, long amount) {
            this.orderCode = orderCode == null ? "" : orderCode;
            this.date = date;
            this.createdAt = createdAt;
            this.bucket = bucket;
            this.amount = amount;
        }
    }
}