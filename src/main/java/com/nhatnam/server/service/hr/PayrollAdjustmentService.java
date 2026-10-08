package com.nhatnam.server.service.hr;

import com.nhatnam.server.entity.MonthlyAdjustment;
import com.nhatnam.server.entity.MonthlyAdjustment.Type;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.AllowanceLabelRepository;
import com.nhatnam.server.repository.MonthlyAdjustmentRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.attendance.PayrollAdjustmentTemplateService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * IMPORT THƯỞNG & PHỤ CẤP THEO THÁNG từ file Excel do OWNER tải lên.
 *
 * <h3>Nguyên tắc</h3>
 * <ul>
 *   <li><b>Khớp bằng ID nhân viên</b>, không khớp bằng họ tên. Tên trong file chỉ
 *       để người nhập nhìn; lệch tên thì cảnh báo chứ không chặn.</li>
 *   <li><b>Import lại = thay thế</b> toàn bộ khoản cùng loại của tháng đó. File
 *       Excel là nguồn sự thật duy nhất, tránh cộng dồn khi tải lên nhiều lần.</li>
 *   <li><b>Nhãn phụ cấp phải có sẵn</b> trong danh mục. Nhãn lạ bị từ chối theo
 *       dòng thay vì âm thầm tạo nhãn mới — nếu không danh mục sẽ loạn vì lỗi gõ.</li>
 *   <li><b>Không nhận phụ cấp cơm</b> qua file: hệ thống đã tự tính theo số ngày
 *       đi làm, nhận thêm ở đây sẽ cộng trùng.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollAdjustmentService {

    private final MonthlyAdjustmentRepository adjustmentRepo;
    private final AllowanceLabelRepository allowanceLabelRepo;
    private final UserRepository userRepo;
    private final PayrollDepartmentResolver deptResolver;
    private final com.nhatnam.server.repository.AdjustmentImportFileRepository importFileRepo;

    /**
     * Phase 2: inject lazy để chặn upload thưởng/phụ cấp khi tháng đã CALCULATED
     * hoặc PUBLISHED. Lazy vì {@code CompanyAttendanceService} cũng tham chiếu
     * ngược (gián tiếp) → tránh circular ở lúc khởi tạo bean.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private CompanyAttendanceService companyAttendanceService;

    /** Kết quả import, trả về cho FE hiển thị. */
    @Data @Builder
    public static class ImportResult {
        private int rowsRead;
        private int saved;
        private long totalAmount;
        private String label;
        private List<String> warnings;
        private List<String> errors;

        /**
         * Số dòng BỊ BỎ QUA vì nhân viên đã có khoản cùng nhãn trong kỳ.
         * Đây là con số quan trọng nhất khi tải lại file sau lúc thêm nhân viên:
         * người cũ phải nằm hết ở đây, chỉ người mới mới được ghi vào.
         */
        private int skippedExisting;

        /** Số dòng đã có sẵn trong hệ thống TRƯỚC khi import lần này. */
        private int existingBefore;

        /** TRUE nếu nhãn này đã từng được tải lên trong kỳ. */
        private boolean labelExisted;

        /**
         * Danh sách nhân viên ĐÃ được lưu — FE hiển thị "Đã xử lý" ở modal
         * kết quả import (tên, khoản, số tiền). Bao gồm cả dòng bị skip vì đã
         * có (để owner thấy đủ danh sách người đã có tiền trong kỳ).
         */
        private List<ImportedItem> savedItems;

        /**
         * Danh sách các dòng KHÔNG khớp được nhân viên nào của bộ phận —
         * import nhầm file (VD file Kinh doanh vào tab Xưởng) sẽ rơi hết vào
         * đây, hiển thị tên gõ trong file để OWNER kiểm tra.
         */
        private List<UnmatchedItem> unmatchedItems;
    }

    /** 1 dòng đã lưu — dùng để hiển thị "Đã xử lý" ở modal kết quả import. */
    @Data @Builder
    public static class ImportedItem {
        private Long userId;
        private String employeeName;
        private String label;
        private long amount;
        /** true = dòng đã có từ trước (skip vì đã có nhãn cùng khoản). */
        private boolean alreadyExisted;
    }

    /** 1 dòng không tìm được nhân viên — hiển thị "Không tìm thấy" ở modal. */
    @Data @Builder
    public static class UnmatchedItem {
        private int rowNumber;
        private String rawName;       // tên gõ trong file
        private String rawIdString;   // ID gõ trong file (chuỗi hiển thị, có thể rỗng)
        private String label;         // với ALLOWANCE có thể có nhiều nhãn/dòng
        private Long amount;
        private String reason;        // "Không tìm thấy…", "Không thuộc bộ phận…"
    }

    /** Một khoản thưởng đã có trong kỳ — FE liệt kê để biết đã tải những gì. */
    @Data @Builder
    public static class AdjustmentBatch {
        private String label;
        private int employeeCount;
        private long totalAmount;
        private Long createdAt;
    }

    /** Kết quả preview file đã import — 1 dòng / (user, khoản) cho OWNER xem lại. */
    @Data @Builder
    public static class PreviewResult {
        private String type;               // BONUS | ALLOWANCE
        private String label;              // nhãn cụ thể (khi type=BONUS)
        private String department;
        private int rowCount;
        private long totalAmount;
        private List<PreviewRow> rows;

        // ── File gốc (nếu có) ───────────────────────────────────────────────
        /** Tên file gốc user đã tải lên. */
        private String fileName;
        /** Header cột từ file gốc. */
        private List<String> fileHeaders;
        /** Nội dung ô của từng dòng — dùng để render bảng "y hệt file gốc". */
        private List<List<String>> fileRows;
    }

    @Data @Builder
    public static class PreviewRow {
        private Long userId;
        private String employeeName;
        private String position;
        private String label;              // với ALLOWANCE mỗi dòng có label riêng
        private long amount;
        private Long createdAt;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRUY VẤN
    // ══════════════════════════════════════════════════════════════════════════

    /** Khoản theo tháng của CẢ KỲ, gom theo userId — nạp 1 lần khi dựng bảng lương. */
    @Transactional(readOnly = true)
    public Map<Long, List<MonthlyAdjustment>> byUserForPeriod(int month, int year) {
        Map<Long, List<MonthlyAdjustment>> out = new HashMap<>();
        for (MonthlyAdjustment a : adjustmentRepo.findByPeriod(month, year)) {
            if (a.getUser() == null) continue;
            out.computeIfAbsent(a.getUser().getId(), k -> new ArrayList<>()).add(a);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<MonthlyAdjustment> forUser(Long userId, int month, int year) {
        return adjustmentRepo.findByUserAndPeriod(userId, month, year);
    }

    /** Số dòng đã import của từng loại — FE hiện "Đã có / Chưa có" (RIÊNG bộ phận). */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(int month, int year, String department) {
        // Đếm dựa trên list rồi count để không đẻ thêm 2 native query nếu dept null.
        List<MonthlyAdjustment> all = adjustmentRepo.findByPeriod(month, year);
        long bonus = 0, allow = 0;
        for (MonthlyAdjustment a : all) {
            if (department != null && !department.isBlank()
                    && a.getDepartment() != null && !department.equals(a.getDepartment())) continue;
            if (a.getType() == Type.BONUS) bonus++;
            else if (a.getType() == Type.ALLOWANCE) allow++;
        }
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("bonus", bonus); m.put("allowance", allow);
        return m;
    }

    /** Backwards-compat: đếm tất cả, không filter bộ phận. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(int month, int year) { return counts(month, year, null); }

    @Transactional
    public void clear(int month, int year, Type type, String department) {
        // Phase 2: không cho xoá thưởng/phụ cấp đã bị đưa vào bảng lương đã tính.
        companyAttendanceService.assertCanUploadAdjustments(month, year);

        List<MonthlyAdjustment> all = adjustmentRepo.findByMonthAndYearAndType(month, year, type);
        if (department == null || department.isBlank()) {
            // Giữ nguyên khoản AUTO_* để lifecycle "Mở lại" xoá riêng (clear MANUAL ở đây).
            adjustmentRepo.deleteAll(all.stream()
                    .filter(a -> a.getSource() != MonthlyAdjustment.Source.AUTO_OT)
                    .toList());
        } else {
            adjustmentRepo.deleteAll(all.stream()
                    .filter(a -> a.getSource() != MonthlyAdjustment.Source.AUTO_OT)
                    .filter(a -> a.getDepartment() == null || department.equals(a.getDepartment()))
                    .toList());
        }
    }

    @Transactional
    public void clear(int month, int year, Type type) { clear(month, year, type, null); }

    // ══════════════════════════════════════════════════════════════════════════
    // IMPORT THƯỞNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * IMPORT MỘT KHOẢN THƯỞNG.
     *
     * <h3>Vì sao KHÔNG xoá rồi ghi lại như phụ cấp</h3>
     * Một tháng có thể có nhiều khoản thưởng khác nhau (lì xì Tết, thưởng chuyên
     * cần, thưởng dự án…), mỗi khoản một file. Nếu vẫn xoá theo (kỳ + loại) như
     * trước thì tải file thứ hai lên sẽ thổi bay khoản thứ nhất — mà lỗi này im
     * lặng, chỉ lộ ra khi kế toán phát hiện thiếu tiền ở phiếu lương.
     *
     * <h3>Quy tắc</h3>
     * Phạm vi thao tác là (kỳ + BONUS + NHÃN). Trong một nhãn, mỗi nhân viên chỉ
     * có đúng một dòng:
     * <ul>
     *   <li>Nhân viên ĐÃ CÓ khoản mang nhãn này → BỎ QUA, giữ nguyên số cũ.</li>
     *   <li>Nhân viên CHƯA CÓ → thêm mới.</li>
     * </ul>
     * Nhờ vậy khi thêm người giữa tháng, OWNER chỉ cần tải lại đúng file cũ:
     * người cũ không bị đụng tới, người mới được bổ sung.
     *
     * <p>Muốn SỬA số tiền đã nhập thì xoá hẳn khoản đó bằng
     * {@link #clearBonusLabel} rồi tải lại — cố tình bắt phải thao tác rõ ràng,
     * để không ai vô tình ghi đè tiền thưởng đã chốt.
     */
    @Transactional
    public ImportResult importBonus(MultipartFile file, int month, int year, String department) {
        // Phase 2: chặn ngay nếu lương tháng đã được tính (CALCULATED/PUBLISHED).
        // Thông báo lỗi tiếng Việt sẽ hiển thị thẳng ở FE, nhắc OWNER phải Mở
        // lại tháng trước khi upload thưởng.
        companyAttendanceService.assertCanUploadAdjustments(month, year);

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<MonthlyAdjustment> batch = new ArrayList<>();
        List<ImportedItem> savedItems = new ArrayList<>();
        List<UnmatchedItem> unmatchedItems = new ArrayList<>();
        int rowsRead = 0, skipped = 0;
        long total = 0;
        String label;
        int existingBefore;
        byte[] fileBytes;

        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("Không đọc được file: " + e.getMessage());
        }

        try (InputStream in = new java.io.ByteArrayInputStream(fileBytes);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);

            label = str(cell(sh, PayrollAdjustmentTemplateService.BONUS_LABEL_ROW,
                    PayrollAdjustmentTemplateService.BONUS_LABEL_COL));
            if (label == null || label.isBlank())
                throw new IllegalArgumentException(
                        "Chưa điền \"Nhãn thưởng\" ở ô B2 — đây là tên khoản thưởng dùng chung cho cả file.");
            label = label.trim();

            // "Đã có" tính TRONG PHẠM VI bộ phận đang thao tác. Cho phép trùng
            // nhãn giữa 2 bộ phận khác nhau (VD Chuyên cần cho cả Xưởng và Tài xế).
            List<MonthlyAdjustment> existing = adjustmentRepo
                    .findByPeriodTypeAndLabel(month, year, Type.BONUS, label)
                    .stream()
                    .filter(a -> department == null || department.isBlank()
                            || a.getDepartment() == null || department.equals(a.getDepartment()))
                    .toList();
            existingBefore = existing.size();
            Map<Long, Long> alreadyHave = new HashMap<>();  // userId → amount (để hiện lại)
            for (MonthlyAdjustment a : existing) {
                if (a.getUser() != null) alreadyHave.put(a.getUser().getId(),
                        a.getAmount() != null ? a.getAmount() : 0L);
            }

            // Match theo ID trong DB (globally). Template BE sinh ra đã có sẵn
            // ID user thuộc đúng bộ phận, user chỉ điền số tiền. Không tự thêm
            // scope theo bộ phận vì user có thể mở nhầm tab so với file đúng.
            Map<Long, User> byId = userIndex();

            Set<Long> seenInFile = new HashSet<>();

            // Bỏ 4 dòng đầu (title, nhãn thưởng, guide, header) — chỉ xử lý data.
            for (int r = PayrollAdjustmentTemplateService.BONUS_DATA_START_ROW;
                 r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;

                String rawId = str(cell(row, 0));
                String rawName = str(cell(row, 1));
                long amount = asMoney(cell(row, 2));

                // Dòng trống hoàn toàn → bỏ qua yên lặng.
                boolean allBlank = (rawId == null || rawId.isBlank())
                        && (rawName == null || rawName.isBlank())
                        && amount <= 0;
                if (allBlank) continue;

                rowsRead++;

                // Match theo ID trong DB. Nếu không tìm thấy → unmatched.
                User u = null;
                Long parsedId = tryParseLong(rawId);
                if (parsedId != null) u = byId.get(parsedId);

                if (u == null) {
                    unmatchedItems.add(UnmatchedItem.builder()
                            .rowNumber(r + 1)
                            .rawName(rawName != null ? rawName.trim() : "")
                            .rawIdString(rawId != null ? rawId.trim() : "")
                            .label(label)
                            .amount(amount > 0 ? amount : null)
                            .reason(parsedId == null
                                    ? "Thiếu ID nhân viên"
                                    : "Không tìm thấy nhân viên ID " + parsedId + " trong database")
                            .build());
                    continue;
                }

                // Bỏ qua tài khoản đã bị KHOÁ / XOÁ MỀM — không được nhận thưởng
                // (đưa vào unmatched để OWNER thấy dòng đã bị loại và biết lý do).
                if (u.isLockAccount() || u.isDeleted()) {
                    unmatchedItems.add(UnmatchedItem.builder()
                            .rowNumber(r + 1)
                            .rawName(rawName != null ? rawName.trim() : u.getFullName())
                            .rawIdString(rawId != null ? rawId.trim() : String.valueOf(u.getId()))
                            .label(label)
                            .amount(amount > 0 ? amount : null)
                            .reason(u.isDeleted()
                                    ? "Nhân viên đã bị xoá — không tính thưởng"
                                    : "Tài khoản đang bị khoá — không tính thưởng")
                            .build());
                    log.info("[Adjustment] Bỏ qua thưởng \"{}\" cho {} (id={}): {}.",
                            label, u.getFullName(), u.getId(),
                            u.isDeleted() ? "đã xoá" : "đang bị khoá");
                    continue;
                }

                if (alreadyHave.containsKey(u.getId())) {
                    skipped++;
                    savedItems.add(ImportedItem.builder()
                            .userId(u.getId()).employeeName(u.getFullName())
                            .label(label).amount(alreadyHave.get(u.getId()))
                            .alreadyExisted(true).build());
                    continue;
                }
                if (!seenInFile.add(u.getId())) {
                    warnings.add("Dòng %d: nhân viên %s xuất hiện nhiều lần trong file — chỉ lấy dòng đầu"
                            .formatted(r + 1, u.getFullName()));
                    continue;
                }

                if (amount <= 0) continue;             // để trống = không có thưởng

                batch.add(MonthlyAdjustment.builder()
                        .user(u).month(month).year(year)
                        .type(Type.BONUS).label(label).amount(amount)
                        .department(department)
                        .build());
                savedItems.add(ImportedItem.builder()
                        .userId(u.getId()).employeeName(u.getFullName())
                        .label(label).amount(amount).alreadyExisted(false).build());
                total += amount;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Adjustment] Lỗi đọc file thưởng {}/{}", month, year, e);
            throw new IllegalArgumentException("Không đọc được file Excel: " + e.getMessage());
        }

        // KHÔNG xoá gì cả — chỉ thêm phần còn thiếu.
        adjustmentRepo.saveAll(batch);

        // Lưu (thay thế) file gốc của khoản này để OWNER preview lại sau này.
        saveImportedFile(file, fileBytes, month, year, Type.BONUS, label, department);

        if (existingBefore > 0 && batch.isEmpty()) {
            warnings.add(("Khoản \"%s\" đã có đủ %d nhân viên trong tháng %d/%d — "
                    + "không có ai mới để thêm.").formatted(label, existingBefore, month, year));
        }

        log.info("[Adjustment] Thưởng \"{}\" {}/{}: đọc {} dòng · thêm mới {} · bỏ qua {} · không tìm thấy {} · tổng {}đ",
                label, month, year, rowsRead, batch.size(), skipped, unmatchedItems.size(), total);

        return ImportResult.builder()
                .rowsRead(rowsRead).saved(batch.size()).totalAmount(total)
                .label(label)
                .skippedExisting(skipped)
                .existingBefore(existingBefore)
                .labelExisted(existingBefore > 0)
                .savedItems(savedItems)
                .unmatchedItems(unmatchedItems)
                .warnings(warnings).errors(errors).build();
    }

    /** Các khoản thưởng đã có trong kỳ — FE hiện danh sách kèm nút xoá từng khoản. */
    @Transactional(readOnly = true)
    public List<AdjustmentBatch> bonusBatches(int month, int year, String department) {
        Map<String, List<MonthlyAdjustment>> byLabel = new LinkedHashMap<>();
        for (MonthlyAdjustment a : adjustmentRepo.findByPeriodAndTypeWithUser(month, year, Type.BONUS)) {
            if (department != null && !department.isBlank()
                    && a.getDepartment() != null && !department.equals(a.getDepartment())) continue;
            byLabel.computeIfAbsent(a.getLabel(), k -> new ArrayList<>()).add(a);
        }

        List<AdjustmentBatch> out = new ArrayList<>();
        byLabel.forEach((label, rows) -> out.add(AdjustmentBatch.builder()
                .label(label)
                .employeeCount(rows.size())
                .totalAmount(rows.stream().mapToLong(a -> a.getAmount() == null ? 0L : a.getAmount()).sum())
                .createdAt(rows.stream().map(MonthlyAdjustment::getCreatedAt)
                        .filter(java.util.Objects::nonNull).min(Long::compareTo).orElse(null))
                .build()));
        return out;
    }

    @Transactional(readOnly = true)
    public List<AdjustmentBatch> bonusBatches(int month, int year) { return bonusBatches(month, year, null); }

    /** Xoá ĐÚNG MỘT khoản thưởng của kỳ (chỉ trong bộ phận nếu chỉ định). */
    @Transactional
    public void clearBonusLabel(int month, int year, String label, String department) {
        // Phase 2: không cho xoá khoản thưởng khi tháng đã CALCULATED/PUBLISHED.
        companyAttendanceService.assertCanUploadAdjustments(month, year);

        if (label == null || label.isBlank())
            throw new IllegalArgumentException("Chưa chỉ định khoản thưởng cần xoá.");
        String trimmedLabel = label.trim();
        List<MonthlyAdjustment> matches = adjustmentRepo
                .findByPeriodTypeAndLabel(month, year, Type.BONUS, trimmedLabel);
        List<MonthlyAdjustment> toDelete = department == null || department.isBlank()
                ? matches
                : matches.stream()
                .filter(a -> a.getDepartment() == null || department.equals(a.getDepartment()))
                .toList();
        adjustmentRepo.deleteAll(toDelete);
        log.info("[Adjustment] Đã xoá {} dòng thưởng \"{}\" của {}/{} (dept={})",
                toDelete.size(), label, month, year, department);
    }

    @Transactional
    public void clearBonusLabel(int month, int year, String label) { clearBonusLabel(month, year, label, null); }

    // ══════════════════════════════════════════════════════════════════════════
    // PREVIEW — trả về nội dung đã import cho OWNER xem lại (không sửa)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Trả về các dòng đã import của (kỳ + loại + [label] + [department]) — mỗi
     * dòng là (user, số tiền). Nếu type=BONUS, label bắt buộc (đúng 1 khoản).
     * Nếu type=ALLOWANCE, label để trống → gom mọi khoản phụ cấp trong bộ phận.
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(int month, int year, Type type, String label, String department) {
        List<MonthlyAdjustment> items;
        if (type == Type.BONUS) {
            if (label == null || label.isBlank())
                throw new IllegalArgumentException("Thiếu 'label' để preview khoản thưởng.");
            items = adjustmentRepo.findByPeriodTypeAndLabel(month, year, Type.BONUS, label.trim());
        } else {
            items = adjustmentRepo.findByPeriodAndTypeWithUser(month, year, type);
        }
        if (department != null && !department.isBlank()) {
            items = items.stream()
                    .filter(a -> a.getDepartment() == null || department.equals(a.getDepartment()))
                    .toList();
        }
        List<PreviewRow> rows = new ArrayList<>();
        long total = 0;
        for (MonthlyAdjustment a : items) {
            long amt = a.getAmount() != null ? a.getAmount() : 0L;
            total += amt;
            rows.add(PreviewRow.builder()
                    .userId(a.getUser() != null ? a.getUser().getId() : null)
                    .employeeName(a.getUser() != null ? a.getUser().getFullName() : null)
                    .position(a.getUser() != null ? a.getUser().getPosition() : null)
                    .label(a.getLabel())
                    .amount(amt)
                    .createdAt(a.getCreatedAt())
                    .build());
        }
        rows.sort(java.util.Comparator.comparing(
                r -> r.getEmployeeName() != null ? r.getEmployeeName() : "",
                String.CASE_INSENSITIVE_ORDER));

        // Đọc lại file gốc (nếu có) để hiển thị "y hệt file đã import".
        String fileName = null;
        List<String> fileHeaders = null;
        List<List<String>> fileRows = null;
        var fileOpt = importFileRepo.findOne(month, year, type.name(),
                type == Type.BONUS ? label : null, department);
        if (fileOpt.isPresent()) {
            var f = fileOpt.get();
            fileName = f.getFileName();
            try (InputStream in = new java.io.ByteArrayInputStream(f.getContent());
                 Workbook wb = WorkbookFactory.create(in)) {
                Sheet sh = wb.getSheetAt(0);
                fileHeaders = new ArrayList<>();
                fileRows = new ArrayList<>();
                // Header của bảng data — bỏ hết title + guide + (với bonus)
                // ô "Nhãn thưởng". Header = *_DATA_START_ROW - 1.
                int headerRowIdx = type == Type.BONUS
                        ? PayrollAdjustmentTemplateService.BONUS_DATA_START_ROW - 1
                        : PayrollAdjustmentTemplateService.ALLOWANCE_DATA_START_ROW - 1;
                // Xác định số cột lớn nhất trong file
                int maxCol = 0;
                for (int r = 0; r <= sh.getLastRowNum(); r++) {
                    Row row = sh.getRow(r);
                    if (row != null) maxCol = Math.max(maxCol, row.getLastCellNum());
                }
                // Header
                Row hRow = sh.getRow(headerRowIdx);
                if (hRow != null) {
                    for (int c = 0; c < maxCol; c++) {
                        String v = str(hRow.getCell(c));
                        fileHeaders.add(v != null ? v : "");
                    }
                }
                // Dữ liệu — mọi dòng sau header. Format số kiểu VN "1.234"
                // để dễ đọc; ID user (2-3 chữ số) không có dấu chấm ngăn cách.
                for (int r = headerRowIdx + 1; r <= sh.getLastRowNum(); r++) {
                    Row row = sh.getRow(r);
                    if (row == null) continue;
                    boolean allBlank = true;
                    List<String> rowCells = new ArrayList<>();
                    for (int c = 0; c < maxCol; c++) {
                        String v = strForDisplay(row.getCell(c));
                        if (v != null && !v.isBlank()) allBlank = false;
                        rowCells.add(v != null ? v : "");
                    }
                    if (allBlank) continue;
                    fileRows.add(rowCells);
                }
            } catch (Exception e) {
                log.warn("[Adjustment] Không đọc được file gốc {}/{} {} {} {}: {}",
                        month, year, type, label, department, e.getMessage());
            }
        }

        return PreviewResult.builder()
                .type(type.name()).label(label).department(department)
                .rowCount(rows.size()).totalAmount(total)
                .rows(rows)
                .fileName(fileName).fileHeaders(fileHeaders).fileRows(fileRows)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IMPORT PHỤ CẤP
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public ImportResult importAllowance(MultipartFile file, int month, int year, String department) {
        // Phase 2: chặn khi lương đã CALCULATED/PUBLISHED — xem importBonus.
        companyAttendanceService.assertCanUploadAdjustments(month, year);

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<MonthlyAdjustment> batch = new ArrayList<>();
        List<ImportedItem> savedItems = new ArrayList<>();
        List<UnmatchedItem> unmatchedItems = new ArrayList<>();
        int rowsRead = 0;
        long total = 0;
        byte[] fileBytes;

        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("Không đọc được file: " + e.getMessage());
        }

        // Đối chiếu nhãn KHÔNG phân biệt hoa thường / dấu, nhưng LƯU theo đúng
        // chính tả trong danh mục để phiếu lương hiển thị nhất quán.
        Map<String, String> labelIndex = new HashMap<>();
        allowanceLabelRepo.findAll().forEach(l -> labelIndex.put(norm(l.getName()), l.getName()));

        try (InputStream in = new java.io.ByteArrayInputStream(fileBytes);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            // Match theo ID trong DB (globally). Không scope theo bộ phận vì
            // template BE sinh ra đã đúng danh sách bộ phận.
            Map<Long, User> byId = userIndex();

            // Bỏ 3 dòng đầu (title, guide, header) — chỉ xử lý data.
            for (int r = PayrollAdjustmentTemplateService.ALLOWANCE_DATA_START_ROW;
                 r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;

                String rawId = str(cell(row, 0));
                String rawName = str(cell(row, 1));

                // Gom các cặp (nhãn, tiền) từ cột C trở đi — cần trước để check trống.
                long sumInRow = 0;
                List<String> rawLabels = new ArrayList<>();
                for (int c = 2; c + 1 <= row.getLastCellNum(); c += 2) {
                    String lbl = str(cell(row, c));
                    long amt = asMoney(cell(row, c + 1));
                    if (amt > 0) sumInRow += amt;
                    if (lbl != null && !lbl.isBlank()) rawLabels.add(lbl.trim());
                }

                // Dòng trống hoàn toàn → bỏ qua yên lặng.
                boolean allBlank = (rawId == null || rawId.isBlank())
                        && (rawName == null || rawName.isBlank())
                        && sumInRow == 0 && rawLabels.isEmpty();
                if (allBlank) continue;

                rowsRead++;

                // Match theo ID trong DB. Nếu ID trống hoặc không tìm thấy → unmatched.
                User u = null;
                Long parsedId = tryParseLong(rawId);
                if (parsedId != null) u = byId.get(parsedId);

                if (u == null) {
                    unmatchedItems.add(UnmatchedItem.builder()
                            .rowNumber(r + 1)
                            .rawName(rawName != null ? rawName.trim() : "")
                            .rawIdString(rawId != null ? rawId.trim() : "")
                            .label(String.join(", ", rawLabels))
                            .amount(sumInRow > 0 ? sumInRow : null)
                            .reason(parsedId == null
                                    ? "Thiếu ID nhân viên"
                                    : "Không tìm thấy nhân viên ID " + parsedId + " trong database")
                            .build());
                    continue;
                }

                // Duyệt các cặp (Nhãn, Số tiền) từ cột C trở đi
                for (int c = 2; c + 1 <= row.getLastCellNum(); c += 2) {
                    String rawLabel = str(cell(row, c));
                    long amount = asMoney(cell(row, c + 1));

                    if ((rawLabel == null || rawLabel.isBlank()) && amount <= 0) continue;

                    if (rawLabel == null || rawLabel.isBlank()) {
                        errors.add("Dòng %d (%s): có số tiền %,dđ nhưng chưa chọn khoản phụ cấp"
                                .formatted(r + 1, u.getFullName(), amount));
                        continue;
                    }
                    if (isMealLabel(rawLabel)) {
                        warnings.add("Dòng %d (%s): bỏ qua \"%s\" — phụ cấp cơm do hệ thống tự tính theo ngày đi làm"
                                .formatted(r + 1, u.getFullName(), rawLabel));
                        continue;
                    }
                    String canonical = labelIndex.get(norm(rawLabel));
                    if (canonical == null) {
                        errors.add("Dòng %d (%s): khoản \"%s\" không có trong danh mục phụ cấp"
                                .formatted(r + 1, u.getFullName(), rawLabel));
                        continue;
                    }
                    if (amount <= 0) continue;

                    batch.add(MonthlyAdjustment.builder()
                            .user(u).month(month).year(year)
                            .type(Type.ALLOWANCE).label(canonical).amount(amount)
                            .department(department)
                            .build());
                    savedItems.add(ImportedItem.builder()
                            .userId(u.getId()).employeeName(u.getFullName())
                            .label(canonical).amount(amount).alreadyExisted(false).build());
                    total += amount;
                }
            }
        } catch (Exception e) {
            log.error("[Adjustment] Lỗi đọc file phụ cấp {}/{}", month, year, e);
            throw new IllegalArgumentException("Không đọc được file Excel: " + e.getMessage());
        }

        // Xoá phụ cấp cũ CHỈ trong bộ phận này (giữ bộ phận khác).
        List<MonthlyAdjustment> oldAll = adjustmentRepo.findByMonthAndYearAndType(month, year, Type.ALLOWANCE);
        List<MonthlyAdjustment> toDelete = department == null || department.isBlank()
                ? oldAll
                : oldAll.stream()
                .filter(a -> a.getDepartment() == null || department.equals(a.getDepartment()))
                .toList();
        adjustmentRepo.deleteAll(toDelete);
        adjustmentRepo.flush();
        adjustmentRepo.saveAll(batch);

        // Lưu file gốc.
        saveImportedFile(file, fileBytes, month, year, Type.ALLOWANCE, null, department);

        log.info("[Adjustment] Phụ cấp {}/{}: {} dòng, lưu {} khoản, không tìm thấy {}, tổng {}đ",
                month, year, rowsRead, batch.size(), unmatchedItems.size(), total);

        return ImportResult.builder()
                .rowsRead(rowsRead).saved(batch.size()).totalAmount(total)
                .savedItems(savedItems)
                .unmatchedItems(unmatchedItems)
                .warnings(warnings).errors(errors).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH
    // ══════════════════════════════════════════════════════════════════════════

    private Map<Long, User> userIndex() {
        Map<Long, User> m = new HashMap<>();
        for (User u : userRepo.findAll()) if (!u.isDeleted()) m.put(u.getId(), u);
        return m;
    }

    private void warnIfNameMismatch(List<String> warnings, int rowIdx, User u, String nameInFile) {
        if (nameInFile == null || nameInFile.isBlank()) return;
        if (!norm(nameInFile).equals(norm(u.getFullName()))) {
            warnings.add("Dòng %d: tên trong file \"%s\" khác hồ sơ \"%s\" — đã dùng theo ID"
                    .formatted(rowIdx + 1, nameInFile, u.getFullName()));
        }
    }

    /** Bỏ dấu + gộp khoảng trắng + chữ thường, để so khớp mềm. */
    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static boolean isMealLabel(String label) {
        return norm(label).contains("com");
    }

    private static Cell cell(Sheet sh, int r, int c) {
        Row row = sh.getRow(r);
        return row != null ? row.getCell(c) : null;
    }

    private static Cell cell(Row row, int c) {
        return row != null ? row.getCell(c) : null;
    }

    private static String str(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
            case FORMULA -> {
                try { yield c.getStringCellValue().trim(); }
                catch (Exception e) { yield null; }
            }
            default -> null;
        };
    }

    /**
     * Giống {@link #str} nhưng dùng cho HIỂN THỊ trên UI: số được format kiểu
     * Việt Nam "1.234.567" thay vì "1234567" cho dễ đọc.
     */
    private static String strForDisplay(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.format(java.util.Locale.GERMANY, "%,d", (long) d);
                }
                yield String.format(java.util.Locale.GERMANY, "%,f", d);
            }
            case FORMULA -> {
                try { yield c.getStringCellValue().trim(); }
                catch (Exception e) { yield null; }
            }
            default -> null;
        };
    }

    private static Long asLong(Cell c) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC) return (long) c.getNumericCellValue();
            String s = str(c);
            if (s == null || s.isBlank()) return null;
            return Long.parseLong(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Đọc số tiền. Chấp nhận cả ô số lẫn ô text kiểu "1.200.000" hay "1,200,000"
     * — người dùng hay copy/paste từ nơi khác nên định dạng rất tuỳ hứng.
     */
    private static long asMoney(Cell c) {
        if (c == null) return 0L;
        try {
            if (c.getCellType() == CellType.NUMERIC) return Math.round(c.getNumericCellValue());
            String s = str(c);
            if (s == null || s.isBlank()) return 0L;
            String digits = s.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0L : Long.parseLong(digits);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Long tryParseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return null; }
    }

    /**
     * Index nhân viên trong phạm vi 1 bộ phận, tra theo TÊN (không phân biệt
     * hoa/thường & không dấu). Dùng khi user không điền ID mà chỉ điền tên.
     * Nếu department null → nhận toàn bộ user (compat cũ).
     */
    private NameIndex buildNameIndex(String department) {
        List<User> pool;
        if (department == null || department.isBlank()) {
            pool = userRepo.findAll();
        } else {
            try {
                var dept = com.nhatnam.server.enumtype.PayrollDepartment.valueOf(department);
                pool = deptResolver.employeesOf(dept);
            } catch (IllegalArgumentException e) {
                pool = userRepo.findAll();
            }
        }
        Map<String, User> byName = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (User u : pool) {
            if (u.getFullName() != null) byName.put(norm(u.getFullName()), u);
            ids.add(u.getId());
        }
        return new NameIndex(byName, ids);
    }

    private record NameIndex(Map<String, User> byName, Set<Long> ids) {
        boolean contains(Long id) { return id != null && ids.contains(id); }
        User findByName(String rawName) {
            if (rawName == null) return null;
            return byName.get(PayrollAdjustmentService.norm(rawName));
        }
    }

    /** Lưu/thay thế file Excel gốc để OWNER preview lại sau này. */
    private void saveImportedFile(MultipartFile file, byte[] bytes, int month, int year,
                                  Type type, String label, String department) {
        try {
            importFileRepo.deleteByKey(month, year, type.name(), label, department);
            importFileRepo.flush();
            importFileRepo.save(com.nhatnam.server.entity.AdjustmentImportFile.builder()
                    .month(month).year(year)
                    .type(type.name()).label(label).department(department)
                    .fileName(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .content(bytes)
                    .build());
        } catch (Exception e) {
            log.warn("[Adjustment] Không lưu được file gốc {}/{} {} {} {}: {}",
                    month, year, type, label, department, e.getMessage());
        }
    }
}