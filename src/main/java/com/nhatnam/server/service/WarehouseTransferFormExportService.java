package com.nhatnam.server.service;

import com.nhatnam.server.entity.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Xuất 3 biểu mẫu Excel của quy trình "đóng gói & hao hụt" (theo đúng mẫu Word
 * "Biểu mẫu xuất nhập kho xúc xích" do chủ dự án cung cấp):
 *
 *  1. {@link #exportTransferOut}    — Phiếu xuất kho bán thành phẩm (PX-BTP-...)
 *     In khi Trưởng xưởng/NV xưởng vừa lập phiếu chuyển kho (trạng thái PENDING
 *     hoặc RECEIVED đều xem được — đây là phiếu xuất, không đổi theo việc nhận).
 *
 *  2. {@link #exportTransferIn}     — Phiếu nhập kho thành phẩm (PN-TP-...)
 *     CHỈ in được sau khi kế toán kho đã xác nhận nhận (status = RECEIVED),
 *     vì cần số liệu đóng gói thực tế + trọng lượng thực cân.
 *
 *  3. {@link #exportLossReport}     — Biên bản ghi nhận hao hụt (BB-HH-...)
 *     Chỉ tồn tại (có nút tạo ở FE) khi dòng phiếu có lossQty > 0.
 *
 * Các trường trong mẫu gốc mà hệ thống hiện không lưu trữ (đơn giá, ca SX,
 * chữ ký, nhiệt độ BTP, phân loại hao hụt chi tiết...) được để Ô TRỐNG kèm
 * nhãn đúng như mẫu — người dùng tự điền tay khi in ra, không suy diễn số liệu.
 */
@Service
@RequiredArgsConstructor
public class WarehouseTransferFormExportService {

    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormat NUM_FMT = new DecimalFormat("#,##0.###");
    private static final DecimalFormat PCT_FMT = new DecimalFormat("#,##0.00");

    // ═══════════════════════════════════════════════════════════════════════
    // 1) PHIẾU XUẤT KHO BÁN THÀNH PHẨM (PX-BTP-...)
    // ═══════════════════════════════════════════════════════════════════════

    public byte[] exportTransferOut(SemiFinishedTransferNote note) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sh = wb.createSheet("Phieu xuat BTP");
        Styles s = new Styles(wb);
        int cols = 8;
        sh.setPrintGridlines(false);
        int r = 0;

        r = writeCompanyHeader(sh, s, r, cols);

        r = titleRow(sh, s, r, cols, "PHIẾU XUẤT KHO BÁN THÀNH PHẨM");

        r = infoRow2(sh, s, r, cols, "Số phiếu:", note.getNoteCode(),
                "Ngày xuất:", fmtDate(note.getCreatedAt()));
        r = infoRow2(sh, s, r, cols, "Bộ phận xuất:", "Xưởng sản xuất",
                "Bộ phận nhận:", "Bộ phận đóng gói");
        // Lệnh sản xuất: gộp các work order liên quan tới tất cả batch nguồn của phiếu
        String workOrderCodes = workOrderCodesOf(note);
        r = infoRow2(sh, s, r, cols, "Lệnh sản xuất:", emptyDash(workOrderCodes),
                "Ca sản xuất:", "Ca .......... (Sáng / Chiều / Tối)");
        r = infoRow1(sh, s, r, cols, "Ghi chú:", note.getNotes());
        r++; // spacer

        // ── Bảng chi tiết ──
        String[] headers = {"STT", "Tên bán thành phẩm", "Mã SP", "Đơn vị",
                "SL theo lệnh SX", "SL thực xuất (kg)", "Đơn giá (đ/kg)", "Thành tiền (đ)"};
        int headerRowIdx = r;
        r = writeHeaderRow(sh, s, r, headers);

        BigDecimal totalQty = BigDecimal.ZERO;
        int stt = 1;
        for (SemiFinishedTransferNoteLine line : note.getLines()) {
            Row row = sh.createRow(r++);
            row.setHeightInPoints(32);
            boolean even = (stt % 2 == 0);
            putCell(row, 0, String.valueOf(stt), even ? s.numEven : s.numOdd);
            putCell(row, 1, line.getProductName(), even ? s.dataEven : s.dataOdd);
            putCell(row, 2, "", even ? s.dataEven : s.dataOdd); // Mã SP — không lưu trong hệ thống
            putCell(row, 3, line.getUnit(), even ? s.numEven : s.numOdd);
            // SL theo lệnh SX — cùng giá trị với SL thực xuất ở cấp dòng (phiếu đã trừ đúng theo nhu cầu)
            putCell(row, 4, NUM_FMT.format(line.getTransferredQty()), even ? s.numEven : s.numOdd);
            putCell(row, 5, NUM_FMT.format(line.getTransferredQty()), even ? s.numEven : s.numOdd);
            putCell(row, 6, "", even ? s.inputEven : s.inputOdd); // Đơn giá — không lưu trong hệ thống
            putCell(row, 7, "", even ? s.inputEven : s.inputOdd); // Thành tiền — không lưu trong hệ thống
            totalQty = totalQty.add(line.getTransferredQty());
            stt++;
        }
        // Thêm vài dòng trống cho đúng cảm giác mẫu giấy (nếu ít dòng)
        for (int i = note.getLines().size(); i < 3; i++) {
            Row row = sh.createRow(r++);
            row.setHeightInPoints(28);
            for (int c = 0; c < headers.length; c++) putCell(row, c, "", c % 2 == 0 ? s.dataOdd : s.dataOdd);
        }

        // Dòng CỘNG
        Row totalRow = sh.createRow(r++);
        totalRow.setHeightInPoints(30);
        sh.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 3));
        putCell(totalRow, 0, "CỘNG", s.totalLabel);
        putCell(totalRow, 4, "", s.totalLabel);
        putCell(totalRow, 5, NUM_FMT.format(totalQty), s.totalValue);
        putCell(totalRow, 6, "", s.totalLabel);
        putCell(totalRow, 7, "", s.totalLabel);
        r++; // spacer

        // ── Ghi chú kiểm soát chất lượng (để trống, đúng nhãn mẫu) ──
        r = qualityNoteBlock(sh, s, r, cols);

        // ── Khối ký tên ──
        signatureBlock(sh, s, r, cols, "Người lập phiếu", "Tổ trưởng sản xuất", "Thủ kho xuất", "Kế toán");

        finalizeSheet(sh, headerRowIdx, cols);
        return toBytes(wb);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2) PHIẾU NHẬP KHO THÀNH PHẨM (PN-TP-...) — chỉ in khi đã RECEIVED
    // ═══════════════════════════════════════════════════════════════════════

    public byte[] exportTransferIn(SemiFinishedTransferNote note) throws IOException {
        if (note.getStatus() != SemiFinishedTransferNote.Status.RECEIVED) {
            throw new IllegalStateException("Phiếu chưa được xác nhận nhận — chưa thể in Phiếu nhập kho thành phẩm");
        }
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sh = wb.createSheet("Phieu nhap TP");
        Styles s = new Styles(wb);
        int cols = 9;
        sh.setPrintGridlines(false);
        int r = 0;

        r = writeCompanyHeader(sh, s, r, cols);
        r = titleRow(sh, s, r, cols, "PHIẾU NHẬP KHO THÀNH PHẨM");

        r = infoRow2(sh, s, r, cols, "Số phiếu:", "PN-" + note.getNoteCode(),
                "Ngày nhập:", fmtDate(note.getReceivedAt()));
        r = infoRow2(sh, s, r, cols, "Bộ phận giao:", "Bộ phận đóng gói",
                "Kho nhập:", "Kho thành phẩm");
        r = infoRow2(sh, s, r, cols, "Liên quan phiếu xuất:", note.getNoteCode(),
                "Lệnh sản xuất:", emptyDash(workOrderCodesOf(note)));
        r = infoRow1(sh, s, r, cols, "Ghi chú:", note.getReceiveNotes());
        r++; // spacer

        String[] headers = {"STT", "Tên thành phẩm", "Mã SP", "Quy cách (kg/gói)",
                "Số lượng gói (cái)", "Trọng lượng thực tế (kg)", "ĐV tính", "Đơn giá (đ/gói)", "Thành tiền (đ)"};
        int headerRowIdx = r;
        r = writeHeaderRow(sh, s, r, headers);

        BigDecimal totalPackaged = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        BigDecimal totalTransferred = BigDecimal.ZERO;
        int stt = 1;
        for (SemiFinishedTransferNoteLine line : note.getLines()) {
            Row row = sh.createRow(r++);
            row.setHeightInPoints(32);
            boolean even = (stt % 2 == 0);
            BigDecimal packaged = line.getPackagedQty() != null ? line.getPackagedQty() : BigDecimal.ZERO;
            BigDecimal received = line.getActualReceivedWeight() != null ? line.getActualReceivedWeight() : BigDecimal.ZERO;
            // Quy cách (kg/gói) = ĐỊNH LƯỢNG CHUẨN khai báo ở Recipe (packagingQty) của
            // batch nguồn — KHÔNG tính ngược từ received/packaged, vì con số tính ngược
            // phản ánh trọng lượng thực tế trung bình (có thể lệch do hao hụt/dung sai
            // cân), không phải quy cách đóng gói chuẩn đã công bố cho sản phẩm.
            BigDecimal standardPackagingQty = line.getSourceBatches().stream()
                    .map(sb -> sb.getBatch() != null && sb.getBatch().getRecipe() != null
                            ? sb.getBatch().getRecipe().getPackagingQty() : null)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            String spec = standardPackagingQty != null ? NUM_FMT.format(standardPackagingQty) : "";

            putCell(row, 0, String.valueOf(stt), even ? s.numEven : s.numOdd);
            putCell(row, 1, line.getProductName(), even ? s.dataEven : s.dataOdd);
            putCell(row, 2, "", even ? s.dataEven : s.dataOdd);
            putCell(row, 3, spec, even ? s.numEven : s.numOdd);
            putCell(row, 4, NUM_FMT.format(packaged), even ? s.numEven : s.numOdd);
            putCell(row, 5, NUM_FMT.format(received), even ? s.numEven : s.numOdd);
            putCell(row, 6, line.getPackagedUnit() != null ? line.getPackagedUnit() : "túi", even ? s.dataEven : s.dataOdd);
            putCell(row, 7, "", even ? s.inputEven : s.inputOdd);
            putCell(row, 8, "", even ? s.inputEven : s.inputOdd);

            totalPackaged = totalPackaged.add(packaged);
            totalReceived = totalReceived.add(received);
            totalTransferred = totalTransferred.add(line.getTransferredQty());
            stt++;
        }
        for (int i = note.getLines().size(); i < 3; i++) {
            Row row = sh.createRow(r++);
            row.setHeightInPoints(28);
            for (int c = 0; c < headers.length; c++) putCell(row, c, "", s.dataOdd);
        }

        Row totalRow = sh.createRow(r++);
        totalRow.setHeightInPoints(30);
        sh.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 3));
        putCell(totalRow, 0, "CỘNG", s.totalLabel);
        putCell(totalRow, 4, NUM_FMT.format(totalPackaged), s.totalValue);
        putCell(totalRow, 5, NUM_FMT.format(totalReceived), s.totalValue);
        putCell(totalRow, 6, "", s.totalLabel);
        putCell(totalRow, 7, "", s.totalLabel);
        putCell(totalRow, 8, "", s.totalLabel);
        r++; // spacer

        // ── Đối chiếu xuất — nhập ──
        BigDecimal diff = totalTransferred.subtract(totalReceived);
        BigDecimal lossQty = diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
        String reconcileText = String.format(
                "BTP xuất kho (phiếu %s tương ứng): %s kg%n" +
                        "TP nhập kho (theo phiếu này): %s kg (%s %s)%n" +
                        "Chênh lệch đóng gói: %s kg  →  %s",
                note.getNoteCode(), NUM_FMT.format(totalTransferred),
                NUM_FMT.format(totalReceived), NUM_FMT.format(totalPackaged),
                note.getLines().isEmpty() ? "gói" : (note.getLines().get(0).getPackagedUnit() != null ? note.getLines().get(0).getPackagedUnit() : "gói"),
                NUM_FMT.format(lossQty),
                lossQty.compareTo(BigDecimal.ZERO) > 0 ? "Xem Biên bản hao hụt tương ứng" : "Không có hao hụt"
        );
        r = wrappedNoteBlock(sh, s, r, cols, reconcileText, 5,
                "Tình trạng thành phẩm nhập kho:   ☐ Đạt tiêu chuẩn     ☐ Chờ xử lý     ☐ Loại bỏ");

        signatureBlock(sh, s, r, cols, "Người lập phiếu", "Tổ trưởng đóng gói", "Thủ kho nhập", "Kế toán");

        finalizeSheet(sh, headerRowIdx, cols);
        return toBytes(wb);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3) BIÊN BẢN GHI NHẬN HAO HỤT (BB-HH-...) — chỉ tồn tại khi lossQty > 0
    // ═══════════════════════════════════════════════════════════════════════

    public byte[] exportLossReport(PackagingLossReport report) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        XSSFSheet sh = wb.createSheet("Bien ban hao hut");
        Styles s = new Styles(wb);
        // cols=8 (không phải 6) để signatureBlock với 4 người ký chia đều
        // colsPerBlock = 8/4 = 2 cột/người — đủ rộng và đều, tránh trường hợp
        // 8 % 4 != 0 hoặc cols quá nhỏ làm 1 vài cột chữ ký chỉ rộng 1 cell.
        int cols = 8;
        sh.setPrintGridlines(false);
        int r = 0;

        r = writeCompanyHeader(sh, s, r, cols);
        r = titleRow(sh, s, r, cols, "BIÊN BẢN GHI NHẬN HAO HỤT");

        r = infoRow1(sh, s, r, cols, "Số biên bản:", report.getReportCode()
                + "        Ngày lập: " + fmtDate(report.getCreatedAt()));
        r++; // spacer

        // ── Thành phần lập biên bản (để trống tên, chỉ in nhãn chức vụ) ──
        r = sectionHeader(sh, s, r, cols, "Thành phần lập biên bản:");
        String[] roles = {"Tổ trưởng sản xuất / đóng gói", "Thủ kho", "Kế toán", "QC / KCS"};
        for (int i = 0; i < roles.length; i++) {
            r = infoRow1(sh, s, r, cols, (i + 1) + ". Ông / Bà:", "................................  Chức vụ: " + roles[i]);
        }
        r++; // spacer

        // ── I. THÔNG TIN MẺ SẢN XUẤT ──
        r = sectionHeader(sh, s, r, cols, "I. THÔNG TIN MẺ SẢN XUẤT");
        SemiFinishedTransferNote note = report.getTransferNote();
        r = infoRow2(sh, s, r, cols, "Lệnh sản xuất số:", emptyDash(workOrderCodesOf(note)),
                "Ca sản xuất:", "Ca .......... (Sáng / Chiều / Tối)");
        r = infoRow2(sh, s, r, cols, "Phiếu xuất BTP:", report.getTransferNoteCodeSnapshot(),
                "Phiếu nhập TP:", "PN-" + report.getTransferNoteCodeSnapshot());
        r = infoRow2(sh, s, r, cols, "Tên sản phẩm:", report.getProductName(),
                "Ngày sản xuất:", fmtDate(report.getCreatedAt()));
        r++; // spacer

        // ── II. SỐ LIỆU XUẤT — NHẬP — HAO HỤT ──
        // Bảng có 6 cột logic nhưng sheet rộng 8 cột Excel (để khối ký tên ở dưới
        // chia đều 4 người) → merge "Chỉ tiêu" và "Ghi chú" rộng 2 cột mỗi cột để
        // lấp đầy đúng 8 cột (1+2+1+1+1+2=8), tránh để trống/lệch bên phải bảng.
        int[] colSpansII = {1, 2, 1, 1, 1, 2};
        r = sectionHeader(sh, s, r, cols, "II. SỐ LIỆU XUẤT — NHẬP — HAO HỤT");
        int headerRowIdx = r;
        r = writeHeaderRowSpanned(sh, s, r, new String[]{"STT", "Chỉ tiêu", "ĐVT", "Kế hoạch / Định mức", "Thực tế", "Ghi chú"}, colSpansII);

        BigDecimal transferred = report.getTransferredQty();
        BigDecimal received = report.getActualReceivedWeight();
        BigDecimal loss = report.getLossQty();
        BigDecimal lossPct = transferred.compareTo(BigDecimal.ZERO) > 0
                ? loss.multiply(new BigDecimal(100)).divide(transferred, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal packaged = report.getPackagedQty() != null ? report.getPackagedQty() : BigDecimal.ZERO;
        BigDecimal avgPerPack = packaged.compareTo(BigDecimal.ZERO) > 0
                ? received.divide(packaged, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        String unit = report.getPackagedUnit() != null ? report.getPackagedUnit() : "gói";

        Object[][] rows = {
                {"1", "BTP xuất kho (đầu vào đóng gói)", "kg", NUM_FMT.format(transferred), NUM_FMT.format(transferred), "Theo phiếu " + report.getTransferNoteCodeSnapshot()},
                {"2", "Thành phẩm nhập kho (số " + unit + " × quy cách)", "kg", NUM_FMT.format(transferred), NUM_FMT.format(received), NUM_FMT.format(packaged) + " " + unit},
                {"3", "Hao hụt đóng gói (dòng 1 − dòng 2)", "kg", "", NUM_FMT.format(loss), "Xem mục III"},
                {"4", "Tỷ lệ hao hụt đóng gói (dòng 3 / dòng 1 × 100%)", "%", "", PCT_FMT.format(lossPct) + "%", ""},
                {"5", "Số " + unit + " thành phẩm thực tế nhập kho", unit, "", NUM_FMT.format(packaged), "Thực cân từng " + unit},
                {"6", "Trọng lượng trung bình / " + unit, "kg", "", NUM_FMT.format(avgPerPack), NUM_FMT.format(received) + " kg / " + NUM_FMT.format(packaged) + " " + unit},
        };
        for (Object[] data : rows) {
            boolean even = (Integer.parseInt((String) data[0]) % 2 == 0);
            CellStyle[] styles = {
                    even ? s.numEven : s.numOdd,
                    even ? s.dataEven : s.dataOdd,
                    even ? s.numEven : s.numOdd,
                    even ? s.dataEven : s.dataOdd,
                    even ? s.dataEven : s.dataOdd,
                    even ? s.dataEven : s.dataOdd,
            };
            r = writeDataRowSpanned(sh, r, data, colSpansII, styles, 30);
        }
        r++; // spacer

        // ── III. PHÂN TÍCH NGUYÊN NHÂN HAO HỤT (để trống cho người dùng điền) ──
        // 5 cột logic, merge "Nguyên nhân" và "Ghi chú / Mô tả" rộng 2 cột, "Phân
        // loại" rộng 2 cột để lấp đầy đúng 8 cột (1+2+1+2+2=8).
        int[] colSpansIII = {1, 2, 1, 2, 2};
        r = sectionHeader(sh, s, r, cols, "III. PHÂN TÍCH NGUYÊN NHÂN HAO HỤT");
        int causeHeaderIdx = r;
        r = writeHeaderRowSpanned(sh, s, r, new String[]{"STT", "Nguyên nhân", "Khối lượng (kg)", "Ghi chú / Mô tả", "Phân loại"}, colSpansIII);
        String[][] causes = {
                {"1", "Chênh lệch dung sai cân khi đóng gói", "", "Mỗi " + unit + " lệch so với định lượng chuẩn", "Định mức bình thường"},
                {"2", "Sản phẩm không đạt tiêu chuẩn ngoại quan", "", "Vỡ, rách, biến dạng", "Loại bỏ / tái chế"},
                {"3", "Dính máy đóng gói / vệ sinh dây chuyền", "", "Hao hụt cơ học", "Định mức bình thường"},
                {"4", "Nguyên nhân khác:", "", "", ""},
        };
        for (String[] data : causes) {
            boolean even = (Integer.parseInt(data[0]) % 2 == 0);
            CellStyle[] styles = {
                    even ? s.numEven : s.numOdd,
                    even ? s.dataEven : s.dataOdd,
                    even ? s.numEven : s.numOdd,
                    even ? s.dataEven : s.dataOdd,
                    even ? s.dataEven : s.dataOdd,
            };
            r = writeDataRowSpanned(sh, r, data, colSpansIII, styles, 28);
        }
        // Dòng TỔNG — merge theo đúng colSpansIII: STT+Nguyên nhân (1+2=3 cột) | Khối lượng (1 cột) | Ghi chú+Phân loại (2+2=4 cột)
        Row causeTotalRow = sh.createRow(r++);
        causeTotalRow.setHeightInPoints(28);
        putCell(causeTotalRow, 0, "TỔNG", s.totalLabel);
        sh.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 2));
        putCell(causeTotalRow, 3, NUM_FMT.format(loss), s.totalValue);
        putCell(causeTotalRow, 4, "", s.totalLabel);
        sh.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 4, 7));
        r++; // spacer

        // ── IV. ĐÁNH GIÁ VÀ KẾT LUẬN ──
        r = sectionHeader(sh, s, r, cols, "IV. ĐÁNH GIÁ VÀ KẾT LUẬN");
        r = wrappedNoteBlock(sh, s, r, cols,
                "☐  Hao hụt trong định mức cho phép — không cần xử lý thêm.\n" +
                        "☐  Hao hụt vượt định mức — cần phê duyệt cấp trên và ghi nhận chi phí bất thường.\n" +
                        "Tổng hao hụt được ghi nhận vào chi phí sản xuất (TK 627 hoặc theo quy định nội bộ): ............. đồng",
                4, "Kiến nghị / Hành động khắc phục: ........................................................................");
        r++;

        // ── V. XÁC NHẬN CÁC BÊN ──
        r = sectionHeader(sh, s, r, cols, "V. XÁC NHẬN CỦA CÁC BÊN");
        signatureBlock(sh, s, r, cols, "Tổ trưởng SX / ĐG", "Thủ kho", "Nhân viên KCS / QC", "Kế toán duyệt");
        r += 5;

        // Phê duyệt cấp trên (nếu vượt định mức) — để trống
        r = wrappedNoteBlock(sh, s, r, cols,
                "PHÊ DUYỆT CẤP TRÊN (nếu vượt định mức)\nQuản lý / Giám đốc sản xuất\n(Ký, ghi rõ họ tên và ngày ký)\n.........................................",
                4, null);

        finalizeSheet(sh, headerRowIdx, cols);
        return toBytes(wb);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers chung — layout
    // ═══════════════════════════════════════════════════════════════════════

    private int writeCompanyHeader(XSSFSheet sh, Styles s, int r, int cols) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(40);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 3));
        sh.addMergedRegion(new CellRangeAddress(r, r, cols - 2, cols - 1));
        putCell(row, 0, "CÔNG TY TNHH .....................................\n" +
                "Địa chỉ: ........................................................... Điện thoại: ................................", s.companyBox);
        putCell(row, cols - 2, "Mã số: ......................\nNgày ban hành: ............ Lần ban hành: ..............", s.companyBox);
        return r + 1;
    }

    private int titleRow(XSSFSheet sh, Styles s, int r, int cols, String title) {
        sh.createRow(r); // spacer nhỏ trước title
        r++;
        Row row = sh.createRow(r);
        row.setHeightInPoints(36);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 1));
        putCell(row, 0, title, s.title);
        return r + 1;
    }

    private int sectionHeader(XSSFSheet sh, Styles s, int r, int cols, String text) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(24);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 1));
        putCell(row, 0, text, s.sectionHeader);
        return r + 1;
    }

    /** 1 dòng "Nhãn: giá trị" trải toàn bộ độ rộng */
    private int infoRow1(XSSFSheet sh, Styles s, int r, int cols, String label, String value) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(26);
        // Chỉ merge phần "value" (cột 1 → cols-1, luôn ≥ 2 cell vì cols ≥ 3 trong mọi mẫu).
        // KHÔNG merge cột 0 (label) vì đó chỉ là 1 cell duy nhất — Apache POI báo lỗi
        // "Merged region must contain 2 or more cells" nếu cố merge 1 cell với chính nó.
        if (cols - 1 > 1) sh.addMergedRegion(new CellRangeAddress(r, r, 1, cols - 1));
        putCell(row, 0, label, s.infoLabel);
        putCell(row, 1, value != null ? value : "", s.infoValue);
        return r + 1;
    }

    /** 1 dòng chứa 2 cặp "Nhãn: giá trị" cạnh nhau */
    private int infoRow2(XSSFSheet sh, Styles s, int r, int cols, String label1, String value1, String label2, String value2) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(26);
        int half = cols / 2;
        // Chỉ merge phần "value" của mỗi cặp (luôn ≥ 2 cell). KHÔNG merge cột label
        // (label1 ở cột 0, label2 ở cột `half`) vì mỗi label chỉ chiếm 1 cell — Apache
        // POI báo lỗi "Merged region must contain 2 or more cells" nếu cố merge 1 cell.
        if (half - 1 > 1) sh.addMergedRegion(new CellRangeAddress(r, r, 1, half - 1));       // value1: cột 1..half-1
        if (cols - 1 > half + 1) sh.addMergedRegion(new CellRangeAddress(r, r, half + 1, cols - 1)); // value2: cột half+1..cols-1
        putCell(row, 0, label1, s.infoLabel);
        putCell(row, 1, value1 != null ? value1 : "", s.infoValue);
        putCell(row, half, label2, s.infoLabel);
        putCell(row, half + 1, value2 != null ? value2 : "", s.infoValue);
        return r + 1;
    }

    private int writeHeaderRow(XSSFSheet sh, Styles s, int r, String[] headers) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(40);
        for (int c = 0; c < headers.length; c++) putCell(row, c, headers[c], s.headerCell);
        return r + 1;
    }

    /**
     * Ghi 1 dòng header với độ rộng cột tuỳ chỉnh (colSpans) — dùng khi tổng số
     * cột logic của bảng < tổng số cột Excel của sheet (VD: sheet 8 cột nhưng
     * bảng chỉ có 6 cột dữ liệu) để các cột bên trong lấp đầy hết chiều ngang,
     * tránh để trống/lệch border. headers.length phải bằng colSpans.length, và
     * sum(colSpans) phải bằng đúng `cols` của sheet.
     */
    private int writeHeaderRowSpanned(XSSFSheet sh, Styles s, int r, String[] headers, int[] colSpans) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(40);
        int excelCol = 0;
        for (int i = 0; i < headers.length; i++) {
            putCell(row, excelCol, headers[i], s.headerCell);
            if (colSpans[i] > 1) {
                sh.addMergedRegion(new CellRangeAddress(r, r, excelCol, excelCol + colSpans[i] - 1));
            }
            excelCol += colSpans[i];
        }
        return r + 1;
    }

    /** Ghi 1 dòng dữ liệu theo đúng colSpans đã dùng ở writeHeaderRowSpanned (xem hàm đó) */
    private int writeDataRowSpanned(XSSFSheet sh, int r, Object[] data, int[] colSpans,
                                    CellStyle[] dataStyles, float heightPt) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(heightPt);
        int excelCol = 0;
        for (int i = 0; i < data.length; i++) {
            putCell(row, excelCol, String.valueOf(data[i]), dataStyles[i]);
            if (colSpans[i] > 1) {
                sh.addMergedRegion(new CellRangeAddress(r, r, excelCol, excelCol + colSpans[i] - 1));
            }
            excelCol += colSpans[i];
        }
        return r + 1;
    }

    /** Khối ghi chú kiểm soát chất lượng — để trống các trường hệ thống chưa lưu, đúng nhãn mẫu gốc */
    private int qualityNoteBlock(XSSFSheet sh, Styles s, int r, int cols) {
        String text = "Ghi chú kiểm soát chất lượng:\n" +
                "Hao hụt sản xuất ghi nhận: ............. kg  (Định mức: ≤ 10%)\n" +
                "Nguyên nhân hao hụt (nếu vượt định mức): ........................................................................\n" +
                "Nhiệt độ BTP khi xuất: ............. °C    │    Màu sắc / mùi: ☐ Đạt    ☐ Không đạt";
        return wrappedNoteBlock(sh, s, r, cols, text, 4, null);
    }

    private int wrappedNoteBlock(XSSFSheet sh, Styles s, int r, int cols, String text, int heightLines, String extraLine) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(16f * heightLines);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 1));
        String full = extraLine != null ? text + "\n" + extraLine : text;
        putCell(row, 0, full, s.noteBlock);
        return r + 1;
    }

    private void signatureBlock(XSSFSheet sh, Styles s, int r, int cols, String... roleLabels) {
        int n = roleLabels.length;
        int colsPerBlock = Math.max(1, cols / n);

        Row labelRow = sh.createRow(r);
        labelRow.setHeightInPoints(22);
        Row hintRow = sh.createRow(r + 1);
        hintRow.setHeightInPoints(18);
        Row spaceRow = sh.createRow(r + 2);
        spaceRow.setHeightInPoints(50);
        Row lineRow = sh.createRow(r + 3);
        lineRow.setHeightInPoints(20);

        for (int i = 0; i < n; i++) {
            int start = i * colsPerBlock;
            int end = (i == n - 1) ? cols - 1 : start + colsPerBlock - 1;
            if (end < start) end = start;
            if (start == end) {
                putCell(labelRow, start, roleLabels[i], s.signLabel);
                putCell(hintRow, start, "(Ký, ghi rõ họ tên)", s.signNote);
                putCell(spaceRow, start, "", s.signNote);
                putCell(lineRow, start, "..................................................", s.signName);
            } else {
                sh.addMergedRegion(new CellRangeAddress(r, r, start, end));
                sh.addMergedRegion(new CellRangeAddress(r + 1, r + 1, start, end));
                sh.addMergedRegion(new CellRangeAddress(r + 2, r + 2, start, end));
                sh.addMergedRegion(new CellRangeAddress(r + 3, r + 3, start, end));
                putCell(labelRow, start, roleLabels[i], s.signLabel);
                putCell(hintRow, start, "(Ký, ghi rõ họ tên)", s.signNote);
                putCell(spaceRow, start, "", s.signNote);
                putCell(lineRow, start, "..................................................", s.signName);
            }
        }
    }

    private void finalizeSheet(XSSFSheet sh, int headerRowIdx, int cols) {
        sh.setDefaultColumnWidth(14);
        for (int c = 0; c < cols; c++) sh.setColumnWidth(c, 16 * 256);
        sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sh.getPrintSetup().setLandscape(true);
        sh.setFitToPage(true);
        sh.getPrintSetup().setFitWidth((short) 1);
        sh.getPrintSetup().setFitHeight((short) 0);
        sh.getFooter().setCenter("Trang &P/&N");
    }

    private byte[] toBytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return bos.toByteArray();
    }

    private void putCell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }

    private String fmtDate(Long epochMs) {
        if (epochMs == null) return ".........../............/............";
        LocalDate d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        return d.format(VN_DATE);
    }

    private String emptyDash(String v) {
        return (v == null || v.isBlank()) ? "—" : v;
    }

    /** Gộp mã các lệnh sản xuất (WorkOrder) liên quan tới toàn bộ batch nguồn trong phiếu chuyển kho */
    private String workOrderCodesOf(SemiFinishedTransferNote note) {
        if (note == null) return null;
        return note.getLines().stream()
                .flatMap(l -> l.getSourceBatches().stream())
                .map(sb -> sb.getBatch() != null && sb.getBatch().getWorkOrder() != null
                        ? sb.getBatch().getWorkOrder().getWorkOrderCode() : null)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Styles
    // ═══════════════════════════════════════════════════════════════════════

    private static class Styles {
        final XSSFCellStyle companyBox, title, sectionHeader;
        final XSSFCellStyle infoLabel, infoValue;
        final XSSFCellStyle headerCell;
        final XSSFCellStyle dataEven, dataOdd, numEven, numOdd, inputEven, inputOdd;
        final XSSFCellStyle totalLabel, totalValue;
        final XSSFCellStyle noteBlock;
        final XSSFCellStyle signLabel, signNote, signName;

        private static final String C_NAVY = "1A3C6E";
        private static final String C_BLUE = "2E75B6";
        private static final String C_ACCENT = "D6E4F0";
        private static final String C_WHITE = "FFFFFF";
        private static final String C_INPUT = "FFFDE7";
        private static final String C_INPUT2 = "FFFBCC";
        private static final String C_MUTED = "5C5C5C";
        private static final String C_GRAY = "F0F0F0";
        private static final String C_TOTAL = "FFF3CD";

        Styles(XSSFWorkbook wb) {
            XSSFFont fCompany = fnt(wb, 11, false, "1C1C1E", false);
            XSSFFont fTitle = fnt(wb, 20, true, C_NAVY, false);
            XSSFFont fSection = fnt(wb, 13, true, C_WHITE, false);
            XSSFFont fLabel = fnt(wb, 12, true, C_NAVY, false);
            XSSFFont fValue = fnt(wb, 12, false, "1C1C1E", false);
            XSSFFont fHdr = fnt(wb, 12, true, C_WHITE, false);
            XSSFFont fData = fnt(wb, 12, false, "1C1C1E", false);
            XSSFFont fNum = fnt(wb, 12, true, C_NAVY, false);
            XSSFFont fInput = fnt(wb, 12, false, "33691E", false);
            XSSFFont fTotalLbl = fnt(wb, 13, true, "8A6D00", false);
            XSSFFont fNote = fnt(wb, 11, false, C_MUTED, false);
            XSSFFont fSign = fnt(wb, 12, true, C_NAVY, false);
            XSSFFont fSignNote = fnt(wb, 10, false, C_MUTED, true);
            XSSFFont fSignName = fnt(wb, 11, false, C_MUTED, false);

            companyBox = base(wb, fCompany, HorizontalAlignment.LEFT, true);

            title = wb.createCellStyle();
            title.setFont(fTitle);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            sectionHeader = wb.createCellStyle();
            sectionHeader.setFont(fSection);
            sectionHeader.setAlignment(HorizontalAlignment.LEFT);
            sectionHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            sectionHeader.setFillForegroundColor(new XSSFColor(hex(C_NAVY), null));
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            infoLabel = base(wb, fLabel, HorizontalAlignment.LEFT, false);
            infoLabel.setFillForegroundColor(new XSSFColor(hex("EBF3FB"), null));
            infoLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            infoValue = base(wb, fValue, HorizontalAlignment.LEFT, false);

            headerCell = wb.createCellStyle();
            headerCell.setFont(fHdr);
            headerCell.setFillForegroundColor(new XSSFColor(hex(C_NAVY), null));
            headerCell.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerCell.setAlignment(HorizontalAlignment.CENTER);
            headerCell.setVerticalAlignment(VerticalAlignment.CENTER);
            headerCell.setWrapText(true);
            border(headerCell, BorderStyle.MEDIUM, C_BLUE);

            dataEven = dataStyle(wb, fData, C_ACCENT, HorizontalAlignment.LEFT);
            dataOdd = dataStyle(wb, fData, C_WHITE, HorizontalAlignment.LEFT);
            numEven = dataStyle(wb, fNum, C_ACCENT, HorizontalAlignment.CENTER);
            numOdd = dataStyle(wb, fNum, C_WHITE, HorizontalAlignment.CENTER);
            inputEven = dataStyle(wb, fInput, C_INPUT, HorizontalAlignment.CENTER);
            inputOdd = dataStyle(wb, fInput, C_INPUT2, HorizontalAlignment.CENTER);

            totalLabel = dataStyle(wb, fTotalLbl, C_TOTAL, HorizontalAlignment.CENTER);
            totalValue = dataStyle(wb, fTotalLbl, C_TOTAL, HorizontalAlignment.CENTER);

            noteBlock = wb.createCellStyle();
            noteBlock.setFont(fNote);
            noteBlock.setAlignment(HorizontalAlignment.LEFT);
            noteBlock.setVerticalAlignment(VerticalAlignment.TOP);
            noteBlock.setWrapText(true);
            noteBlock.setFillForegroundColor(new XSSFColor(hex(C_GRAY), null));
            noteBlock.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(noteBlock, BorderStyle.THIN, "BDBDBD");

            signLabel = wb.createCellStyle();
            signLabel.setFont(fSign);
            signLabel.setAlignment(HorizontalAlignment.CENTER);
            signLabel.setVerticalAlignment(VerticalAlignment.CENTER);

            signNote = wb.createCellStyle();
            signNote.setFont(fSignNote);
            signNote.setAlignment(HorizontalAlignment.CENTER);
            signNote.setVerticalAlignment(VerticalAlignment.CENTER);

            signName = wb.createCellStyle();
            signName.setFont(fSignName);
            signName.setAlignment(HorizontalAlignment.CENTER);
            signName.setVerticalAlignment(VerticalAlignment.TOP);
        }

        private XSSFCellStyle base(XSSFWorkbook wb, XSSFFont font, HorizontalAlignment align, boolean wrap) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setAlignment(align);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setWrapText(wrap);
            border(st, BorderStyle.THIN, "BDBDBD");
            return st;
        }

        private XSSFCellStyle dataStyle(XSSFWorkbook wb, XSSFFont font, String bgHex, HorizontalAlignment align) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(new XSSFColor(hex(bgHex), null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setAlignment(align);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setWrapText(true);
            border(st, BorderStyle.THIN, "BDBDBD");
            return st;
        }

        private static void border(XSSFCellStyle st, BorderStyle bs, String hexColor) {
            XSSFColor c = new XSSFColor(hex(hexColor), null);
            st.setBorderTop(bs); st.setTopBorderColor(c);
            st.setBorderBottom(bs); st.setBottomBorderColor(c);
            st.setBorderLeft(bs); st.setLeftBorderColor(c);
            st.setBorderRight(bs); st.setRightBorderColor(c);
        }

        private static XSSFFont fnt(XSSFWorkbook wb, int pt, boolean bold, String hexColor, boolean italic) {
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) pt);
            f.setBold(bold);
            f.setItalic(italic);
            f.setColor(new XSSFColor(hex(hexColor), null));
            return f;
        }

        private static byte[] hex(String h) {
            return new byte[]{
                    (byte) Integer.parseInt(h.substring(0, 2), 16),
                    (byte) Integer.parseInt(h.substring(2, 4), 16),
                    (byte) Integer.parseInt(h.substring(4, 6), 16)
            };
        }
    }
}