package com.nhatnam.server.service.hr;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.hr.PayrollDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.repository.*;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.utils.PayrollTaxCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollService {

    private final PayrollBatchRepository batchRepository;
    private final PayslipRepository payslipRepository;
    private final EmployeeSalaryRepository salaryRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Value("${application.security.jwt.secret-key}")
    private String jwtSecretKey;

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX = "payroll-batch:";
    private final Map<String, Long> usedTokens = new ConcurrentHashMap<>();

    // ── Column layout của file export/import ───────────────────────────────────
    private static final int COL_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_DEPARTMENT = 2;
    private static final int COL_DIVISION = 3;
    private static final int COL_POSITION = 4;
    private static final int COL_BASE_SALARY = 5;
    private static final int COL_DEPENDENTS = 6;
    private static final int COL_STANDARD_WORKDAYS = 7;
    private static final int COL_ACTUAL_WORKDAYS = 8;
    private static final int COL_BONUS = 9;
    private static final int COL_ALLOWANCE = 10;
    private static final int COL_INSURANCE_SALARY = 11;

    // ── Helpers chung ───────────────────────────────────────────────────────────

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("Người dùng không tồn tại"));
    }

    private String makeToken(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(jwtSecretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((TOKEN_PREFIX + payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) { throw new RuntimeException("Không thể tạo token", e); }
    }

    private String generateToken() {
        String ts = String.valueOf(System.currentTimeMillis());
        return ts + ":" + makeToken(ts);
    }

    private void validateAndConsumeToken(String token) {
        if (token == null || token.isBlank())
            throw new BusinessException("File không hợp lệ: thiếu mã xác thực. Vui lòng Export lại file mới.");
        String[] parts = token.split(":", 2);
        if (parts.length != 2 || !makeToken(parts[0]).equals(parts[1]))
            throw new BusinessException("File không hợp lệ: mã xác thực không khớp. Vui lòng Export lại file mới.");
        if (usedTokens.containsKey(token))
            throw new BusinessException("File này đã được import rồi. Vui lòng tạo phiếu lương mới để import lại.");
        usedTokens.put(token, System.currentTimeMillis());
    }

    /** Số công chuẩn trong tháng: T2-T6 = 1 công, T7 = 0.5 công, CN = 0. */
    private double standardWorkdaysOf(int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        double total = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY) total += 0.5;
            else if (dow != DayOfWeek.SUNDAY) total += 1.0;
        }
        return total;
    }

    // ── 1. Tạo phiếu lương (batch) + export Excel ───────────────────────────────

    @Transactional
    public byte[] createBatchAndExport(int month, int year) throws Exception {
        User actor = currentUser();

        PayrollBatch batch = batchRepository.findByMonthAndYear(month, year).orElse(null);
        if (batch != null && !"DRAFT".equals(batch.getStatus()))
            throw new BusinessException("Đã có phiếu lương tháng " + month + "/" + year
                    + " ở trạng thái " + batch.getStatus() + ". Không thể tạo lại.");

        List<EmployeeSalary> approvedSalaries = salaryRepository.findLatestApprovedPerUser();
        Map<Long, EmployeeSalary> salaryByUser = approvedSalaries.stream()
                .filter(s -> s.getUser() != null && !s.getUser().isLockAccount())
                .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s, (a, b) -> a));

        if (salaryByUser.isEmpty())
            throw new BusinessException("Chưa có nhân viên nào được duyệt lương cơ bản. Vui lòng duyệt lương trước khi tạo phiếu lương.");

        long now = System.currentTimeMillis();
        String token = generateToken();

        if (batch == null) {
            batch = PayrollBatch.builder()
                    .month(month).year(year).status("DRAFT")
                    .exportToken(token)
                    .employeeCount(salaryByUser.size())
                    .createdBy(actor)
                    .createdAt(now).updatedAt(now)
                    .build();
        } else {
            batch.setExportToken(token);
            batch.setEmployeeCount(salaryByUser.size());
            batch.setUpdatedAt(now);
        }
        batch = batchRepository.save(batch);

        double standardWorkdays = standardWorkdaysOf(month, year);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet meta = wb.createSheet("META");
            wb.setSheetHidden(wb.getSheetIndex("META"), true);
            Row metaRow = meta.createRow(0);
            metaRow.createCell(0).setCellValue(token);
            metaRow.createCell(1).setCellValue(salaryByUser.size());
            metaRow.createCell(2).setCellValue(batch.getId());
            metaRow.createCell(3).setCellValue(month);
            metaRow.createCell(4).setCellValue(year);

            XSSFSheet sheet = wb.createSheet("Tinh luong " + month + "-" + year);

            XSSFCellStyle headerStyle = wb.createCellStyle();
            XSSFFont headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setWrapText(true);

            XSSFCellStyle lockStyle = wb.createCellStyle();
            lockStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            lockStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] headers = {
                    "ID", "Ten nhan vien", "Bo phan", "Phong ban", "Chuc vu",
                    "Luong truoc thue (da duyet)", "So nguoi phu thuoc",
                    "Cong chuan thang", "Cong thuc te", "Thuong", "Phu cap",
                    "Luong dong BHXH/BHYT/BHTN"
            };
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, i == COL_NAME ? 7000 : 5200);
            }
            sheet.createFreezePane(0, 1);

            List<User> users = salaryByUser.keySet().stream()
                    .map(id -> salaryByUser.get(id).getUser())
                    .sorted(Comparator.comparing(User::getId))
                    .collect(Collectors.toList());

            int rowIdx = 1;
            for (User u : users) {
                EmployeeSalary sal = salaryByUser.get(u.getId());
                Row row = sheet.createRow(rowIdx++);

                Cell idCell = row.createCell(COL_ID);
                idCell.setCellValue(u.getId());
                idCell.setCellStyle(lockStyle);

                Cell nameCell = row.createCell(COL_NAME);
                nameCell.setCellValue(u.getFullName() != null ? u.getFullName() : u.getUsername());
                nameCell.setCellStyle(lockStyle);

                row.createCell(COL_DEPARTMENT).setCellValue(u.getDepartment() != null ? u.getDepartment() : "");
                row.createCell(COL_DIVISION).setCellValue(u.getDivision() != null ? u.getDivision() : "");
                row.createCell(COL_POSITION).setCellValue(u.getPosition() != null ? u.getPosition() : "");

                Cell baseCell = row.createCell(COL_BASE_SALARY);
                baseCell.setCellValue(sal.getBaseSalary() != null ? sal.getBaseSalary() : 0);
                baseCell.setCellStyle(lockStyle);

                row.createCell(COL_DEPENDENTS).setCellValue(0);

                Cell stdCell = row.createCell(COL_STANDARD_WORKDAYS);
                stdCell.setCellValue(standardWorkdays);

                row.createCell(COL_ACTUAL_WORKDAYS).setCellValue(standardWorkdays);
                row.createCell(COL_BONUS).setCellValue(0);
                row.createCell(COL_ALLOWANCE).setCellValue(0);
                Long insSal = sal.getInsuranceSalary() != null && sal.getInsuranceSalary() > 0
                        ? sal.getInsuranceSalary()
                        : sal.getBaseSalary();
                row.createCell(COL_INSURANCE_SALARY).setCellValue(insSal != null ? insSal : 0);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── 2. Import Excel → tính lương → tạo Payslip ──────────────────────────────

    @Transactional
    public PayrollBatchDto importBatch(MultipartFile file) throws Exception {
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet meta = wb.getSheet("META");
            if (meta == null || meta.getRow(0) == null)
                throw new BusinessException("File không hợp lệ: thiếu sheet xác thực. Vui lòng tạo phiếu lương và export lại.");

            Row metaRow = meta.getRow(0);
            String token = cellStr(metaRow, 0);
            validateAndConsumeToken(token);

            long exportedCount = (long) getDouble(metaRow, 1);
            long batchId = (long) getDouble(metaRow, 2);
            int month = (int) getDouble(metaRow, 3);
            int year = (int) getDouble(metaRow, 4);

            PayrollBatch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy phiếu lương tương ứng — vui lòng tạo lại."));
            if (!"DRAFT".equals(batch.getStatus()))
                throw new BusinessException("Phiếu lương tháng " + month + "/" + year
                        + " đã ở trạng thái " + batch.getStatus() + " — không thể import lại.");

            Sheet sheet = wb.getSheet("Tinh luong " + month + "-" + year);
            if (sheet == null)
                throw new BusinessException("File không hợp lệ: thiếu sheet dữ liệu lương.");

            List<EmployeeSalary> approvedSalaries = salaryRepository.findLatestApprovedPerUser();
            Map<Long, EmployeeSalary> salaryByUser = approvedSalaries.stream()
                    .filter(s -> s.getUser() != null && !s.getUser().isLockAccount())
                    .collect(Collectors.toMap(s -> s.getUser().getId(), s -> s, (a, b) -> a));

            List<Long> fileIds = new ArrayList<>();
            List<Row> dataRows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String idStr = cellStr(row, COL_ID);
                if (idStr == null || idStr.isBlank()) continue;
                Long id;
                try { id = Long.parseLong(idStr.trim()); } catch (Exception e) { continue; }
                fileIds.add(id);
                dataRows.add(row);
            }

            if (exportedCount > 0 && fileIds.size() != exportedCount)
                throw new BusinessException("Số lượng nhân viên trong file (" + fileIds.size()
                        + ") không khớp với số lượng đã export (" + exportedCount
                        + "). Vui lòng không thêm/xóa dòng — tạo phiếu lương mới nếu danh sách nhân viên đã thay đổi.");
            if (fileIds.size() != salaryByUser.size())
                throw new BusinessException("Số lượng nhân viên trong file (" + fileIds.size()
                        + ") không khớp với số lượng nhân viên đã duyệt lương hiện tại (" + salaryByUser.size()
                        + "). Vui lòng tạo phiếu lương mới để import lại.");

            Set<Long> idSet = new HashSet<>(fileIds);
            if (idSet.size() != fileIds.size())
                throw new BusinessException("File chứa ID nhân viên bị trùng lặp. Vui lòng tạo phiếu lương mới.");

            List<Long> unknownIds = fileIds.stream().filter(id -> !salaryByUser.containsKey(id)).toList();
            if (!unknownIds.isEmpty())
                throw new BusinessException("File chứa ID nhân viên không tồn tại hoặc chưa được duyệt lương: "
                        + unknownIds + ". Vui lòng tạo phiếu lương mới.");

            payslipRepository.deleteByBatchId(batchId);

            long now = System.currentTimeMillis();
            List<Payslip> payslips = new ArrayList<>();

            for (Row row : dataRows) {
                Long userId = Long.parseLong(cellStr(row, COL_ID).trim());
                EmployeeSalary sal = salaryByUser.get(userId);
                User u = sal.getUser();

                long baseSalary = sal.getBaseSalary() != null ? sal.getBaseSalary() : 0L;
                int dependents = (int) getDouble(row, COL_DEPENDENTS);
                double standardWorkdays = getDouble(row, COL_STANDARD_WORKDAYS);
                if (standardWorkdays <= 0) standardWorkdays = standardWorkdaysOf(month, year);
                double actualWorkdays = getDouble(row, COL_ACTUAL_WORKDAYS);
                long bonus = (long) getDouble(row, COL_BONUS);
                long allowance = (long) getDouble(row, COL_ALLOWANCE);
                long insuranceSalary = (long) getDouble(row, COL_INSURANCE_SALARY);

                PayrollTaxCalculator.PayslipResult r = PayrollTaxCalculator.calc(
                        baseSalary, standardWorkdays, actualWorkdays, bonus, allowance, insuranceSalary, dependents);

                Payslip slip = Payslip.builder()
                        .batch(batch)
                        .user(u)
                        .userFullName(u.getFullName() != null ? u.getFullName() : u.getUsername())
                        .department(u.getDepartment())
                        .division(u.getDivision())
                        .position(u.getPosition())
                        .baseSalary(baseSalary)
                        .dependents(dependents)
                        .standardWorkdays(standardWorkdays)
                        .actualWorkdays(actualWorkdays)
                        .bonus(bonus)
                        .allowance(allowance)
                        .insuranceSalary(insuranceSalary)
                        .actualSalary(r.actualSalary)
                        .grossSalary(r.grossSalary)
                        .socialInsuranceAmount(r.socialInsuranceAmount)
                        .healthInsuranceAmount(r.healthInsuranceAmount)
                        .unemploymentInsuranceAmount(r.unemploymentInsuranceAmount)
                        .totalInsuranceAmount(r.totalInsuranceAmount)
                        .preTaxIncome(r.preTaxIncome)
                        .personalDeduction(r.personalDeduction)
                        .dependentDeduction(r.dependentDeduction)
                        .taxableIncome(r.taxableIncome)
                        .personalIncomeTax(r.personalIncomeTax)
                        .netSalary(r.netSalary)
                        .createdAt(now).updatedAt(now)
                        .build();
                payslips.add(slip);
            }
            payslipRepository.saveAll(payslips);

            batch.setStatus("PENDING_APPROVAL");
            batch.setImportedAt(now);
            batch.setUpdatedAt(now);
            batch = batchRepository.save(batch);

            notificationService.sendToRole("OWNER", "PAYROLL_PENDING",
                    "Phiếu lương tháng " + month + "/" + year + " (" + payslips.size()
                            + " nhân viên) đã sẵn sàng — chờ duyệt",
                    "{\"batchId\":" + batch.getId() + "}");

            return toBatchDto(batch);
        }
    }

    // ── 3. Duyệt / từ chối batch (toàn bộ 1 lần) ────────────────────────────────

    @Transactional
    public PayrollBatchDto approveBatch(Long batchId) {
        User owner = currentUser();
        PayrollBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu lương"));
        if (!"PENDING_APPROVAL".equals(batch.getStatus()))
            throw new BusinessException("Phiếu lương không ở trạng thái chờ duyệt");

        long now = System.currentTimeMillis();
        batch.setStatus("APPROVED");
        batch.setApprovedBy(owner);
        batch.setApprovedAt(now);
        batch.setUpdatedAt(now);
        batch = batchRepository.save(batch);

        if (batch.getCreatedBy() != null) {
            notificationService.sendToUser(batch.getCreatedBy(), "HR", "PAYROLL_APPROVED",
                    "Phiếu lương tháng " + batch.getMonth() + "/" + batch.getYear() + " đã được duyệt. Có thể tải về để chi lương.",
                    "{\"batchId\":" + batchId + "}");
            notificationService.sendToUser(batch.getCreatedBy(), "SUPER_ACCOUNTANT", "PAYROLL_APPROVED",
                    "Phiếu lương tháng " + batch.getMonth() + "/" + batch.getYear() + " đã được duyệt. Có thể tải về để chi lương.",
                    "{\"batchId\":" + batchId + "}");
        }
        return toBatchDto(batch);
    }

    @Transactional
    public PayrollBatchDto rejectBatch(Long batchId, RejectBatchRequest req) {
        User owner = currentUser();
        PayrollBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu lương"));
        if (!"PENDING_APPROVAL".equals(batch.getStatus()))
            throw new BusinessException("Phiếu lương không ở trạng thái chờ duyệt");
        if (req.getRejectReason() == null || req.getRejectReason().isBlank())
            throw new BusinessException("Cần nhập lý do từ chối");

        long now = System.currentTimeMillis();
        batch.setStatus("REJECTED");
        batch.setRejectReason(req.getRejectReason());
        batch.setApprovedBy(owner);
        batch.setUpdatedAt(now);
        batch = batchRepository.save(batch);

        if (batch.getCreatedBy() != null) {
            notificationService.sendToUser(batch.getCreatedBy(), "HR", "PAYROLL_REJECTED",
                    "Phiếu lương tháng " + batch.getMonth() + "/" + batch.getYear()
                            + " bị từ chối: " + req.getRejectReason(),
                    "{\"batchId\":" + batchId + "}");
        }
        return toBatchDto(batch);
    }

    // ── 4. List & detail ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<PayrollBatchDto> listBatches(String status, Pageable pageable) {
        Page<PayrollBatch> page = batchRepository.findAllFiltered(status, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toBatchDto).toList());
    }

    @Transactional(readOnly = true)
    public PayrollBatchDto getBatch(Long batchId) {
        return toBatchDto(batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu lương")));
    }

    @Transactional(readOnly = true)
    public List<PayslipDto> getBatchPayslips(Long batchId) {
        return payslipRepository.findByBatchIdOrderByUserFullNameAsc(batchId).stream()
                .map(this::toPayslipDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PayslipDto> getMyPayslips(Long userId) {
        return payslipRepository.findByUserIdOrderByLatest(userId).stream()
                .map(this::toPayslipDto).collect(Collectors.toList());
    }

    // ── 5. Tải file phiếu lương chi tiết (sau khi Owner duyệt) ──────────────────

    @Transactional(readOnly = true)
    public byte[] exportPayslipsExcel(Long batchId) throws Exception {
        PayrollBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu lương"));
        if (!"APPROVED".equals(batch.getStatus()))
            throw new BusinessException("Phiếu lương chưa được Owner duyệt — không thể tải về.");

        List<Payslip> payslips = payslipRepository.findByBatchIdOrderByUserFullNameAsc(batchId);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            PayslipStyles s = new PayslipStyles(wb);

            buildSummarySheet(wb, s, batch, payslips);

            int sheetCount = 0;
            for (Payslip p : payslips) {
                if (sheetCount >= 200) break;
                String safeName = sanitizeSheetName((sheetCount + 1) + "_" + p.getUserFullName());
                XSSFSheet sheet = wb.createSheet(safeName);
                buildPayslipDetailSheet(sheet, s, batch, p);
                sheetCount++;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sheet "Tong hop" — danh sách toàn bộ phiếu lương trong batch
    // ═══════════════════════════════════════════════════════════════════════

    private void buildSummarySheet(XSSFWorkbook wb, PayslipStyles s, PayrollBatch batch, List<Payslip> payslips) {
        XSSFSheet sheet = wb.createSheet("Tong hop");
        int cols = 23;
        sheet.setPrintGridlines(false);
        int r = 0;

        r = writeCompanyHeaderPs(sheet, s, r, cols);
        r = titleRowPs(sheet, s, r, cols, "BẢNG TỔNG HỢP LƯƠNG THÁNG " + batch.getMonth() + "/" + batch.getYear());
        r = infoRow1Ps(sheet, s, r, cols, "Tổng số nhân viên:", String.valueOf(payslips.size()));
        r++;

        String[] headers = {
                "STT", "Họ và tên", "Bộ phận", "Phòng ban", "Chức vụ",
                "Lương trước thuế", "NPT", "Công chuẩn", "Công thực tế",
                "Thưởng", "Phụ cấp", "Lương GROSS", "Lương đóng BH",
                "BHXH (8%)", "BHYT (1.5%)", "BHTN (1%)", "Tổng bảo hiểm",
                "Thu nhập trước thuế", "Giảm trừ bản thân", "Giảm trừ NPT",
                "Thu nhập tính thuế", "Thuế TNCN", "Lương NET"
        };
        int headerRowIdx = r;
        Row headerRow = sheet.createRow(r);
        headerRow.setHeightInPoints(40);
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(s.headerCell);
        }
        sheet.setColumnWidth(0, 8 * 256);
        sheet.setColumnWidth(1, 26 * 256);
        for (int i = 2; i < cols; i++) sheet.setColumnWidth(i, 15 * 256);
        sheet.createFreezePane(0, r + 1, 0, r + 1);
        r++;

        int stt = 1;
        for (Payslip p : payslips) {
            Row row = sheet.createRow(r++);
            row.setHeightInPoints(22);
            boolean even = (stt % 2 == 0);
            CellStyle txt = even ? s.dataEven : s.dataOdd;
            CellStyle num = even ? s.numEven : s.numOdd;
            CellStyle money = even ? s.moneyEven : s.moneyOdd;

            putCellPs(row, 0, String.valueOf(stt), num);
            putCellPs(row, 1, p.getUserFullName(), txt);
            putCellPs(row, 2, p.getDepartment(), txt);
            putCellPs(row, 3, p.getDivision(), txt);
            putCellPs(row, 4, p.getPosition(), txt);
            setMoneyCellPs(row, 5, p.getBaseSalary(), money);
            putCellPs(row, 6, String.valueOf(p.getDependents() != null ? p.getDependents() : 0), num);
            putCellPs(row, 7, fmtDay(p.getStandardWorkdays()), num);
            putCellPs(row, 8, fmtDay(p.getActualWorkdays()), num);
            setMoneyCellPs(row, 9, p.getBonus(), money);
            setMoneyCellPs(row, 10, p.getAllowance(), money);
            setMoneyCellPs(row, 11, p.getGrossSalary(), money);
            setMoneyCellPs(row, 12, p.getInsuranceSalary(), money);
            setMoneyCellPs(row, 13, p.getSocialInsuranceAmount(), money);
            setMoneyCellPs(row, 14, p.getHealthInsuranceAmount(), money);
            setMoneyCellPs(row, 15, p.getUnemploymentInsuranceAmount(), money);
            setMoneyCellPs(row, 16, p.getTotalInsuranceAmount(), money);
            setMoneyCellPs(row, 17, p.getPreTaxIncome(), money);
            setMoneyCellPs(row, 18, p.getPersonalDeduction(), money);
            setMoneyCellPs(row, 19, p.getDependentDeduction(), money);
            setMoneyCellPs(row, 20, p.getTaxableIncome(), money);
            setMoneyCellPs(row, 21, p.getPersonalIncomeTax(), money);
            setMoneyCellPs(row, 22, p.getNetSalary(), even ? s.netEven : s.netOdd);
            stt++;
        }

        // Dòng TỔNG CỘNG
        Row totalRow = sheet.createRow(r);
        totalRow.setHeightInPoints(24);
        sheet.addMergedRegion(new CellRangeAddress(r, r, 0, 4));
        putCellPs(totalRow, 0, "TỔNG CỘNG", s.totalLabel);
        setMoneyCellPs(totalRow, 5, sumOf(payslips, Payslip::getBaseSalary), s.totalValue);
        putCellPs(totalRow, 6, "", s.totalLabel);
        putCellPs(totalRow, 7, "", s.totalLabel);
        putCellPs(totalRow, 8, "", s.totalLabel);
        setMoneyCellPs(totalRow, 9, sumOf(payslips, Payslip::getBonus), s.totalValue);
        setMoneyCellPs(totalRow, 10, sumOf(payslips, Payslip::getAllowance), s.totalValue);
        setMoneyCellPs(totalRow, 11, sumOf(payslips, Payslip::getGrossSalary), s.totalValue);
        setMoneyCellPs(totalRow, 12, sumOf(payslips, Payslip::getInsuranceSalary), s.totalValue);
        setMoneyCellPs(totalRow, 13, sumOf(payslips, Payslip::getSocialInsuranceAmount), s.totalValue);
        setMoneyCellPs(totalRow, 14, sumOf(payslips, Payslip::getHealthInsuranceAmount), s.totalValue);
        setMoneyCellPs(totalRow, 15, sumOf(payslips, Payslip::getUnemploymentInsuranceAmount), s.totalValue);
        setMoneyCellPs(totalRow, 16, sumOf(payslips, Payslip::getTotalInsuranceAmount), s.totalValue);
        setMoneyCellPs(totalRow, 17, sumOf(payslips, Payslip::getPreTaxIncome), s.totalValue);
        setMoneyCellPs(totalRow, 18, sumOf(payslips, Payslip::getPersonalDeduction), s.totalValue);
        setMoneyCellPs(totalRow, 19, sumOf(payslips, Payslip::getDependentDeduction), s.totalValue);
        setMoneyCellPs(totalRow, 20, sumOf(payslips, Payslip::getTaxableIncome), s.totalValue);
        setMoneyCellPs(totalRow, 21, sumOf(payslips, Payslip::getPersonalIncomeTax), s.totalValue);
        setMoneyCellPs(totalRow, 22, sumOf(payslips, Payslip::getNetSalary), s.totalValue);

        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(true);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.getFooter().setCenter("Trang &P/&N");
    }

    private long sumOf(List<Payslip> payslips, java.util.function.Function<Payslip, Long> getter) {
        return payslips.stream().mapToLong(p -> { Long v = getter.apply(p); return v != null ? v : 0L; }).sum();
    }

    private String fmtDay(Double v) {
        if (v == null) return "0";
        return (v == Math.floor(v)) ? String.valueOf(v.intValue()) : String.valueOf(v);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sheet phiếu lương chi tiết — 1 nhân viên / 1 sheet
    // ═══════════════════════════════════════════════════════════════════════

    private void buildPayslipDetailSheet(XSSFSheet sheet, PayslipStyles s, PayrollBatch batch, Payslip p) {
        int cols = 4;
        sheet.setPrintGridlines(false);
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 18 * 256);
        sheet.setColumnWidth(2, 30 * 256);
        sheet.setColumnWidth(3, 18 * 256);

        int r = 0;
        r = writeCompanyHeaderPs(sheet, s, r, cols);
        r = titleRowPs(sheet, s, r, cols, "PHIẾU LƯƠNG THÁNG " + batch.getMonth() + "/" + batch.getYear());
        r = subTitleRowPs(sheet, s, r, cols,
                (batch.getApprovedAt() != null ? "Đã duyệt: " + fmtDateTime(batch.getApprovedAt()) : ""));
        r++;

        // ── Thông tin nhân viên ──
        r = sectionHeaderPs(sheet, s, r, cols, "THÔNG TIN NHÂN VIÊN");
        r = infoRow2Ps(sheet, s, r, cols, "Họ và tên:", p.getUserFullName(), "Chức vụ:", p.getPosition());
        r = infoRow2Ps(sheet, s, r, cols, "Bộ phận:", p.getDepartment(), "Phòng ban:", p.getDivision());
        r = infoRow2Ps(sheet, s, r, cols, "Số người phụ thuộc:", String.valueOf(p.getDependents() != null ? p.getDependents() : 0),
                "Công chuẩn / Công thực tế:", fmtDay(p.getStandardWorkdays()) + " / " + fmtDay(p.getActualWorkdays()));
        r++;

        // ── Bảng lương GROSS ──
        r = sectionHeaderPs(sheet, s, r, cols, "LƯƠNG GROSS");
        r = payRowPs(sheet, s, r, cols, "Lương trước thuế (đã duyệt)", p.getBaseSalary(), false);
        r = payRowPs(sheet, s, r, cols, "Lương theo công thực tế", p.getActualSalary(), false);
        r = payRowPs(sheet, s, r, cols, "Thưởng", p.getBonus(), false);
        r = payRowPs(sheet, s, r, cols, "Phụ cấp", p.getAllowance(), false);
        r = payRowHighlightPs(sheet, s, r, cols, "TỔNG LƯƠNG GROSS", p.getGrossSalary());
        r++;

        // ── Các khoản giảm trừ ──
        r = sectionHeaderPs(sheet, s, r, cols, "CÁC KHOẢN BẢO HIỂM & GIẢM TRỪ");
        r = payRowPs(sheet, s, r, cols, "Lương đóng BHXH/BHYT/BHTN", p.getInsuranceSalary(), false);
        r = payRowPs(sheet, s, r, cols, "Bảo hiểm xã hội (8%)", negate(p.getSocialInsuranceAmount()), false);
        r = payRowPs(sheet, s, r, cols, "Bảo hiểm y tế (1.5%)", negate(p.getHealthInsuranceAmount()), false);
        r = payRowPs(sheet, s, r, cols, "Bảo hiểm thất nghiệp (1%)", negate(p.getUnemploymentInsuranceAmount()), false);
        r = payRowHighlightPs(sheet, s, r, cols, "THU NHẬP TRƯỚC THUẾ", p.getPreTaxIncome());
        r = payRowPs(sheet, s, r, cols, "Giảm trừ gia cảnh bản thân", negate(p.getPersonalDeduction()), false);
        r = payRowPs(sheet, s, r, cols, "Giảm trừ gia cảnh người phụ thuộc", negate(p.getDependentDeduction()), false);
        r = payRowHighlightPs(sheet, s, r, cols, "THU NHẬP TÍNH THUẾ", p.getTaxableIncome());
        r = payRowPs(sheet, s, r, cols, "Thuế thu nhập cá nhân", negate(p.getPersonalIncomeTax()), false);
        r++;

        // ── Lương thực nhận ──
        r = netRowPs(sheet, s, r, cols, "LƯƠNG THỰC NHẬN (NET)", p.getNetSalary());
        r++;

        // ── Khối ký tên ──
        signatureBlockPs(sheet, s, r, cols, "Người lập phiếu", "Kế toán", "Người nhận lương");

        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.getFooter().setCenter("Trang &P/&N");
    }

    private Long negate(Long v) { return v == null ? 0L : -v; }

    private String fmtDateTime(Long epochMs) {
        if (epochMs == null) return "";
        LocalDate d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault());
        return d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers layout dùng riêng cho phiếu lương (Styles ở cuối file)
    // ═══════════════════════════════════════════════════════════════════════

    private int writeCompanyHeaderPs(XSSFSheet sh, PayslipStyles s, int r, int cols) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(40);
        // Khối phải (mã NS / kỳ lương) chỉ vẽ riêng khi còn ít nhất 2 cột cho nó,
        // nếu không gộp toàn bộ làm 1 khối công ty duy nhất (tránh merge 1-cell lỗi POI).
        int rightWidth = Math.min(2, Math.max(0, cols - 2));
        int leftEnd = cols - 1 - rightWidth;

        if (leftEnd > 0) sh.addMergedRegion(new CellRangeAddress(r, r, 0, leftEnd));
        putCellPs(row, 0, "CÔNG TY TNHH .....................................\n" +
                "Địa chỉ: ................................................. Điện thoại: ........................", s.companyBox);

        if (rightWidth >= 2) {
            sh.addMergedRegion(new CellRangeAddress(r, r, leftEnd + 1, cols - 1));
            putCellPs(row, leftEnd + 1, "Mã NS: ......................\nKỳ lương: ............", s.companyBox);
        }
        return r + 1;
    }

    private int titleRowPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String title) {
        sh.createRow(r);
        r++;
        Row row = sh.createRow(r);
        row.setHeightInPoints(34);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 1));
        putCellPs(row, 0, title, s.title);
        return r + 1;
    }

    private int subTitleRowPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String text) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(18);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 1));
        putCellPs(row, 0, text, s.subTitle);
        return r + 1;
    }

    private int sectionHeaderPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String text) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(24);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 1));
        putCellPs(row, 0, text, s.sectionHeader);
        return r + 1;
    }

    private int infoRow1Ps(XSSFSheet sh, PayslipStyles s, int r, int cols, String label, String value) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(22);
        sh.addMergedRegion(new CellRangeAddress(r, r, 1, cols - 1));
        putCellPs(row, 0, label, s.infoLabel);
        putCellPs(row, 1, value, s.infoValue);
        return r + 1;
    }

    private int infoRow2Ps(XSSFSheet sh, PayslipStyles s, int r, int cols,
                            String label1, String value1, String label2, String value2) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(22);
        putCellPs(row, 0, label1, s.infoLabel);
        putCellPs(row, 1, value1, s.infoValue);
        putCellPs(row, 2, label2, s.infoLabel);
        putCellPs(row, 3, value2, s.infoValue);
        return r + 1;
    }

    /** 1 dòng "Diễn giải | Số tiền" trong bảng lương — cols-1 đầu là label, cột cuối là số tiền */
    private int payRowPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String label, Long amount, boolean bold) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(22);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 2));
        putCellPs(row, 0, label, s.payLabel);
        setMoneyCellPs(row, cols - 1, amount, s.payValue);
        return r + 1;
    }

    private int payRowHighlightPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String label, Long amount) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(24);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 2));
        putCellPs(row, 0, label, s.subtotalLabel);
        setMoneyCellPs(row, cols - 1, amount, s.subtotalValue);
        return r + 1;
    }

    private int netRowPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String label, Long amount) {
        Row row = sh.createRow(r);
        row.setHeightInPoints(30);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, cols - 2));
        putCellPs(row, 0, label, s.netLabel);
        setMoneyCellPs(row, cols - 1, amount, s.netValue);
        return r + 1;
    }

    private void signatureBlockPs(XSSFSheet sh, PayslipStyles s, int r, int cols, String... roleLabels) {
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
            if (end > start) {
                sh.addMergedRegion(new CellRangeAddress(r, r, start, end));
                sh.addMergedRegion(new CellRangeAddress(r + 1, r + 1, start, end));
                sh.addMergedRegion(new CellRangeAddress(r + 2, r + 2, start, end));
                sh.addMergedRegion(new CellRangeAddress(r + 3, r + 3, start, end));
            }
            putCellPs(labelRow, start, roleLabels[i], s.signLabel);
            putCellPs(hintRow, start, "(Ký, ghi rõ họ tên)", s.signNote);
            putCellPs(spaceRow, start, "", s.signNote);
            putCellPs(lineRow, start, "..................................................", s.signName);
        }
    }

    private void putCellPs(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }

    private void setMoneyCellPs(Row row, int col, Long value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : 0);
        if (style != null) c.setCellStyle(style);
    }

    private String sanitizeSheetName(String name) {
        if (name == null) name = "NV";
        String cleaned = name.replaceAll("[\\\\/\\[\\]*?:]", "_");
        if (cleaned.length() > 31) cleaned = cleaned.substring(0, 31);
        return cleaned.isBlank() ? "NV" : cleaned;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Styles cho phiếu lương — cùng tông màu (navy/blue) với mẫu phiếu kho
    // ═══════════════════════════════════════════════════════════════════════

    private static class PayslipStyles {
        final XSSFCellStyle companyBox, title, subTitle, sectionHeader;
        final XSSFCellStyle infoLabel, infoValue;
        final XSSFCellStyle headerCell;
        final XSSFCellStyle dataEven, dataOdd, numEven, numOdd, moneyEven, moneyOdd, netEven, netOdd;
        final XSSFCellStyle payLabel, payValue, subtotalLabel, subtotalValue, netLabel, netValue;
        final XSSFCellStyle totalLabel, totalValue;
        final XSSFCellStyle signLabel, signNote, signName;

        private static final String C_NAVY = "1A3C6E";
        private static final String C_BLUE = "2E75B6";
        private static final String C_ACCENT = "D6E4F0";
        private static final String C_WHITE = "FFFFFF";
        private static final String C_MUTED = "5C5C5C";
        private static final String C_NET_BG = "1A3C6E";
        private static final String C_NET_FONT = "FFFFFF";
        private static final String C_SUBTOTAL_BG = "FFF3CD";
        private static final String C_SUBTOTAL_FONT = "8A6D00";
        private static final String C_TOTAL = "FFF3CD";
        private static final String DATA_FMT = "#,##0";

        PayslipStyles(XSSFWorkbook wb) {
            DataFormat fmt = wb.createDataFormat();

            XSSFFont fCompany = fnt(wb, 11, false, "1C1C1E");
            XSSFFont fTitle = fnt(wb, 18, true, C_NAVY);
            XSSFFont fSubTitle = fnt(wb, 11, false, C_MUTED);
            XSSFFont fSection = fnt(wb, 13, true, C_WHITE);
            XSSFFont fLabel = fnt(wb, 11, true, C_NAVY);
            XSSFFont fValue = fnt(wb, 11, false, "1C1C1E");
            XSSFFont fHdr = fnt(wb, 11, true, C_WHITE);
            XSSFFont fData = fnt(wb, 11, false, "1C1C1E");
            XSSFFont fNum = fnt(wb, 11, true, C_NAVY);
            XSSFFont fPayLabel = fnt(wb, 12, false, "1C1C1E");
            XSSFFont fPayValue = fnt(wb, 12, false, "1C1C1E");
            XSSFFont fSubtotal = fnt(wb, 12, true, C_SUBTOTAL_FONT);
            XSSFFont fNet = fnt(wb, 15, true, C_NET_FONT);
            XSSFFont fTotalLbl = fnt(wb, 12, true, "8A6D00");
            XSSFFont fSign = fnt(wb, 12, true, C_NAVY);
            XSSFFont fSignNote = fnt(wb, 10, false, C_MUTED, true);
            XSSFFont fSignName = fnt(wb, 11, false, C_MUTED);

            companyBox = base(wb, fCompany, HorizontalAlignment.LEFT, true);

            title = wb.createCellStyle();
            title.setFont(fTitle);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            subTitle = wb.createCellStyle();
            subTitle.setFont(fSubTitle);
            subTitle.setAlignment(HorizontalAlignment.CENTER);
            subTitle.setVerticalAlignment(VerticalAlignment.CENTER);

            sectionHeader = wb.createCellStyle();
            sectionHeader.setFont(fSection);
            sectionHeader.setAlignment(HorizontalAlignment.LEFT);
            sectionHeader.setVerticalAlignment(VerticalAlignment.CENTER);
            sectionHeader.setFillForegroundColor(new XSSFColor(hex(C_NAVY), null));
            sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sectionHeader.setIndention((short) 1);

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

            dataEven = dataStyle(wb, fData, C_ACCENT, HorizontalAlignment.LEFT, null);
            dataOdd = dataStyle(wb, fData, C_WHITE, HorizontalAlignment.LEFT, null);
            numEven = dataStyle(wb, fNum, C_ACCENT, HorizontalAlignment.CENTER, null);
            numOdd = dataStyle(wb, fNum, C_WHITE, HorizontalAlignment.CENTER, null);
            moneyEven = dataStyle(wb, fData, C_ACCENT, HorizontalAlignment.RIGHT, fmt.getFormat(DATA_FMT));
            moneyOdd = dataStyle(wb, fData, C_WHITE, HorizontalAlignment.RIGHT, fmt.getFormat(DATA_FMT));
            netEven = dataStyle(wb, fnt(wb, 11, true, C_NAVY), C_ACCENT, HorizontalAlignment.RIGHT, fmt.getFormat(DATA_FMT));
            netOdd = dataStyle(wb, fnt(wb, 11, true, C_NAVY), C_WHITE, HorizontalAlignment.RIGHT, fmt.getFormat(DATA_FMT));

            payLabel = wb.createCellStyle();
            payLabel.setFont(fPayLabel);
            payLabel.setAlignment(HorizontalAlignment.LEFT);
            payLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            payLabel.setIndention((short) 1);
            border(payLabel, BorderStyle.THIN, "DDDDDD");

            payValue = wb.createCellStyle();
            payValue.setFont(fPayValue);
            payValue.setAlignment(HorizontalAlignment.RIGHT);
            payValue.setVerticalAlignment(VerticalAlignment.CENTER);
            payValue.setDataFormat(fmt.getFormat(DATA_FMT));
            border(payValue, BorderStyle.THIN, "DDDDDD");

            subtotalLabel = wb.createCellStyle();
            subtotalLabel.setFont(fSubtotal);
            subtotalLabel.setAlignment(HorizontalAlignment.LEFT);
            subtotalLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            subtotalLabel.setIndention((short) 1);
            subtotalLabel.setFillForegroundColor(new XSSFColor(hex(C_SUBTOTAL_BG), null));
            subtotalLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(subtotalLabel, BorderStyle.THIN, "DDDDDD");

            subtotalValue = wb.createCellStyle();
            subtotalValue.setFont(fSubtotal);
            subtotalValue.setAlignment(HorizontalAlignment.RIGHT);
            subtotalValue.setVerticalAlignment(VerticalAlignment.CENTER);
            subtotalValue.setDataFormat(fmt.getFormat(DATA_FMT));
            subtotalValue.setFillForegroundColor(new XSSFColor(hex(C_SUBTOTAL_BG), null));
            subtotalValue.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(subtotalValue, BorderStyle.THIN, "DDDDDD");

            netLabel = wb.createCellStyle();
            netLabel.setFont(fNet);
            netLabel.setAlignment(HorizontalAlignment.LEFT);
            netLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            netLabel.setIndention((short) 1);
            netLabel.setFillForegroundColor(new XSSFColor(hex(C_NET_BG), null));
            netLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            netValue = wb.createCellStyle();
            netValue.setFont(fNet);
            netValue.setAlignment(HorizontalAlignment.RIGHT);
            netValue.setVerticalAlignment(VerticalAlignment.CENTER);
            netValue.setDataFormat(fmt.getFormat(DATA_FMT));
            netValue.setFillForegroundColor(new XSSFColor(hex(C_NET_BG), null));
            netValue.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            totalLabel = dataStyle(wb, fTotalLbl, C_TOTAL, HorizontalAlignment.CENTER, null);
            totalValue = dataStyle(wb, fTotalLbl, C_TOTAL, HorizontalAlignment.RIGHT, fmt.getFormat(DATA_FMT));

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

        private XSSFCellStyle dataStyle(XSSFWorkbook wb, XSSFFont font, String bgHex, HorizontalAlignment align, Short dataFormat) {
            XSSFCellStyle st = wb.createCellStyle();
            st.setFont(font);
            st.setFillForegroundColor(new XSSFColor(hex(bgHex), null));
            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            st.setAlignment(align);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setWrapText(true);
            if (dataFormat != null) st.setDataFormat(dataFormat);
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

        private static XSSFFont fnt(XSSFWorkbook wb, int pt, boolean bold, String hexColor) {
            return fnt(wb, pt, bold, hexColor, false);
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

    // ── Mapping helpers ──────────────────────────────────────────────────────────

    private PayrollBatchDto toBatchDto(PayrollBatch b) {
        return PayrollBatchDto.builder()
                .id(b.getId())
                .month(b.getMonth())
                .year(b.getYear())
                .status(b.getStatus())
                .employeeCount(b.getEmployeeCount())
                .rejectReason(b.getRejectReason())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .importedAt(b.getImportedAt())
                .approvedAt(b.getApprovedAt())
                .createdByName(b.getCreatedBy() != null ? b.getCreatedBy().getFullName() : null)
                .approvedByName(b.getApprovedBy() != null ? b.getApprovedBy().getFullName() : null)
                .build();
    }

    private PayslipDto toPayslipDto(Payslip p) {
        return PayslipDto.builder()
                .id(p.getId())
                .batchId(p.getBatch().getId())
                .month(p.getBatch().getMonth())
                .year(p.getBatch().getYear())
                .userId(p.getUser().getId())
                .userFullName(p.getUserFullName())
                .department(p.getDepartment())
                .division(p.getDivision())
                .position(p.getPosition())
                .baseSalary(p.getBaseSalary())
                .dependents(p.getDependents())
                .standardWorkdays(p.getStandardWorkdays())
                .actualWorkdays(p.getActualWorkdays())
                .bonus(p.getBonus())
                .allowance(p.getAllowance())
                .insuranceSalary(p.getInsuranceSalary())
                .actualSalary(p.getActualSalary())
                .grossSalary(p.getGrossSalary())
                .socialInsuranceAmount(p.getSocialInsuranceAmount())
                .healthInsuranceAmount(p.getHealthInsuranceAmount())
                .unemploymentInsuranceAmount(p.getUnemploymentInsuranceAmount())
                .totalInsuranceAmount(p.getTotalInsuranceAmount())
                .preTaxIncome(p.getPreTaxIncome())
                .personalDeduction(p.getPersonalDeduction())
                .dependentDeduction(p.getDependentDeduction())
                .taxableIncome(p.getTaxableIncome())
                .personalIncomeTax(p.getPersonalIncomeTax())
                .netSalary(p.getNetSalary())
                .build();
    }

    private String cellStr(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> null;
        };
    }

    private double getDouble(Row row, int col) {
        try {
            Cell c = row.getCell(col);
            if (c == null) return 0.0;
            if (c.getCellType() == CellType.STRING) {
                String s = c.getStringCellValue();
                return s == null || s.isBlank() ? 0.0 : Double.parseDouble(s.trim().replace(",", ""));
            }
            return c.getNumericCellValue();
        } catch (Exception e) { return 0.0; }
    }
}
