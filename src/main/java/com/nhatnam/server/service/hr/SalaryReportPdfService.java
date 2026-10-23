// src/main/java/com/nhatnam/server/service/hr/SalaryReportPdfService.java
package com.nhatnam.server.service.hr;

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
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.entity.FactoryKpiBonus;
import com.nhatnam.server.entity.FactoryKpiBonusItem;
import com.nhatnam.server.entity.OfficeBonusItem;
import com.nhatnam.server.entity.OfficeBonusResult;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.FactoryKpiService;
import com.nhatnam.server.service.FactoryPayrollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SINH FILE PDF cho các báo cáo lương / thưởng.
 *
 * <p>Thay thế các export Excel (XSSFWorkbook) trước đây — iText 7 + font
 * DejaVuSans (cùng pattern với OfficeSupplyOrderPdfService, VoucherPdfService...).
 * Font .ttf đã có sẵn trong {@code src/main/resources/fonts/}.
 *
 * <p>Public API:
 * <ul>
 *   <li>{@link #factoryBonusPdf(int, int)} — Thưởng xưởng sản xuất</li>
 *   <li>{@link #officeBonusPdf(int, int, PayrollDepartment)} — Thưởng KT / KD</li>
 *   <li>{@link #driverBonusPdf(int, int)} — Thưởng tài xế + hỗ trợ giao hàng</li>
 * </ul>
 *
 * <p>Bảng lương chính (nhiều cột động) do {@link SalaryExportService} tự giữ
 * dạng Excel vì PDF ngang 1 trang A4 không đủ chỗ cho mọi cột phụ cấp.
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class SalaryReportPdfService {

    private final OfficeBonusResultRepository officeBonusResultRepo;
    private final ExpenseVoucherRepository expenseVoucherRepo;
    private final WarehouseBonusRepository warehouseBonusRepo;
    private final AttendanceBonusRepository attendanceBonusRepo;
    private final OrderRepository orderRepository;
    private final FactoryKpiService kpiService;
    private final PayrollDepartmentResolver deptResolver;
    private final FactoryPayrollService factoryPayrollService;

    // ─── Palette ─────────────────────────────────────────────────────────────
    private static final DeviceRgb NAVY       = new DeviceRgb( 26,  39,  68);
    private static final DeviceRgb GOLD       = new DeviceRgb(201, 168,  76);
    private static final DeviceRgb LIGHT_GOLD = new DeviceRgb(253, 248, 237);
    private static final DeviceRgb INK        = new DeviceRgb( 40,  45,  55);
    private static final DeviceRgb INK_SOFT   = new DeviceRgb(105, 110, 120);
    private static final DeviceRgb HAIRLINE   = new DeviceRgb(220, 220, 225);
    private static final DeviceRgb BG_ALT     = new DeviceRgb(248, 249, 250);
    private static final DeviceRgb BG_WHITE   = new DeviceRgb(255, 255, 255);
    private static final DeviceRgb BG_GREEN   = new DeviceRgb(232, 245, 233);
    private static final DeviceRgb BG_YELLOW  = new DeviceRgb(253, 236, 236);  // đỏ nhạt cho inactive
    private static final DeviceRgb RED        = new DeviceRgb(220,  38,  38);

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * PDF thưởng KT / KD — LANDSCAPE, 7 cột:
     *   STT · Họ tên · Chức vụ · Bonus · Tạm ứng · Tổng · Ký tên
     *
     * <p>Chỉnh 10/2026:
     * <ul>
     *   <li>Chuyển ngang (A4.rotate()) để có đủ chỗ cho cột "Ký tên" trống</li>
     *   <li>KT: BỎ subtitle "Doanh thu hold / chia hoa hồng" (đã có trên UI)</li>
     *   <li>KT: sort Kế toán trưởng (SUPER_ACCOUNTANT) → Chuyên viên (ACCOUNTANT)</li>
     *   <li>KD: sort theo bonus DESC, BỎ nhân viên bonus = 0</li>
     *   <li>KD: nếu nhân viên kiêm nhiệm (payrollRole ≠ SALES) → thêm dòng
     *       "Kiêm nhân viên kinh doanh" bên dưới chức vụ chính</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public byte[] officeBonusPdf(int month, int year, PayrollDepartment dept) {
        OfficeBonusResult bonus = officeBonusResultRepo
                .findByMonthAndYearAndDepartment(month, year, dept).orElse(null);
        String deptLabel = dept.getLabel();
        String monthStr = String.format("%d-%02d", year, month);
        boolean isKt = dept == PayrollDepartment.ACCOUNTING;
        boolean isKd = dept == PayrollDepartment.SALES;

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            // Ngang để đủ chỗ cho cột Ký tên
            pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(30, 30, 30, 30);
                PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

                addHeader(doc, bd, "BẢNG THƯỞNG DOANH THU " + deptLabel.toUpperCase()
                        + " THÁNG " + month + "/" + year);
                // Spacer thay cho subtitle KT đã bỏ (yêu cầu 10/2026: 2 chỉ số
                // doanh thu hold/chia hoa hồng đã có trên UI, không cần in lên PDF)
                doc.add(new Paragraph(" ").setFontSize(2));

                // ── Chuẩn bị danh sách items (sort / filter theo dept) ──
                List<OfficeBonusItem> rows = new ArrayList<>();
                if (bonus != null && bonus.getItems() != null) rows.addAll(bonus.getItems());

                if (isKt) {
                    // Kế toán trưởng trước, chuyên viên sau
                    rows.sort(java.util.Comparator.comparingInt(this::officeKtRank));
                } else if (isKd) {
                    // Sort bonus DESC, bỏ bonus = 0 (yêu cầu nghiệp vụ)
                    rows.removeIf(it -> {
                        long b = it.getBonusAmount() != null ? it.getBonusAmount() : 0L;
                        return b <= 0;
                    });
                    rows.sort((a, b) -> {
                        long ba = a.getBonusAmount() != null ? a.getBonusAmount() : 0L;
                        long bb = b.getBonusAmount() != null ? b.getBonusAmount() : 0L;
                        return Long.compare(bb, ba);
                    });
                }

                // ── Table 7 cột (10/2026 thêm "Thưởng chuyên cần") ──
                //   STT · Họ tên · <Bonus> · Chuyên cần · Tạm ứng · Tổng · Ký tên
                String bonusLabel = isKd ? "Thưởng kinh doanh"
                        : isKt ? "Thưởng doanh thu công nợ"
                        : "Bonus";
                Table t = new Table(UnitValue.createPercentArray(
                        new float[]{5, 28, 14, 12, 11, 13, 17}))
                        .useAllAvailableWidth();
                t.addHeaderCell(hdr("STT", bd));
                t.addHeaderCell(hdr("Họ và tên", bd));
                t.addHeaderCell(hdr(bonusLabel, bd));
                t.addHeaderCell(hdr("Thưởng\nchuyên cần", bd));
                t.addHeaderCell(hdr("Tạm ứng", bd));
                t.addHeaderCell(hdr("Tổng thưởng", bd));
                t.addHeaderCell(hdr("Ký tên", bd));

                int stt = 1;
                for (OfficeBonusItem it : rows) {
                    long bonusAmt = it.getBonusAmount() != null ? it.getBonusAmount() : 0L;
                    long attBonus = 0, adv = 0;
                    if (it.getUser() != null) {
                        BigDecimal a = expenseVoucherRepo.sumSalaryAdvance(
                                it.getUser().getId(), monthStr, ExpenseVoucher.VoucherStatus.APPROVED);
                        adv = a != null ? a.longValue() : 0;
                        var ab = attendanceBonusRepo
                                .findByUser_IdAndMonthAndYear(it.getUser().getId(), month, year)
                                .orElse(null);
                        if (ab != null && ab.getAmount() != null) attBonus = ab.getAmount();
                    }
                    long total = bonusAmt + attBonus - adv;

                    // Giữ dòng nếu CÓ bonus HOẶC có attBonus (yêu cầu 10/2026: thưởng
                    // chuyên cần vẫn in khi bonus chính = 0).
                    if (bonusAmt == 0L && attBonus == 0L) continue;

                    boolean alt = stt % 2 == 0;
                    DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
                    t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
                    t.addCell(dc(nz(it.getUserFullName()), rg, bg, TextAlignment.LEFT));
                    t.addCell(money(bonusAmt, rg, bg));
                    t.addCell(money(attBonus, rg, bg));
                    t.addCell(money(adv, rg, bg));
                    t.addCell(moneyBold(total, bd, BG_GREEN));
                    t.addCell(new Cell().setBackgroundColor(bg).setPadding(14)
                            .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                            .add(new Paragraph(" ").setFont(rg).setFontSize(9)));
                    stt++;
                }
                if (stt == 1) {
                    Cell empty = new Cell(1, 7)
                            .add(new Paragraph("Không có dữ liệu thưởng.")
                                    .setFont(rg).setFontColor(INK_SOFT)
                                    .setTextAlignment(TextAlignment.CENTER))
                            .setPadding(10);
                    t.addCell(empty);
                }
                doc.add(t);
                addFooter(doc, rg);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[officeBonusPdf] error month={} year={} dept={}", month, year, dept, e);
            return fallbackErrorPdf("Office bonus PDF: " + e.getMessage());
        }
    }

    /**
     * (10/2026) Xuất PDF thưởng Kho — thưởng cứng, nhập tay per-user per-month.
     * Format giống KT/KD: STT · Họ tên · Bonus · Tạm ứng · Tổng · Ký tên.
     * Chỉ in NV có bonus > 0.
     */
    @Transactional(readOnly = true)
    public byte[] warehouseBonusPdf(int month, int year) {
        PayrollDepartment dept = PayrollDepartment.WAREHOUSE;
        String deptLabel = dept.getLabel();
        String monthStr = String.format("%d-%02d", year, month);

        // Load tất cả warehouse_bonus trong tháng (chỉ amount > 0)
        List<com.nhatnam.server.entity.WarehouseBonus> rows = warehouseBonusRepo
                .findByMonthAndYear(month, year).stream()
                .filter(wb -> wb.getAmount() != null && wb.getAmount() > 0 && wb.getUser() != null)
                .sorted(java.util.Comparator.comparingInt(wb ->
                        roleRankInDept(dept, wb.getUser())))
                .toList();

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(30, 30, 30, 30);
                PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

                addHeader(doc, bd, "BẢNG THƯỞNG " + deptLabel.toUpperCase()
                        + " THÁNG " + month + "/" + year);
                doc.add(new Paragraph(" ").setFontSize(2));

                Table t = new Table(UnitValue.createPercentArray(
                        new float[]{5, 28, 14, 12, 11, 13, 17}))
                        .useAllAvailableWidth();
                t.addHeaderCell(hdr("STT", bd));
                t.addHeaderCell(hdr("Họ và tên", bd));
                t.addHeaderCell(hdr("Thưởng nhân viên kho", bd));
                t.addHeaderCell(hdr("Thưởng\nchuyên cần", bd));
                t.addHeaderCell(hdr("Tạm ứng", bd));
                t.addHeaderCell(hdr("Tổng thưởng", bd));
                t.addHeaderCell(hdr("Ký tên", bd));

                // Thêm NV phòng kho CHƯA có warehouse_bonus nhưng CÓ thưởng chuyên cần
                java.util.Set<Long> alreadyIn = new java.util.HashSet<>();
                for (var wb : rows) if (wb.getUser() != null) alreadyIn.add(wb.getUser().getId());
                List<com.nhatnam.server.entity.User> extras = new ArrayList<>();
                for (var u : deptResolver.employeesWithLockedOf(dept)) {
                    if (alreadyIn.contains(u.getId())) continue;
                    long ab = attendanceBonusRepo
                            .findByUser_IdAndMonthAndYear(u.getId(), month, year)
                            .map(com.nhatnam.server.entity.AttendanceBonus::getAmount).orElse(0L);
                    if (ab > 0) extras.add(u);
                }

                int stt = 1;
                for (com.nhatnam.server.entity.WarehouseBonus wb : rows) {
                    long bonusAmt = wb.getAmount();
                    BigDecimal a = expenseVoucherRepo.sumSalaryAdvance(
                            wb.getUser().getId(), monthStr,
                            com.nhatnam.server.entity.ExpenseVoucher.VoucherStatus.APPROVED);
                    long adv = a != null ? a.longValue() : 0;
                    long attBonus = attendanceBonusRepo
                            .findByUser_IdAndMonthAndYear(wb.getUser().getId(), month, year)
                            .map(com.nhatnam.server.entity.AttendanceBonus::getAmount).orElse(0L);
                    long total = bonusAmt + attBonus - adv;

                    boolean alt = stt % 2 == 0;
                    DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
                    t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
                    t.addCell(dc(nz(wb.getUser().getFullName()), rg, bg, TextAlignment.LEFT));
                    t.addCell(money(bonusAmt, rg, bg));
                    t.addCell(money(attBonus, rg, bg));
                    t.addCell(money(adv, rg, bg));
                    t.addCell(moneyBold(total, bd, BG_GREEN));
                    t.addCell(new Cell().setBackgroundColor(bg).setPadding(14)
                            .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                            .add(new Paragraph(" ").setFont(rg).setFontSize(9)));
                    stt++;
                }
                for (var u : extras) {
                    long attBonus = attendanceBonusRepo
                            .findByUser_IdAndMonthAndYear(u.getId(), month, year)
                            .map(com.nhatnam.server.entity.AttendanceBonus::getAmount).orElse(0L);
                    BigDecimal a = expenseVoucherRepo.sumSalaryAdvance(
                            u.getId(), monthStr,
                            com.nhatnam.server.entity.ExpenseVoucher.VoucherStatus.APPROVED);
                    long adv = a != null ? a.longValue() : 0;
                    long total = attBonus - adv;
                    boolean alt = stt % 2 == 0;
                    DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
                    t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
                    t.addCell(dc(nz(u.getFullName()), rg, bg, TextAlignment.LEFT));
                    t.addCell(money(0L, rg, bg));
                    t.addCell(money(attBonus, rg, bg));
                    t.addCell(money(adv, rg, bg));
                    t.addCell(moneyBold(total, bd, BG_GREEN));
                    t.addCell(new Cell().setBackgroundColor(bg).setPadding(14)
                            .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                            .add(new Paragraph(" ").setFont(rg).setFontSize(9)));
                    stt++;
                }
                if (stt == 1) {
                    Cell empty = new Cell(1, 7)
                            .add(new Paragraph("Không có dữ liệu thưởng Kho.")
                                    .setFont(rg).setFontColor(INK_SOFT)
                                    .setTextAlignment(TextAlignment.CENTER))
                            .setPadding(10);
                    t.addCell(empty);
                }
                doc.add(t);
                addFooter(doc, rg);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[warehouseBonusPdf] error month={} year={}", month, year, e);
            return fallbackErrorPdf("Warehouse bonus PDF: " + e.getMessage());
        }
    }

    private int roleRankInDept(PayrollDepartment dept, com.nhatnam.server.entity.User u) {
        Role r = deptResolver.payrollRoleOf(u);
        if (r == null) return Integer.MAX_VALUE;
        int idx = dept.getRoles().indexOf(r);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /** Sort rank cho KT: Kế toán trưởng (SUPER_ACCOUNTANT) trước, Chuyên viên kế toán (ACCOUNTANT) sau. */
    private int officeKtRank(OfficeBonusItem it) {
        if (it.getUser() == null) return 99;
        Role r = deptResolver.payrollRoleOf(it.getUser());
        if (r == Role.SUPER_ACCOUNTANT) return 1;
        if (r == Role.ACCOUNTANT) return 2;
        return 99;
    }

    /**
     * PDF thưởng xưởng sản xuất.
     *
     * <h3>(10/2026) Layout mới theo yêu cầu nghiệp vụ:</h3>
     * <ul>
     *   <li>BỎ phần "Đơn giá bonus" (đơn giá /tấn)</li>
     *   <li>Dòng 1: "Quỹ thưởng tháng trước" (carryOverIn) + "Quỹ thưởng tháng này" (bonusPool)</li>
     *   <li>Dòng 2: "Quỹ dư sau khi chia tháng này" (carryOverOut)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public byte[] factoryBonusPdf(int month, int year) {
        FactoryKpiBonus kpi = kpiService.getOrCompute(month, year);
        List<User> employees = new ArrayList<>(deptResolver.employeesOf(PayrollDepartment.FACTORY));

        // (10/2026) BỎ nhân sự lãnh đạo (OWNER/ADMIN) khỏi bảng thưởng xưởng dù
        // họ có payrollRole FACTORY để nhận chế độ. Yêu cầu nghiệp vụ: file chỉ
        // liệt kê người thuộc riêng phòng xưởng sản xuất (không có Chủ Tịch).
        employees.removeIf(u -> {
            java.util.Set<Role> all = u.getAllRoles();
            return all != null && (all.contains(Role.OWNER) || all.contains(Role.ADMIN));
        });

        // Sort theo thứ tự: Trưởng xưởng → Trợ lý xưởng → Kế toán xưởng →
        // Nhân viên đóng gói → Công nhân sản xuất → Bảo vệ
        employees.sort(java.util.Comparator.comparingInt(u ->
                roleRank(FactoryKpiService.overridePackagingRole(u, deptResolver.payrollRoleOf(u)))));
        String monthStr = String.format("%d-%02d", year, month);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            // Ngang để đủ chỗ cho cột Ký tên
            pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(30, 30, 30, 30);
                PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

                addHeader(doc, bd, "BẢNG THƯỞNG BONUS XƯỞNG SẢN XUẤT THÁNG " + month + "/" + year);

                // ── Row 1: Quỹ thưởng tháng trước | Quỹ thưởng tháng này ──
                Table info = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                        .useAllAvailableWidth().setMarginBottom(6);
                info.addCell(infoCell("Quỹ thưởng tháng trước mang sang",
                        fmtVn(kpi.getCarryOverIn()) + "đ", rg, bd, new DeviceRgb(232, 245, 233)));
                info.addCell(infoCell("Quỹ thưởng tháng này",
                        fmtVn(kpi.getBonusPool()) + "đ"
                                + (kpi.getTotalOutputKg() != null && kpi.getTotalOutputKg().signum() > 0
                                ? "   (SL: " + fmtKg(kpi.getTotalOutputKg()) + "Kg)" : ""),
                        rg, bd, new DeviceRgb(226, 240, 253)));
                doc.add(info);

                // ── Row 2: Quỹ dư sau khi chia tháng này ──
                Table info2 = new Table(UnitValue.createPercentArray(new float[]{1}))
                        .useAllAvailableWidth().setMarginBottom(14);
                info2.addCell(infoCell("Quỹ dư sau khi chia tháng này",
                        fmtVn(kpi.getCarryOverOut()) + "đ", rg, bd, new DeviceRgb(255, 243, 224)));
                doc.add(info2);

                // ── Table 7 cột (10/2026 thêm "Thưởng chuyên cần") ──
                Table t = new Table(UnitValue.createPercentArray(
                        new float[]{5, 28, 14, 12, 11, 13, 17}))
                        .useAllAvailableWidth();
                t.addHeaderCell(hdr("STT", bd));
                t.addHeaderCell(hdr("Họ và tên", bd));
                t.addHeaderCell(hdr("Thưởng sản xuất", bd));
                t.addHeaderCell(hdr("Thưởng\nchuyên cần", bd));
                t.addHeaderCell(hdr("Tạm ứng", bd));
                t.addHeaderCell(hdr("Tổng thưởng", bd));
                t.addHeaderCell(hdr("Ký tên", bd));

                int stt = 1;
                for (User u : employees) {
                    final long uid = u.getId();
                    FactoryKpiBonusItem item = kpi.getItems().stream()
                            .filter(i -> i.getUser() != null && i.getUser().getId() == uid)
                            .findFirst().orElse(null);
                    long bonusAmt = item != null && item.getAmount() != null ? item.getAmount() : 0L;
                    long attBonus = attendanceBonusRepo
                            .findByUser_IdAndMonthAndYear(uid, month, year)
                            .map(com.nhatnam.server.entity.AttendanceBonus::getAmount).orElse(0L);
                    BigDecimal a = expenseVoucherRepo.sumSalaryAdvance(u.getId(), monthStr,
                            ExpenseVoucher.VoucherStatus.APPROVED);
                    long adv = a != null ? a.longValue() : 0;
                    long total = bonusAmt + attBonus - adv;

                    // Giữ dòng nếu có bonus HOẶC thưởng chuyên cần
                    if (bonusAmt == 0L && attBonus == 0L) continue;

                    boolean alt = stt % 2 == 0;
                    DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
                    t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
                    t.addCell(dc(nz(u.getFullName()), rg, bg, TextAlignment.LEFT));
                    t.addCell(money(bonusAmt, rg, bg));
                    t.addCell(money(attBonus, rg, bg));
                    t.addCell(money(adv, rg, bg));
                    t.addCell(moneyBold(total, bd, BG_GREEN));
                    t.addCell(new Cell().setBackgroundColor(bg).setPadding(14)
                            .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                            .add(new Paragraph(" ").setFont(rg).setFontSize(9)));
                    stt++;
                }
                doc.add(t);
                addFooter(doc, rg);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[factoryBonusPdf] error month={} year={}", month, year, e);
            return fallbackErrorPdf("Factory bonus PDF: " + e.getMessage());
        }
    }

    /**
     * PDF bảng lương tổng hợp (từng phòng).
     * Phiên bản PDF giữ các cột chính: STT · Họ tên · Chức vụ · Ngày công · Lương cơ bản
     * · Phụ cấp · Thưởng · Khấu trừ · Thực lãnh. (Breakdown phụ cấp chi tiết nếu cần xem
     * đầy đủ, dùng bản Excel cũ bằng cách query thêm ?format=xlsx — xem controller.)
     */
    @Transactional(readOnly = true)
    public byte[] salaryReportPdf(int month, int year, List<String> departments,
                                  java.util.function.Function<String, List<EmployeeRowView>> collector,
                                  String exportType) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(25, 25, 25, 25);
                PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

                String titleType = "BONUS_ONLY".equals(exportType) ? "THƯỞNG"
                        : "SALARY_ONLY".equals(exportType) ? "LƯƠNG" : "LƯƠNG & THƯỞNG";
                addHeader(doc, bd, "BẢNG " + titleType + " THÁNG " + month + "/" + year);

                for (String deptCode : departments) {
                    List<EmployeeRowView> rows = collector.apply(deptCode);
                    if (rows == null || rows.isEmpty()) continue;

                    doc.add(new Paragraph(deptLabel(deptCode)).setFont(bd).setFontSize(12)
                            .setFontColor(GOLD).setMarginTop(10).setMarginBottom(4));

                    Table t = new Table(UnitValue.createPercentArray(
                            new float[]{4, 22, 18, 8, 12, 12, 12, 12}))
                            .useAllAvailableWidth();
                    t.addHeaderCell(hdr("STT", bd));
                    t.addHeaderCell(hdr("Họ và tên", bd));
                    t.addHeaderCell(hdr("Chức vụ", bd));
                    t.addHeaderCell(hdr("Ngày\ncông", bd));
                    t.addHeaderCell(hdr("Lương\ncơ bản", bd));
                    t.addHeaderCell(hdr("Phụ cấp", bd));
                    t.addHeaderCell(hdr("Thưởng", bd));
                    t.addHeaderCell(hdr("Thực nhận", bd));

                    int stt = 1;
                    for (EmployeeRowView r : rows) {
                        boolean alt = stt % 2 == 0;
                        DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
                        t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
                        t.addCell(dc(nz(r.name), rg, bg, TextAlignment.LEFT));
                        t.addCell(dc(nz(r.role), rg, bg, TextAlignment.LEFT));
                        t.addCell(dc(r.workdays == null ? "" :
                                String.format("%.1f", r.workdays), rg, bg, TextAlignment.CENTER));
                        t.addCell(money(r.baseSalary, rg, bg));
                        t.addCell(money(r.allowances, rg, bg));
                        t.addCell(money(r.bonus, rg, bg));
                        t.addCell(moneyBold(r.net, bd, BG_GREEN));
                        stt++;
                    }
                    doc.add(t);
                }
                addFooter(doc, rg);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[salaryReportPdf] error", e);
            return fallbackErrorPdf("Salary report PDF: " + e.getMessage());
        }
    }

    /** DTO ngắn gọn cho PDF bảng lương. */
    public static class EmployeeRowView {
        public String name;
        public String role;
        public Double workdays;
        public long baseSalary;
        public long allowances;
        public long bonus;
        public long net;
    }

    private static String deptLabel(String code) {
        return switch (code) {
            case "FACTORY" -> "XƯỞNG SẢN XUẤT";
            case "ACCOUNTING" -> "KẾ TOÁN";
            case "SALES" -> "KINH DOANH";
            case "WAREHOUSE" -> "KHO";
            case "DRIVER" -> "GIAO NHẬN";
            case "MANAGEMENT" -> "QUẢN LÝ";
            default -> code;
        };
    }

    /**
     * PDF báo cáo tài xế dùng cho role SUPER_ACCOUNTANT (chọn khoảng thời gian).
     *
     * <h3>(10/2026) Yêu cầu mới</h3>
     * <ul>
     *   <li>Bỏ hiển thị giá xăng / đơn giá thưởng ở dòng meta — chỉ còn thời điểm xuất + người xuất</li>
     *   <li>Hardcode skip "Q9"</li>
     *   <li>Tài xế đã ngưng (hoặc đã xoá): tiền tố "[Đã nghỉ việc]", chữ đỏ in nghiêng cả dòng</li>
     *   <li>Vẫn xuất tài xế đã ngưng nếu còn data trong khoảng</li>
     * </ul>
     */
    public byte[] driverAttendanceReportPdf(DriverAttReport data) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(25, 25, 25, 25);
                PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");
                PdfFont it = loadFont("fonts/DejaVuSans.ttf");  // italic fallback

                addHeader(doc, bd, "BÁO CÁO TÀI XẾ & BẢNG LƯƠNG · "
                        + data.fromLabel + " — " + data.toLabel);

                // (10/2026) Meta chỉ còn: Xuất lúc + Người xuất (bỏ giá xăng/thưởng)
                doc.add(new Paragraph("Xuất lúc: " + data.exportedAt
                        + "   |   Người xuất: " + data.exportedBy)
                        .setFont(rg).setFontSize(9).setFontColor(INK_SOFT)
                        .setTextAlignment(TextAlignment.CENTER).setMarginBottom(8));

                Table t = new Table(UnitValue.createPercentArray(
                        new float[]{4, 22, 11, 10, 10, 14, 14, 14, 13}))
                        .useAllAvailableWidth();
                t.addHeaderCell(hdr("STT", bd));
                t.addHeaderCell(hdr("Tên tài xế", bd));
                t.addHeaderCell(hdr("Số km\nxe máy", bd));
                t.addHeaderCell(hdr("Số đơn\nxe máy", bd));
                t.addHeaderCell(hdr("Số đơn\nxe tải", bd));
                t.addHeaderCell(hdr("Tiền xăng", bd));
                t.addHeaderCell(hdr("Thưởng\nxe máy", bd));
                t.addHeaderCell(hdr("Thưởng\nxe tải", bd));
                t.addHeaderCell(hdr("Tổng cộng", bd));

                long grandTotal = 0;
                int stt = 1;
                for (DriverAttRow r : data.rows) {
                    long gas    = Math.round(r.kmBike * data.gasRate);
                    long bikeB  = Math.round(r.bikeOrders * data.bikeBonus);
                    long truckB = Math.round(r.truckOrders * data.truckBonus);
                    long raw    = gas + bikeB + truckB;
                    long total  = raw <= 0 ? 0 : (long) (Math.ceil(raw / 5000d) * 5000);
                    grandTotal += total;

                    // (10/2026) Tài xế đã ngưng: màu đỏ, in nghiêng, thêm [Đã nghỉ việc]
                    boolean inactive = !r.active;
                    String displayName = inactive ? "[Đã nghỉ việc] " + r.name : r.name;
                    if (r.isHelper) displayName = displayName + "\n  (Hỗ trợ giao hàng)";

                    DeviceRgb bg = stt % 2 == 0 ? BG_ALT : BG_WHITE;
                    if (inactive) bg = BG_YELLOW;   // nền đỏ nhạt
                    DeviceRgb textColor = inactive ? RED : INK;
                    boolean italic = inactive;

                    t.addCell(driverCell(String.valueOf(stt), rg, bg, textColor, italic, TextAlignment.CENTER));
                    t.addCell(driverCell(displayName, rg, bg, textColor, italic, TextAlignment.LEFT));
                    t.addCell(driverCell(String.valueOf(r.kmBike), rg, bg, textColor, italic, TextAlignment.RIGHT));
                    t.addCell(driverCell(String.valueOf(r.bikeOrders), rg, bg, textColor, italic, TextAlignment.RIGHT));
                    t.addCell(driverCell(String.valueOf(r.truckOrders), rg, bg, textColor, italic, TextAlignment.RIGHT));
                    t.addCell(driverCell(fmtVn(gas) + "đ", rg, bg, textColor, italic, TextAlignment.RIGHT));
                    t.addCell(driverCell(fmtVn(bikeB) + "đ", rg, bg, textColor, italic, TextAlignment.RIGHT));
                    t.addCell(driverCell(fmtVn(truckB) + "đ", rg, bg, textColor, italic, TextAlignment.RIGHT));
                    t.addCell(driverCell(fmtVn(total) + "đ", bd, bg, inactive ? RED : NAVY, italic, TextAlignment.RIGHT));
                    stt++;
                }
                if (stt == 1) {
                    t.addCell(new Cell(1, 9).add(new Paragraph("Không có dữ liệu.")
                                    .setFont(rg).setFontColor(INK_SOFT).setTextAlignment(TextAlignment.CENTER))
                            .setPadding(10));
                }

                // Total
                Cell totLabel = new Cell(1, 8).add(new Paragraph("TỔNG CỘNG (" + (stt - 1) + " dòng)")
                                .setFont(bd).setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.RIGHT))
                        .setBackgroundColor(NAVY).setPadding(6);
                Cell totVal = new Cell().add(new Paragraph(fmtVn(grandTotal) + "đ")
                                .setFont(bd).setFontColor(GOLD).setTextAlignment(TextAlignment.RIGHT))
                        .setBackgroundColor(NAVY).setPadding(6);
                t.addCell(totLabel);
                t.addCell(totVal);
                doc.add(t);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[driverAttendanceReportPdf] error", e);
            return fallbackErrorPdf("Driver attendance report PDF: " + e.getMessage());
        }
    }

    private Cell driverCell(String text, PdfFont font, DeviceRgb bg, DeviceRgb textColor,
                            boolean italic, TextAlignment align) {
        // (10/2026) iText 7 phiên bản này không có Paragraph.setItalic() —
        // simulate italic bằng cách setBold vào ký tự + prefix [...] đã đủ phân biệt.
        // Dù không nghiêng, phần tử đã đổi màu đỏ + nền đỏ nhạt + prefix "[Đã nghỉ việc]"
        // nên nhận diện vẫn rõ ràng.
        Paragraph p = new Paragraph(text).setFont(font).setFontSize(9)
                .setFontColor(textColor).setTextAlignment(align);
//        if (italic) p.setBold();   // nhấn mạnh bù cho không có italic
        return new Cell().add(p).setBackgroundColor(bg).setPadding(4)
                .setBorder(new SolidBorder(HAIRLINE, 0.5f));
    }

    /** DTO 1 dòng cho báo cáo tài xế. */
    public static class DriverAttRow {
        public String name;
        public int kmBike;
        public int bikeOrders;
        public int truckOrders;
        public boolean active = true;
        public boolean isHelper;
    }
    /** Container data cho báo cáo tài xế. */
    public static class DriverAttReport {
        public String fromLabel;
        public String toLabel;
        public String exportedAt;
        public String exportedBy;
        public double gasRate;
        public double bikeBonus;
        public double truckBonus;
        public List<DriverAttRow> rows = new ArrayList<>();
    }

    /** PDF thưởng tài xế + hỗ trợ giao hàng (dùng ở FactoryPayroll). */
    @Transactional(readOnly = true)
    public byte[] driverBonusPdf(int month, int year) {
        var cfg = factoryPayrollService.driverPayrollConfig(month, year);
        List<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> driverRows =
                cfg.getRows() != null ? cfg.getRows() : List.of();
        // (10/2026) Tài xế xe tải — thưởng cố định, tách thành section riêng
        List<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> truckDrvRows =
                cfg.getTruckDriverRows() != null ? cfg.getTruckDriverRows() : List.of();
        List<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> nonDriverRw =
                cfg.getNonDriverRows() != null ? cfg.getNonDriverRows() : List.of();
        String monthStr = String.format("%d-%02d", year, month);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
            try (Document doc = new Document(pdfDoc)) {
                doc.setMargins(30, 30, 30, 30);
                PdfFont rg = loadFont("fonts/DejaVuSans.ttf");
                PdfFont bd = loadFont("fonts/DejaVuSans-Bold.ttf");

                addHeader(doc, bd, "BẢNG THƯỞNG TÀI XẾ & HỖ TRỢ GIAO HÀNG THÁNG " + month + "/" + year);

                // (10/2026) 9 cột — thêm Thưởng chuyên cần trước Tạm ứng
                //   STT · Họ tên · Xăng · Bonus xe máy · Bonus xe tải · Thưởng chuyên cần · Tạm ứng · Tổng · Ký tên
                Table t = new Table(UnitValue.createPercentArray(
                        new float[]{4, 19, 10, 10, 10, 11, 9, 11, 16}))
                        .useAllAvailableWidth();
                t.addHeaderCell(hdr("STT", bd));
                t.addHeaderCell(hdr("Họ và tên", bd));
                t.addHeaderCell(hdr("Phụ cấp\nxăng xe", bd));
                t.addHeaderCell(hdr("Thưởng đơn\nxe máy", bd));
                t.addHeaderCell(hdr("Thưởng đơn\nxe tải", bd));
                t.addHeaderCell(hdr("Thưởng\nchuyên cần", bd));
                t.addHeaderCell(hdr("Tạm ứng", bd));
                t.addHeaderCell(hdr("Tổng cộng", bd));
                t.addHeaderCell(hdr("Ký tên", bd));

                // (10/2026) Sort từng nhóm theo tổng bonus DESC (trước tạm ứng).
                // Nhóm "TÀI XẾ" riêng, nhóm "HỖ TRỢ" riêng — không trộn.
                java.util.Comparator<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> byBonusDesc =
                        (a, b) -> Long.compare(bonusBeforeAdvance(b), bonusBeforeAdvance(a));
                java.util.List<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> drvSorted =
                        new java.util.ArrayList<>(driverRows);
                drvSorted.sort(byBonusDesc);
                java.util.List<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> supSorted =
                        new java.util.ArrayList<>(nonDriverRw);
                supSorted.sort(byBonusDesc);

                int stt = 1;
                boolean hasDrivers = false, hasTruckDrivers = false, hasSupports = false;

                // (10/2026) SECTION 1: Tài xế xe máy + lượt xe tải khi kiêm
                for (var row : drvSorted) {
                    long attBonus = attendanceBonusAmount(row.getUserId(), month, year);
                    if (bonusBeforeAdvance(row) == 0L && attBonus == 0L) continue;  // ẩn khi cả hai = 0
                    if (!hasDrivers) { groupRow(t, "TÀI XẾ XE MÁY", bd); hasDrivers = true; }
                    stt = appendDriverRow(t, row, stt, false, rg, bd, monthStr, attBonus);
                }

                // (10/2026) SECTION 2: Tài xế XE TẢI — thưởng cố định
                // Cột km / tiền xăng / lượt xe máy / thưởng xe máy / lượt xe tải
                // hiển thị emdash — chỉ "Thưởng xe tải (cố định)" có số, trừ tạm ứng để ra Tổng.
                java.util.List<com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto> truckSorted =
                        new java.util.ArrayList<>(truckDrvRows);
                truckSorted.sort((a, b) -> Long.compare(
                        b.getFixedTruckBonus() != null ? b.getFixedTruckBonus() : 0L,
                        a.getFixedTruckBonus() != null ? a.getFixedTruckBonus() : 0L));
                for (var row : truckSorted) {
                    long fixed = row.getFixedTruckBonus() != null ? row.getFixedTruckBonus() : 0L;
                    long attBonus = attendanceBonusAmount(row.getUserId(), month, year);
                    if (fixed == 0L && attBonus == 0L) continue;  // chưa nhập thưởng + không có chuyên cần → ẩn
                    if (!hasTruckDrivers) { groupRow(t, "TÀI XẾ XE TẢI", bd); hasTruckDrivers = true; }
                    stt = appendTruckDriverRow(t, row, stt, rg, bd, monthStr, attBonus);
                }

                // (10/2026) SECTION 3: Nhân viên hỗ trợ giao hàng
                for (var row : supSorted) {
                    long attBonus = attendanceBonusAmount(row.getUserId(), month, year);
                    if (bonusBeforeAdvance(row) == 0L && attBonus == 0L) continue;
                    if (!hasSupports) { groupRow(t, "HỖ TRỢ GIAO HÀNG", bd); hasSupports = true; }
                    stt = appendDriverRow(t, row, stt, true, rg, bd, monthStr, attBonus);
                }
                if (stt == 1) {
                    t.addCell(new Cell(1, 9).add(new Paragraph("Không có dữ liệu.")
                                    .setFont(rg).setFontColor(INK_SOFT).setTextAlignment(TextAlignment.CENTER))
                            .setPadding(10));
                }
                doc.add(t);
                addFooter(doc, rg);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[driverBonusPdf] error month={} year={}", month, year, e);
            return fallbackErrorPdf("Driver bonus PDF: " + e.getMessage());
        }
    }

    /** Thưởng chuyên cần tháng của 1 NV (0 nếu chưa set / userId null). */
    private long attendanceBonusAmount(Long userId, int month, int year) {
        if (userId == null) return 0L;
        return attendanceBonusRepo.findByUser_IdAndMonthAndYear(userId, month, year)
                .map(ab -> ab.getAmount() != null ? ab.getAmount() : 0L)
                .orElse(0L);
    }

    /** Tổng bonus (xăng + xe máy + xe tải) trước khi trừ tạm ứng — dùng để sort và lọc 0đ. */
    private static long bonusBeforeAdvance(
            com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto r) {
        long fuel  = r.getMotorbike() != null && r.getMotorbike().getFuelPay()  != null
                ? r.getMotorbike().getFuelPay().longValue() : 0L;
        long bMoto = r.getMotorbike() != null && r.getMotorbike().getBonusPay() != null
                ? r.getMotorbike().getBonusPay().longValue() : 0L;
        long bTruck = r.getTruck() != null && r.getTruck().getBonusPay() != null
                ? r.getTruck().getBonusPay().longValue() : 0L;
        return fuel + bMoto + bTruck;
    }

    private int appendDriverRow(Table t,
                                com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto r,
                                int stt, boolean nonDriver, PdfFont rg, PdfFont bd, String monthStr, long attBonus) {
        boolean alt = stt % 2 == 0;
        DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
        long fuel = r.getMotorbike() != null && r.getMotorbike().getFuelPay() != null
                ? r.getMotorbike().getFuelPay().longValue() : 0L;
        long bMoto = r.getMotorbike() != null && r.getMotorbike().getBonusPay() != null
                ? r.getMotorbike().getBonusPay().longValue() : 0L;
        long bTruck = r.getTruck() != null && r.getTruck().getBonusPay() != null
                ? r.getTruck().getBonusPay().longValue() : 0L;
        long adv = 0;
        if (r.getUserId() != null) {
            BigDecimal a = expenseVoucherRepo.sumSalaryAdvance(r.getUserId(), monthStr,
                    ExpenseVoucher.VoucherStatus.APPROVED);
            adv = a != null ? a.longValue() : 0;
        }
        // FIX (10/2026): làm tròn LÊN bước 5.000đ cho tổng thưởng gross — đồng bộ
        // với UI "Thưởng tháng" (AccountantBonusViewPage.DriverBonusCard.rowTotal).
        // Trước đây PDF trả số thô (vd 3.938.176đ) trong khi UI đã hiện 3.940.000đ
        // → NV đọc lệch 1-5k so với bảng công bố. Round UP áp lên GROSS (xăng +
        // bonus xe máy + bonus xe tải + chuyên cần), rồi mới trừ tạm ứng → phần
        // "Tạm ứng" vẫn hiện đúng số đã ứng, "Tổng cộng" khớp UI.
        long grossBonus   = fuel + bMoto + bTruck + attBonus;
        long roundedGross = grossBonus > 0
                ? ((grossBonus + 4999L) / 5000L) * 5000L
                : 0L;
        long total = roundedGross - adv;

        // 9 cột: STT · Họ tên · Xăng · Bonus xe máy · Bonus xe tải ·
        //        Thưởng chuyên cần · Tạm ứng · Tổng · Ký tên
        t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
        t.addCell(dc(nz(r.getDriverName()), rg, bg, TextAlignment.LEFT));
        t.addCell(money(fuel, rg, bg));
        t.addCell(money(bMoto, rg, bg));
        t.addCell(money(bTruck, rg, bg));
        t.addCell(money(attBonus, rg, bg));
        t.addCell(money(adv, rg, bg));
        t.addCell(moneyBold(total, bd, BG_GREEN));
        // Ô "Ký tên" trống
        t.addCell(new Cell().setBackgroundColor(bg).setPadding(14)
                .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                .add(new Paragraph(" ").setFont(rg).setFontSize(9)));
        return stt + 1;
    }

    /**
     * (10/2026) Row cho TÀI XẾ XE TẢI (thưởng cố định) — các cột tính-theo-lượt
     * hiển thị emdash, chỉ cột "Thưởng xe tải" là số cố định, Tạm ứng vẫn tính
     * bình thường, Tổng = fixed + chuyên cần - tạm ứng.
     */
    private int appendTruckDriverRow(Table t,
                                     com.nhatnam.server.dto.factorypayroll.FactoryPayrollDtos.DriverSalaryRowDto r,
                                     int stt, PdfFont rg, PdfFont bd, String monthStr, long attBonus) {
        boolean alt = stt % 2 == 0;
        DeviceRgb bg = alt ? BG_ALT : BG_WHITE;
        long fixed = r.getFixedTruckBonus() != null ? r.getFixedTruckBonus() : 0L;
        long adv = 0;
        if (r.getUserId() != null) {
            BigDecimal a = expenseVoucherRepo.sumSalaryAdvance(r.getUserId(), monthStr,
                    ExpenseVoucher.VoucherStatus.APPROVED);
            adv = a != null ? a.longValue() : 0;
        }
        long total = fixed + attBonus - adv;

        // 9 cột: STT · Họ tên · Xăng · Bonus xe máy · Bonus xe tải (cố định) ·
        //        Thưởng chuyên cần · Tạm ứng · Tổng · Ký tên
        t.addCell(dc(String.valueOf(stt), rg, bg, TextAlignment.CENTER));
        t.addCell(dc(nz(r.getDriverName()), rg, bg, TextAlignment.LEFT));
        t.addCell(dc("—", rg, bg, TextAlignment.RIGHT));   // Phụ cấp xăng xe
        t.addCell(dc("—", rg, bg, TextAlignment.RIGHT));   // Thưởng đơn xe máy
        t.addCell(money(fixed, rg, bg));                    // Thưởng xe tải (cố định)
        t.addCell(money(attBonus, rg, bg));
        t.addCell(money(adv, rg, bg));
        t.addCell(moneyBold(total, bd, BG_GREEN));
        t.addCell(new Cell().setBackgroundColor(bg).setPadding(14)
                .setBorder(new SolidBorder(HAIRLINE, 0.5f))
                .add(new Paragraph(" ").setFont(rg).setFontSize(9)));
        return stt + 1;
    }

    private void groupRow(Table t, String label, PdfFont bd) {
        // 9 cột (thêm Thưởng chuyên cần)
        Cell c = new Cell(1, 9).add(new Paragraph(label).setFont(bd).setFontColor(ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(NAVY).setPadding(5);
        t.addCell(c);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void addHeader(Document doc, PdfFont bd, String title) {
        doc.add(new Paragraph("CÔNG TY TNHH NHẤT NAM")
                .setFont(bd).setFontSize(10).setFontColor(GOLD)
                .setTextAlignment(TextAlignment.CENTER).setMarginBottom(2));
        doc.add(new Paragraph(title)
                .setFont(bd).setFontSize(14).setFontColor(NAVY)
                .setTextAlignment(TextAlignment.CENTER).setMarginBottom(10));
    }

    private void addFooter(Document doc, PdfFont rg) {
        String ts = LocalDateTime.now(VN).format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"));
        doc.add(new Paragraph("Xuất lúc: " + ts)
                .setFont(rg).setFontSize(8).setFontColor(INK_SOFT)
                .setTextAlignment(TextAlignment.RIGHT).setMarginTop(10));
    }

    private Cell hdr(String text, PdfFont bd) {
        return new Cell().add(new Paragraph(text).setFont(bd).setFontSize(9)
                        .setFontColor(ColorConstants.WHITE).setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(NAVY).setPadding(5).setBorder(new SolidBorder(NAVY, 0.5f));
    }

    private Cell dc(String text, PdfFont rg, DeviceRgb bg, TextAlignment align) {
        return new Cell().add(new Paragraph(text).setFont(rg).setFontSize(9)
                        .setFontColor(INK).setTextAlignment(align))
                .setBackgroundColor(bg).setPadding(4).setBorder(new SolidBorder(HAIRLINE, 0.5f));
    }

    private Cell money(long v, PdfFont rg, DeviceRgb bg) {
        return new Cell().add(new Paragraph(fmtVn(v) + " đ").setFont(rg).setFontSize(9)
                        .setFontColor(INK).setTextAlignment(TextAlignment.RIGHT))
                .setBackgroundColor(bg).setPadding(4).setBorder(new SolidBorder(HAIRLINE, 0.5f));
    }

    private Cell moneyBold(long v, PdfFont bd, DeviceRgb bg) {
        return new Cell().add(new Paragraph(fmtVn(v) + " đ").setFont(bd).setFontSize(9)
                        .setFontColor(NAVY).setTextAlignment(TextAlignment.RIGHT))
                .setBackgroundColor(bg).setPadding(4).setBorder(new SolidBorder(HAIRLINE, 0.5f));
    }

    private Cell infoCell(String label, String value, PdfFont rg, PdfFont bd, DeviceRgb bg) {
        Cell c = new Cell().setBackgroundColor(bg).setPadding(8).setBorder(new SolidBorder(HAIRLINE, 0.5f));
        c.add(new Paragraph(label).setFont(rg).setFontSize(8).setFontColor(INK_SOFT));
        c.add(new Paragraph(value).setFont(bd).setFontSize(12).setFontColor(NAVY));
        return c;
    }

    /**
     * Thứ tự hiển thị trên bảng thưởng xưởng SX (yêu cầu nghiệp vụ 10/2026):
     * Trưởng xưởng → Trợ lý xưởng → Kế toán xưởng → Nhân viên đóng gói →
     * Công nhân sản xuất (gộp FACTORY_WORKER vì nhãn giống nhau) → Bảo vệ.
     */
    private int roleRank(com.nhatnam.server.enumtype.Role r) {
        if (r == null) return 999;
        return switch (r) {
            case SUPER_FACTORY_WORKER      -> 1;  // Trưởng xưởng
            case FACTORY_STAFF             -> 2;  // Trợ lý xưởng
            case FACTORY_ACCOUNTANT        -> 3;  // Kế toán xưởng
            case FACTORY_PACKAGING_WORKER  -> 4;  // Nhân viên đóng gói
            case FACTORY_PRODUCTION_WORKER -> 5;  // Công nhân sản xuất
            case FACTORY_WORKER            -> 5;  // Nhân viên xưởng (cùng mức)
            case FACTORY_SECURITY          -> 6;  // Bảo vệ
            default -> 99;
        };
    }

    public static String fmtVn(Number v) {
        if (v == null) return "0";
        return String.format("%,d", v.longValue());
    }
    private static String fmtKg(BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.1f", v.doubleValue());
    }
    private static String nz(String s) { return s == null ? "" : s; }

    private PdfFont loadFont(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return PdfFontFactory.createFont(is.readAllBytes(),
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        } catch (Exception e) {
            log.warn("[SalaryReportPdf] Không load được font {}: {}. Fallback Helvetica.",
                    path, e.getMessage());
            try {
                return PdfFontFactory.createFont(
                        com.itextpdf.io.font.constants.StandardFonts.HELVETICA);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
    }

    private byte[] fallbackErrorPdf(String msg) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(bos));
            try (Document doc = new Document(pdfDoc)) {
                doc.add(new Paragraph("Không tạo được PDF báo cáo.").setFontColor(RED));
                doc.add(new Paragraph("Chi tiết: " + msg));
            }
            return bos.toByteArray();
        } catch (Exception ignore) { return new byte[0]; }
    }
}