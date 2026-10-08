package com.nhatnam.server.service.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.hr.CompanyPayrollDtos.*;
import com.nhatnam.server.dto.hr.HrDtos.SalaryBreakdownDto;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.MonthlyAdjustment.Source;
import com.nhatnam.server.entity.MonthlyAdjustment.Type;
import com.nhatnam.server.enumtype.PayrollCalcStatus;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.AttendanceEntryRepository;
import com.nhatnam.server.repository.AttendanceSheetRepository;
import com.nhatnam.server.repository.EmployeeSalaryRepository;
import com.nhatnam.server.repository.MonthlyAdjustmentRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.FactoryPayrollService;
import com.nhatnam.server.service.attendance.AttendanceExcelParser;
import com.nhatnam.server.service.attendance.AttendanceExcelParser.DayRecord;
import com.nhatnam.server.service.attendance.AttendanceExcelParser.EmployeeBlock;
import com.nhatnam.server.service.hr.PayrollCalculationService.DayCategory;
import com.nhatnam.server.service.hr.PayrollCalculationService.OtResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * TRUNG TÂM VÒNG ĐỜI LƯƠNG CẢ CÔNG TY — Phase 2 refactor (10/2026).
 *
 * <p>Service này sở hữu toàn bộ vòng đời mới: upload file chấm công / lịch nghỉ /
 * đơn xin nghỉ dùng chung (không còn theo bộ phận), bấm Tính lương, Public,
 * Unpublic, Mở lại. Nó TÁI SỬ DỤNG {@code AttendanceSheet} entity hiện có bằng
 * cách lưu dưới cờ {@link AttendanceSheetRepository#COMPANY_SENTINEL}; không
 * cần migration schema.
 *
 * <h3>Vì sao không viết lại {@code FactoryPayrollService}</h3>
 * File đó 3200+ dòng, buộc chặt vào các flow cũ (KPI, driver odometer, modal
 * sơ đồ tổ chức, v.v). Thay vì rewrite, Phase 2 giới thiệu một luồng mới chạy
 * SONG SONG với luồng cũ. Phase 3 (FE) chuyển sang gọi API mới và các endpoint
 * cũ sẽ ngừng được gọi; Phase 4 có thể xoá hẳn khối code cũ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyAttendanceService {

    private final AttendanceSheetRepository sheetRepo;
    private final AttendanceEntryRepository entryRepo;
    private final MonthlyAdjustmentRepository adjustmentRepo;
    private final UserRepository userRepo;
    /** Cần để đọc cờ {@code partTime} khi tính ngày công / ngày cơm trong buildEntry. */
    private final EmployeeSalaryRepository salaryRepo;
    /** Để lấy số dư ngày phép khi bù trừ phút đi trễ. */
    private final EmployeeRequestService employeeRequestService;
    private final HolidayService holidayService;
    private final PayrollCalculationService calc;
    private final HrService hrService;
    private final EntityManager em;

    /**
     * Dùng {@link FactoryPayrollService#monthDir} + {@code storeFile} có sẵn
     * để không trùng lặp logic lưu file đĩa. Lazy inject để tránh circular
     * dependency vì FactoryPayrollService gián tiếp depend vào các repo mà
     * class này cũng dùng.
     */
    @Lazy
    @Autowired
    private FactoryPayrollService factoryPayrollService;

    /**
     * Phase 7 (10/2026): bonus & allowance import nay gọi chung ở
     * CompanyPayrollPanel. Lazy inject vì PayrollAdjustmentService đã inject
     * ngược lại CompanyAttendanceService (để kiểm guard canUploadAdjustments).
     */
    @Lazy
    @Autowired
    private PayrollAdjustmentService payrollAdjustmentService;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ══════════════════════════════════════════════════════════════════════════
    // 1. STATUS — FE gọi mỗi khi vào trang để biết đang ở trạng thái nào
    // ══════════════════════════════════════════════════════════════════════════

    public CompanyPeriodStatusDto getStatus(int month, int year) {
        AttendanceSheet s = sheetRepo.findCompanySheet(month, year).orElse(null);
        long bonusCnt = adjustmentRepo.countByMonthAndYearAndType(month, year, Type.BONUS);
        long allowCnt = adjustmentRepo.countByMonthAndYearAndType(month, year, Type.ALLOWANCE);
        return toStatusDto(month, year, s, bonusCnt, allowCnt);
    }

    private CompanyPeriodStatusDto toStatusDto(int month, int year, AttendanceSheet s,
                                               long bonusCnt, long allowCnt) {
        PayrollCalcStatus st = s != null ? s.getCalcStatus() : PayrollCalcStatus.NONE;
        boolean hasAtt = s != null && s.hasAttendanceFile();
        return CompanyPeriodStatusDto.builder()
                .month(month)
                .year(year)
                .calcStatus(st)
                .hasAttendanceFile(hasAtt)
                .hasExceptionFile(s != null && s.getExceptionFilePath() != null)
                .hasLeaveFile(s != null && s.getLeaveFilePath() != null)
                .attendanceFileName(s != null ? s.getFileName() : null)
                .exceptionFileName(s != null ? s.getExceptionFileName() : null)
                .leaveFileName(s != null ? s.getLeaveFileName() : null)
                .attendanceUploadedAt(s != null ? s.getUploadedAt() : null)
                .exceptionUploadedAt(s != null ? s.getExceptionUploadedAt() : null)
                .leaveUploadedAt(s != null ? s.getLeaveUploadedAt() : null)
                .calculatedAt(s != null ? s.getCalculatedAt() : null)
                .calculatedByName(s != null ? s.getCalculatedByName() : null)
                .publishedAt(s != null ? s.getPublishedAt() : null)
                .publishedByName(s != null ? s.getPublishedByName() : null)
                .otTotalWeekdayMinutes(s != null ? s.getOtTotalWeekdayMinutes() : 0L)
                .otTotalSundayMinutes(s != null ? s.getOtTotalSundayMinutes() : 0L)
                .otTotalHolidayMinutes(s != null ? s.getOtTotalHolidayMinutes() : 0L)
                .otTotalAmount(s != null ? s.getOtTotalAmount() : 0L)
                .canUploadAttendance(st != PayrollCalcStatus.PUBLISHED)      // publish rồi phải Unpublic trước
                .canUploadAdjustments(st.canUploadAdjustments())
                .canCalculate(hasAtt && st.canCalculate())
                .canPublish(st.canPublish())
                .canUnpublish(st.canUnpublish())
                .canReopen(st.canReopen())
                .bonusLineCount(bonusCnt)
                .allowanceLineCount(allowCnt)
                .parsedRows(s != null ? s.getParsedRows() : 0)
                .kpiCalcStatus(s != null ? s.getKpiCalcStatus() : PayrollCalcStatus.NONE)
                .kpiCalculatedAt(s != null ? s.getKpiCalculatedAt() : null)
                .kpiCalculatedByName(s != null ? s.getKpiCalculatedByName() : null)
                .kpiPublishedAt(s != null ? s.getKpiPublishedAt() : null)
                .kpiPublishedByName(s != null ? s.getKpiPublishedByName() : null)
                .bonusCalcStatus(s != null ? s.getBonusCalcStatus() : PayrollCalcStatus.NONE)
                .bonusCalculatedAt(s != null ? s.getBonusCalculatedAt() : null)
                .bonusCalculatedByName(s != null ? s.getBonusCalculatedByName() : null)
                .bonusPublishedAt(s != null ? s.getBonusPublishedAt() : null)
                .bonusPublishedByName(s != null ? s.getBonusPublishedByName() : null)
                // PHASE 6: chuyên cần chỉ xem khi đã Tính lương (CALCULATED | PUBLISHED).
                .canChuyenCan(s != null && (st == PayrollCalcStatus.CALCULATED
                        || st == PayrollCalcStatus.PUBLISHED))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. UPLOAD FILE CHẤM CÔNG (CẢ CÔNG TY — 1 FILE DUY NHẤT)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Upload file chấm công chung của cả công ty cho 1 tháng.
     *
     * <p>Khớp nhân viên theo thứ tự: (1) {@code User.employeeCode} khớp mã trong
     * file; (2) tên đầy đủ chuẩn hoá (không dấu, lowercase) khớp. Nhân viên không
     * khớp được ghi vào {@code warnings}.
     *
     * <p>Upload lại = GHI ĐÈ: xoá hết attendance_entry cũ của sheet, parse lại
     * từ đầu. Trạng thái về {@link PayrollCalcStatus#UPLOADED}; nếu đang
     * CALCULATED/PUBLISHED sẽ kéo xuống UPLOADED và xoá OT.
     */
    @Transactional
    public UploadResultDto uploadAttendance(MultipartFile file, int month, int year, User actor) {
        requireValidPeriod(month, year);
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn file chấm công.");

        AttendanceSheet sheet = getOrCreateCompanySheet(month, year);
        List<String> warnings = new ArrayList<>();

        // Lưu file đĩa (dùng helper có sẵn trong FactoryPayrollService)
        String savedPath = factoryPayrollService.storeAttendanceFile(
                file, month, year, PayrollDepartment.FACTORY, "sheet", warnings);
        if (savedPath != null) {
            sheet.setFilePath(savedPath);
            sheet.setFileName(file.getOriginalFilename());
            sheet.setUploadedAt(System.currentTimeMillis());
            sheet.setUploadedByName(actor != null ? actor.getFullName() : null);
            sheet.setUploadedBy(actor);
        }

        // Parse file
        List<EmployeeBlock> blocks;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getBytes()))) {
            blocks = AttendanceExcelParser.parse(wb, year, warnings);
        } catch (IOException e) {
            throw new IllegalArgumentException("Không đọc được file Excel: " + e.getMessage());
        }

        // Xoá entry cũ của sheet (ghi đè toàn bộ)
        entryRepo.deleteBySheet_Id(sheet.getId());
        em.flush();  // đẩy DELETE trước INSERT để tránh unique conflict

        // Resolve + lưu entry
        Map<String, User> byCode = indexUsersByCode();
        Map<String, User> byName = indexUsersByNormalizedName();

        int matched = 0, unmatched = 0;
        for (EmployeeBlock b : blocks) {
            User u = resolveUser(b, byCode, byName);
            if (u == null) {
                unmatched++;
                warnings.add("Không khớp nhân viên: " + b.getEmployeeCode() + " / " + b.getEmployeeName());
                continue;
            }
            AttendanceEntry entry = buildEntryFromBlock(sheet, u, b);
            entryRepo.save(entry);
            matched++;
        }

        sheet.setParsedRows(matched);
        sheet.setStatus(AttendanceSheet.SheetStatus.PROCESSED);
        sheet.onAttendanceFileChanged();           // kéo trạng thái về UPLOADED, xoá OT nếu đã tính
        sheetRepo.save(sheet);

        // Nếu đang CALCULATED/PUBLISHED thì onAttendanceFileChanged đã kéo
        // calcStatus về UPLOADED — cần xoá luôn các khoản AUTO_OT còn sót để
        // dữ liệu nhất quán.
        adjustmentRepo.deleteByPeriodAndSource(month, year, Source.AUTO_OT);

        return UploadResultDto.builder()
                .fileName(file.getOriginalFilename())
                .parsedRows(blocks.size())
                .matchedUsers(matched)
                .unmatchedRows(unmatched)
                .warnings(warnings)
                .status(getStatus(month, year))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. UPLOAD LỊCH NGHỈ / ĐƠN XIN NGHỈ (CẢ CÔNG TY)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Upload file "lịch nghỉ / đi trễ / về sớm". Chỉ lưu file + metadata ở Phase 2
     * — logic parse chi tiết Phase 3/4 sẽ wire lại từ parser cũ.
     *
     * <p>Chặn nếu {@code calcStatus >= CALCULATED} (phải Mở lại trước).
     */
    @Transactional
    public UploadResultDto uploadException(MultipartFile file, int month, int year, User actor) {
        requireValidPeriod(month, year);
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn file.");

        AttendanceSheet sheet = getOrCreateCompanySheet(month, year);
        assertCanUploadAdjustments(sheet);

        List<String> warnings = new ArrayList<>();
        String savedPath = factoryPayrollService.storeAttendanceFile(
                file, month, year, PayrollDepartment.FACTORY, "exception", warnings);
        if (savedPath != null) {
            sheet.setExceptionFilePath(savedPath);
            sheet.setExceptionFileName(file.getOriginalFilename());
            sheet.setExceptionUploadedAt(System.currentTimeMillis());
        }
        sheetRepo.save(sheet);

        return UploadResultDto.builder()
                .fileName(file.getOriginalFilename())
                .warnings(warnings)
                .status(getStatus(month, year))
                .build();
    }

    @Transactional
    public UploadResultDto uploadLeave(MultipartFile file, int month, int year, User actor) {
        requireValidPeriod(month, year);
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn file.");

        AttendanceSheet sheet = getOrCreateCompanySheet(month, year);
        assertCanUploadAdjustments(sheet);

        List<String> warnings = new ArrayList<>();
        String savedPath = factoryPayrollService.storeAttendanceFile(
                file, month, year, PayrollDepartment.FACTORY, "leave", warnings);
        if (savedPath != null) {
            sheet.setLeaveFilePath(savedPath);
            sheet.setLeaveFileName(file.getOriginalFilename());
            sheet.setLeaveUploadedAt(System.currentTimeMillis());
        }
        sheetRepo.save(sheet);

        return UploadResultDto.builder()
                .fileName(file.getOriginalFilename())
                .warnings(warnings)
                .status(getStatus(month, year))
                .build();
    }

    /** Xoá 1 trong 3 file. {@code kind} = ATTENDANCE | EXCEPTION | LEAVE. */
    @Transactional
    public CompanyPeriodStatusDto deleteFile(String kind, int month, int year) {
        AttendanceSheet s = sheetRepo.findCompanySheet(month, year)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Chưa có bảng chấm công cho %d/%d".formatted(month, year)));

        switch (kind.toUpperCase(Locale.ROOT)) {
            case "ATTENDANCE" -> {
                tryDeleteDiskFile(s.getFilePath());
                s.setFilePath(null); s.setFileName(null);
                entryRepo.deleteBySheet_Id(s.getId());
                s.setParsedRows(0);
                s.onAttendanceFileChanged();
                adjustmentRepo.deleteByPeriodAndSource(month, year, Source.AUTO_OT);
            }
            case "EXCEPTION" -> {
                assertCanUploadAdjustments(s);
                tryDeleteDiskFile(s.getExceptionFilePath());
                s.setExceptionFilePath(null); s.setExceptionFileName(null);
                s.setExceptionUploadedAt(null); s.setExceptionRows(0);
            }
            case "LEAVE" -> {
                assertCanUploadAdjustments(s);
                tryDeleteDiskFile(s.getLeaveFilePath());
                s.setLeaveFilePath(null); s.setLeaveFileName(null);
                s.setLeaveUploadedAt(null); s.setLeaveRows(0);
            }
            default -> throw new IllegalArgumentException("Loại file không hợp lệ: " + kind);
        }
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 7 — UPLOAD THƯỞNG & PHỤ CẤP CHUNG CHO CẢ CÔNG TY
    //
    // Trước Phase 7: 2 loại này import per-department ở từng tab bộ phận. Phase
    // 7 gộp vào CompanyPayrollPanel đầu trang — 1 file duy nhất cho cả công ty.
    // Luồng parse không đổi: PayrollAdjustmentService đã accept dept=null (nó
    // phân bổ theo role nhân viên trong file). Chỉ cần endpoint mới gọi với
    // dept=null + áp cùng guard canUploadAdjustments.
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public UploadResultDto uploadBonus(MultipartFile file, int month, int year, User actor) {
        requireValidPeriod(month, year);
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn file.");

        // Guard: chặn khi tháng đã CALCULATED/PUBLISHED.
        assertCanUploadAdjustments(month, year);

        var result = payrollAdjustmentService.importBonus(file, month, year, null);
        int unmatched = result.getUnmatchedItems() == null ? 0 : result.getUnmatchedItems().size();
        List<String> allMessages = new ArrayList<>();
        if (result.getWarnings() != null) allMessages.addAll(result.getWarnings());
        if (result.getErrors()   != null) allMessages.addAll(result.getErrors());
        return UploadResultDto.builder()
                .fileName(file.getOriginalFilename())
                .parsedRows(result.getRowsRead())
                .matchedUsers(result.getSaved())
                .unmatchedRows(unmatched)
                .warnings(allMessages)
                .status(getStatus(month, year))
                .build();
    }

    @Transactional
    public UploadResultDto uploadAllowance(MultipartFile file, int month, int year, User actor) {
        requireValidPeriod(month, year);
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Vui lòng chọn file.");

        assertCanUploadAdjustments(month, year);

        var result = payrollAdjustmentService.importAllowance(file, month, year, null);
        int unmatched = result.getUnmatchedItems() == null ? 0 : result.getUnmatchedItems().size();
        List<String> allMessages = new ArrayList<>();
        if (result.getWarnings() != null) allMessages.addAll(result.getWarnings());
        if (result.getErrors()   != null) allMessages.addAll(result.getErrors());
        return UploadResultDto.builder()
                .fileName(file.getOriginalFilename())
                .parsedRows(result.getRowsRead())
                .matchedUsers(result.getSaved())
                .unmatchedRows(unmatched)
                .warnings(allMessages)
                .status(getStatus(month, year))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. TÍNH LƯƠNG — nút "Tính lương" / "Mở lại"
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public CalculateResultDto calculate(int month, int year, User actor) {
        AttendanceSheet sheet = sheetRepo.findCompanySheet(month, year)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Chưa có bảng chấm công cho %d/%d — vui lòng upload trước.".formatted(month, year)));

        if (!sheet.hasAttendanceFile())
            throw new IllegalArgumentException("Bảng chấm công tháng %d/%d chưa có file.".formatted(month, year));

        if (!sheet.getCalcStatus().canCalculate())
            throw new IllegalArgumentException(
                    "Không thể tính lương khi đang ở trạng thái " + sheet.getCalcStatus()
                            + ". Vui lòng Mở lại trước.");

        List<String> warnings = new ArrayList<>();

        // Dọn khoản AUTO_OT cũ (phòng khi recalc do dùng cả CompanyPayrollService
        // tiếng cũ, hoặc dữ liệu rơi rớt từ phiên trước)
        adjustmentRepo.deleteByPeriodAndSource(month, year, Source.AUTO_OT);

        // Nạp danh sách ngày lễ 1 lần
        Set<LocalDate> holidays = holidayService.datesOfMonth(month, year);
        int standardDays = calc.standardWorkdaysInMonth(month, year);
        sheet.setStandardDays((double) standardDays);

        List<AttendanceEntry> entries = entryRepo.findBySheet_Id(sheet.getId());

        long totalWk = 0, totalSn = 0, totalHd = 0, totalAmount = 0;
        int employeesComputed = 0;
        int otRowsCreated = 0;

        for (AttendanceEntry e : entries) {
            User u = e.getUser();
            if (u == null) continue;

            int[] mins = extractOtMinutesFromDaily(e.getDailyJson(), holidays, warnings, u.getFullName());
            int wk = mins[0], sn = mins[1], hd = mins[2];

            long baseSalary = resolveBaseSalary(u, month, year);
            OtResult r = calc.computeOtAmount(baseSalary, standardDays, wk, sn, hd);

            e.setOtWeekdayMinutes(wk);
            e.setOtSundayMinutes(sn);
            e.setOtHolidayMinutes(hd);
            e.setOtAmount(r.totalAmount());

            totalWk += wk; totalSn += sn; totalHd += hd; totalAmount += r.totalAmount();
            employeesComputed++;

            // Tạo bản ghi ALLOWANCE AUTO_OT TRƯỚC khi snapshot, để snapshot bao
            // gồm cả OT.
            if (r.totalAmount() > 0) {
                MonthlyAdjustment adj = MonthlyAdjustment.builder()
                        .user(u).month(month).year(year)
                        .type(Type.ALLOWANCE)
                        .label(MonthlyAdjustment.LABEL_AUTO_OT)
                        .amount(r.totalAmount())
                        .source(Source.AUTO_OT)
                        .department(departmentOf(u))
                        .build();
                adjustmentRepo.save(adj);
                otRowsCreated++;
            }

            // ── PHASE 4: SNAPSHOT ────────────────────────────────────────
            // Flush adjustment vừa tạo rồi mới gọi lại HrService.getSalary
            // Breakdown để lấy breakdown bao gồm OT. Lưu toàn bộ JSON + 10
            // cột denormalized cho export.
            em.flush();
            saveSnapshot(e, u, month, year, warnings);
            entryRepo.save(e);
        }

        sheet.setOtTotalWeekdayMinutes(totalWk);
        sheet.setOtTotalSundayMinutes(totalSn);
        sheet.setOtTotalHolidayMinutes(totalHd);
        sheet.setOtTotalAmount(totalAmount);
        sheet.markCalculated(actor != null ? actor.getFullName() : null);
        sheetRepo.save(sheet);

        log.info("[CompanyPayroll] Đã tính lương {}/{}: {} nhân viên, OT = {}đ, {} khoản AUTO_OT",
                month, year, employeesComputed, totalAmount, otRowsCreated);

        return CalculateResultDto.builder()
                .month(month).year(year)
                .employeesComputed(employeesComputed)
                .otTotalAmount(totalAmount)
                .otAllowanceRowsCreated(otRowsCreated)
                .warnings(warnings)
                .status(getStatus(month, year))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. PUBLISH / UNPUBLISH / REOPEN
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public CompanyPeriodStatusDto publish(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (!s.getCalcStatus().canPublish())
            throw new IllegalArgumentException("Trạng thái hiện tại không cho Public: " + s.getCalcStatus());
        s.markPublished(actor != null ? actor.getFullName() : null);
        sheetRepo.save(s);
        log.info("[CompanyPayroll] PUBLISH {}/{} bởi {}", month, year, actor != null ? actor.getFullName() : "?");
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto unpublish(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (!s.getCalcStatus().canUnpublish())
            throw new IllegalArgumentException("Trạng thái hiện tại không cho Unpublic: " + s.getCalcStatus());
        s.markUnpublished();
        sheetRepo.save(s);
        log.info("[CompanyPayroll] UNPUBLISH {}/{} bởi {}", month, year, actor != null ? actor.getFullName() : "?");
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto reopen(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (!s.getCalcStatus().canReopen())
            throw new IllegalArgumentException(
                    "Chỉ Mở lại khi đang ở trạng thái CALCULATED. Nếu đang PUBLISHED, vui lòng Unpublic trước.");

        // Xoá khoản OT auto + dọn OT + snapshot trên từng entry
        adjustmentRepo.deleteByPeriodAndSource(month, year, Source.AUTO_OT);
        for (AttendanceEntry e : entryRepo.findBySheet_Id(s.getId())) {
            e.setOtWeekdayMinutes(0);
            e.setOtSundayMinutes(0);
            e.setOtHolidayMinutes(0);
            e.setOtAmount(0L);
            e.clearSnapshot();                              // Phase 4
            entryRepo.save(e);
        }
        s.markReopened();
        sheetRepo.save(s);
        log.info("[CompanyPayroll] REOPEN {}/{} bởi {}", month, year, actor != null ? actor.getFullName() : "?");
        return getStatus(month, year);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4 — LIFECYCLE KPI (xưởng) + BONUS (sales/accounting)
    //
    // Hai luồng này chạy per-department với logic nghiệp vụ do FactoryKpiService
    // / OfficeBonusService đảm nhiệm. Phase 4 chỉ bổ sung tracking trạng thái 3
    // bước giống lương chính — vòng đời Calculate → Publish ↔ Unpublic → Reopen.
    //
    // Hiện tại việc TÍNH thật sự KPI/Bonus vẫn do controller cũ (FactoryPayroll
    // Controller.finalizeKpi, calcSalesKpi, calcSalesBonus...) kích hoạt. Service
    // KPI/Bonus chưa được rewire để gọi vào các method dưới đây. Phase 4+ (hoặc
    // Phase 5) sẽ wire xong để FE gọi 4 endpoint ngắn gọn thay cho 2-3 endpoint
    // phân mảnh hiện tại.
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public CompanyPeriodStatusDto kpiCalculate(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getKpiCalcStatus() == PayrollCalcStatus.PUBLISHED)
            throw new IllegalArgumentException("KPI đã Public — Unpublic trước khi tính lại.");
        s.markKpiCalculated(actor != null ? actor.getFullName() : null);
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto kpiPublish(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getKpiCalcStatus() != PayrollCalcStatus.CALCULATED)
            throw new IllegalArgumentException("Phải tính KPI trước khi Public.");
        s.markKpiPublished(actor != null ? actor.getFullName() : null);
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto kpiUnpublish(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getKpiCalcStatus() != PayrollCalcStatus.PUBLISHED)
            throw new IllegalArgumentException("KPI chưa Public thì không Unpublic được.");
        s.markKpiUnpublished();
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto kpiReopen(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getKpiCalcStatus() != PayrollCalcStatus.CALCULATED)
            throw new IllegalArgumentException("Chỉ Mở lại khi KPI đang ở trạng thái CALCULATED.");
        s.markKpiReopened();
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto bonusCalculate(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getBonusCalcStatus() == PayrollCalcStatus.PUBLISHED)
            throw new IllegalArgumentException("Thưởng DT đã Public — Unpublic trước khi tính lại.");
        s.markBonusCalculated(actor != null ? actor.getFullName() : null);
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto bonusPublish(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getBonusCalcStatus() != PayrollCalcStatus.CALCULATED)
            throw new IllegalArgumentException("Phải tính Thưởng DT trước khi Public.");
        s.markBonusPublished(actor != null ? actor.getFullName() : null);
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto bonusUnpublish(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getBonusCalcStatus() != PayrollCalcStatus.PUBLISHED)
            throw new IllegalArgumentException("Thưởng DT chưa Public thì không Unpublic được.");
        s.markBonusUnpublished();
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    @Transactional
    public CompanyPeriodStatusDto bonusReopen(int month, int year, User actor) {
        AttendanceSheet s = requireCompanySheet(month, year);
        if (s.getBonusCalcStatus() != PayrollCalcStatus.CALCULATED)
            throw new IllegalArgumentException("Chỉ Mở lại khi Thưởng DT đang ở trạng thái CALCULATED.");
        s.markBonusReopened();
        sheetRepo.save(s);
        return getStatus(month, year);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. GUARD CHO MODULE PHỤ CẤP / THƯỞNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@link PayrollAdjustmentService#importBonus} và {@link PayrollAdjustmentService#importAllowance}
     * gọi hàm này trước khi ghi để chặn upload khi lương đã CALCULATED/PUBLISHED.
     */
    public void assertCanUploadAdjustments(int month, int year) {
        AttendanceSheet s = sheetRepo.findCompanySheet(month, year).orElse(null);
        if (s == null) return;      // chưa có bảng → chưa tính lương, cho phép
        assertCanUploadAdjustments(s);
    }

    private void assertCanUploadAdjustments(AttendanceSheet s) {
        if (!s.getCalcStatus().canUploadAdjustments())
            throw new IllegalArgumentException(
                    "Lương tháng này đang ở trạng thái " + s.getCalcStatus()
                            + " — không thể cập nhật thưởng/lịch nghỉ/phụ cấp. Vui lòng Mở lại trước.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS NỘI BỘ
    // ══════════════════════════════════════════════════════════════════════════

    private AttendanceSheet getOrCreateCompanySheet(int month, int year) {
        return sheetRepo.findCompanySheet(month, year).orElseGet(() -> {
            AttendanceSheet s = AttendanceSheet.builder()
                    .month(month).year(year)
                    .department(AttendanceSheetRepository.COMPANY_SENTINEL)
                    .standardDays((double) calc.standardWorkdaysInMonth(month, year))
                    .status(AttendanceSheet.SheetStatus.UPLOADED)
                    .calcStatus(PayrollCalcStatus.NONE)
                    .build();
            return sheetRepo.save(s);
        });
    }

    private AttendanceSheet requireCompanySheet(int month, int year) {
        return sheetRepo.findCompanySheet(month, year)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Chưa có dữ liệu lương cho tháng %d/%d.".formatted(month, year)));
    }

    private void requireValidPeriod(int month, int year) {
        if (month < 1 || month > 12)
            throw new IllegalArgumentException("Tháng phải trong 1..12.");
    }

    private void tryDeleteDiskFile(String path) {
        if (path == null || path.isBlank()) return;
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (Exception e) {
            log.warn("[CompanyPayroll] Không xoá được file {}: {}", path, e.getMessage());
        }
    }

    /**
     * Index nhân viên theo mã, chịu được zero-padding.
     *
     * <p>HOTFIX (10/2026): File chấm công máy xuất mã padded 0 thành 5 digit
     * (VD "00001", "01007", "10015"), còn {@code User.employeeCode} trong DB
     * có thể không pad ("1", "1007", "10015"). Giờ 1 user được index dưới
     * NHIỀU key:
     * <ul>
     *   <li>Nguyên bản (trim upper): "1" → key "1"</li>
     *   <li>Nếu numeric: thêm bản không có zero đầu: "00001" → "1"</li>
     *   <li>Nếu numeric: thêm bản pad về 5 digit: "1" → "00001"</li>
     * </ul>
     * Khi resolve, thử trực tiếp mã trong file; nếu miss, thử numeric
     * normalize cả 2 chiều.
     */
    private Map<String, User> indexUsersByCode() {
        Map<String, User> m = new HashMap<>();
        for (User u : allPayrollEmployees()) {
            String c = u.getEmployeeCode();
            if (c == null || c.isBlank()) continue;
            String norm = c.trim().toUpperCase(Locale.ROOT);
            m.put(norm, u);

            // Nếu toàn số, thêm key không có zero đầu và key pad 5 digit
            if (norm.matches("\\d+")) {
                String stripped = norm.replaceFirst("^0+", "");
                if (stripped.isEmpty()) stripped = "0";
                m.putIfAbsent(stripped, u);
                if (stripped.length() <= 5) {
                    m.putIfAbsent(String.format("%05d", Long.parseLong(stripped)), u);
                }
            }
        }
        return m;
    }

    /** Index tất cả nhân viên theo tên đã chuẩn hoá (bỏ dấu, lowercase, dồn khoảng trắng). */
    private Map<String, User> indexUsersByNormalizedName() {
        Map<String, User> m = new HashMap<>();
        for (User u : allPayrollEmployees()) {
            String n = normalizeName(u.getFullName());
            if (n == null) continue;
            m.putIfAbsent(n, u);
        }
        return m;
    }

    /**
     * Toàn bộ nhân viên có thể nhận lương theo bộ phận.
     *
     * <p>FIX (10/2026): file chấm công hiện DÙNG CHUNG cho cả công ty, nên pool
     * resolve phải khớp đúng pool mà {@link PayrollDepartmentResolver#employeesWithLockedOf}
     * dùng khi dựng bảng lương — nếu không, nhân viên có trong bảng lương
     * nhưng không có trong index chấm công → upload không match → preview 0
     * công. Trước đây chỉ query {@code findByRolesContaining} nên bỏ sót:
     * <ul>
     *   <li>User có {@code payroll_role} được set tay (VD Kế toán xưởng login
     *       bằng role ACCOUNTANT, payroll_role = FACTORY_ACCOUNTANT).</li>
     *   <li>User có role chính (cột {@code role}) khác tập {@code roles}.</li>
     * </ul>
     * Giờ query cả 3 cách giống {@code PayrollDepartmentResolver}.
     * Lọc nhân viên đã xoá mềm; GIỮ cả tài khoản bị khoá — họ có thể đã đi làm
     * đầu tháng rồi mới bị khoá, chấm công của họ vẫn phải được ghi nhận.
     */
    private List<User> allPayrollEmployees() {
        Map<Long, User> merged = new LinkedHashMap<>();
        for (Role r : PayrollDepartment.allPayrollRoles()) {
            userRepo.findByRole(r).forEach(u -> merged.put(u.getId(), u));
            userRepo.findByRolesContaining(r).forEach(u -> merged.put(u.getId(), u));
            userRepo.findByPayrollRole(r).forEach(u -> merged.put(u.getId(), u));
        }
        return merged.values().stream()
                .filter(u -> !u.isDeleted())
                .toList();
    }

    /**
     * Resolve nhân viên — ƯU TIÊN TÊN.
     *
     * <p>Thay đổi (10/2026): theo yêu cầu nghiệp vụ, file chấm công nay match
     * theo <b>TÊN NHÂN VIÊN</b> làm chiến lược chính. Mã chấm công chỉ còn làm
     * fallback cho các dòng viết tắt / nickname (file hiện có "Quoc", "Duyen",
     * "ThiLinh", "HongNgoc", "Tung" — không khớp bất kỳ tên đầy đủ nào trong
     * DB nên bắt buộc phải khớp bằng mã).
     *
     * <p>Thứ tự thực thi:
     * <ol>
     *   <li>Tên đầy đủ chuẩn hoá khớp CHÍNH XÁC một user trong DB.</li>
     *   <li>Tên file là substring của tên DB (hoặc ngược lại) và CHỈ CÓ ĐÚNG
     *       1 ứng viên — bắt các dạng viết thiếu ("Thuý Vi" cho "Lê Huỳnh
     *       Thúy Vi"), viết khác dấu, hoặc viết hoa chữ cái bất thường. Nếu
     *       nhiều hơn 1 candidate → KHÔNG match để tránh gán nhầm người.</li>
     *   <li>Fallback cuối — mã nhân viên khớp (index đã bao gồm cả bản
     *       zero-pad và không zero-pad). Áp dụng cho các dòng chỉ có nickname
     *       mà không có họ tên đầy đủ trong file.</li>
     * </ol>
     */
    private User resolveUser(EmployeeBlock b, Map<String, User> byCode, Map<String, User> byName) {
        // ── 1. Tên đầy đủ khớp chính xác ────────────────────────────────────
        String norm = normalizeName(b.getEmployeeName());
        if (norm != null) {
            User u = byName.get(norm);
            if (u != null) return u;

            // ── 2. Tên fuzzy — substring 2 chiều, chỉ chấp nhận khi DUY NHẤT ──
            List<User> candidates = new ArrayList<>();
            for (var entry : byName.entrySet()) {
                String dbName = entry.getKey();
                if (dbName.contains(norm) || norm.contains(dbName)) {
                    candidates.add(entry.getValue());
                }
            }
            if (candidates.size() == 1) return candidates.get(0);
            // Nếu > 1 candidate: fuzzy không an toàn, rơi xuống match mã.
        }

        // ── 3. Fallback: mã nhân viên ───────────────────────────────────────
        if (b.getEmployeeCode() != null && !b.getEmployeeCode().isBlank()) {
            String code = b.getEmployeeCode().trim().toUpperCase(Locale.ROOT);
            User u = byCode.get(code);
            if (u != null) return u;
        }
        return null;
    }

    static String normalizeName(String s) {
        if (s == null) return null;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
        return n.isBlank() ? null : n;
    }

    /** Dept code để gắn vào MonthlyAdjustment (giữ convention cũ). */
    private String departmentOf(User u) {
        PayrollDepartment d = PayrollDepartment.of(PayrollDepartment.resolvePayrollRole(u.getRoles()));
        return d != null ? d.name() : null;
    }

    /**
     * Dựng {@link AttendanceEntry} từ 1 {@link EmployeeBlock}.
     * Chi tiết từng ngày đã có sẵn trong dailyJson (serialize ở helper riêng
     * để dùng chung với {@code FactoryPayrollService.buildDailyJson}); Phase 2
     * này tạm serialize gọn tại chỗ — Phase 4 sẽ hợp nhất 2 chỗ này.
     */
    /**
     * Số phút làm tối thiểu để tính 1 ngày công FULL-TIME. Ngang với
     * FactoryPayrollService.resolveDay — không import constant trực tiếp để
     * tránh circular, nhưng giữ ĐỒNG BỘ bằng tay.
     */
    private static final int FULL_DAY_MIN_WORKED = 360;   // ≥ 6h   = 1 công
    private static final int HALF_DAY_MIN_WORKED = 240;   // ≥ 4h   = 0.5 công
    private static final int PART_TIME_MIN_WORKED = 120;  // ≥ 2h   = 0.5 công cho part-time
    /** Du di đi trễ: 8:00-8:05 vẫn tính đúng giờ; từ 8:06 trở đi tính FULL số phút trễ. */
    private static final int GRACE_MINUTES = 5;
    /** 1 ngày phép = 480 phút (khớp FactoryPayrollService.LEAVE_MINUTES_PER_DAY). */
    private static final int LEAVE_MINUTES_PER_DAY = 480;

    private AttendanceEntry buildEntryFromBlock(AttendanceSheet sheet, User u, EmployeeBlock b) {
        boolean isPartTime = isPartTime(u);
        DayCount dc = computeDayCount(b, isPartTime);

        // ── TRỪ PHÚT ĐI TRỄ VÀO QUỸ PHÉP, HẾT PHÉP → TRỪ NGÀY CÔNG ──────────
        //
        // Chính sách (10/2026, user confirm):
        //   1. GỘP cả tháng rồi mới trừ (không trừ từng ngày).
        //   2. Du di 5 phút PER NGÀY: 8:00-8:05 vào = 0 phút trễ; 8:06 = 6 phút
        //      trễ (phạt FULL số phút sau mốc chuẩn).
        //   3. Trừ vào quỹ phép trước (1 ngày phép = 480 phút).
        //   4. Hết phép → phần còn thừa chia 480 ra ngày công trừ thẳng vào
        //      actualDays.
        //   5. CHỈ TRỪ phút ĐI TRỄ. Về sớm KHÔNG trừ (cho MỌI bộ phận, kể cả
        //      Xưởng).
        //   6. Part-time không áp (họ chỉ có 0.5 công/ngày sẵn, trễ chỉ ghi
        //      nhận KPI).
        int monthlyLateMinutes = isPartTime ? 0 : dc.lateMinutesForPenalty;
        int leaveBalanceMinutes = loadLeaveBalanceMinutes(u, sheet.getYear());
        int consumedFromLeave   = Math.min(monthlyLateMinutes, leaveBalanceMinutes);
        int remainingLate       = monthlyLateMinutes - consumedFromLeave;
        int leaveBalanceAfter   = leaveBalanceMinutes - consumedFromLeave;
        double lateDaysDeduct   = remainingLate / (double) LEAVE_MINUTES_PER_DAY;
        double adjustedActualDays = Math.max(0.0, dc.actualDays - lateDaysDeduct);

        return AttendanceEntry.builder()
                .sheet(sheet)
                .user(u)
                .userFullName(u.getFullName())
                .employeeCode(b.getEmployeeCode())
                .sourceName(b.getEmployeeName())
                .presentDays(dc.presentDays)
                .machineTotalHours(b.getTotalHours())
                .machineTotalWorkUnits(b.getTotalWorkUnits())
                .lateCount(b.getLateCount())
                .lateMinutes(dc.lateMinutesForPenalty)   // tổng phút trễ (đã áp du di)
                .earlyCount(b.getEarlyCount())
                .earlyMinutes(b.getEarlyMinutes())       // vẫn ghi nhận cho báo cáo, KHÔNG trừ
                .leaveMinutesUsed(consumedFromLeave)
                .leaveBalanceMinutesAfter(leaveBalanceAfter)
                .standardDays((double) calc.standardWorkdaysInMonth(sheet.getMonth(), sheet.getYear()))
                .actualDays(adjustedActualDays)
                .mealDays(dc.mealDays)
                .partTime(isPartTime)
                .dailyJson(serializeDaily(b.getDays()))
                .build();
    }

    /**
     * Số phút phép còn lại của nhân viên tại năm (480 × ngày phép còn).
     * CLAMP về ≥ 0 (quỹ âm do dữ liệu cũ → coi như hết). Lỗi lấy balance
     * (user chưa có workStartDate…) → trả 0 cho an toàn.
     */
    private int loadLeaveBalanceMinutes(User u, int year) {
        try {
            var bal = employeeRequestService.leaveBalance(u.getId(), year);
            double days = bal.getRemainingDays() != null ? bal.getRemainingDays() : 0.0;
            return (int) Math.max(0, Math.round(days * LEAVE_MINUTES_PER_DAY));
        } catch (Exception e) {
            log.warn("[CompanyPayroll] Không lấy được quỹ phép của {}: {}",
                    u.getFullName(), e.getMessage());
            return 0;
        }
    }

    /**
     * TRUE nếu hồ sơ lương MỚI NHẤT của nhân viên có cờ part-time.
     * Không có hồ sơ → full-time.
     */
    private boolean isPartTime(User u) {
        try {
            var list = salaryRepo.findAllByUserIdOrderByCreatedAtDesc(u.getId());
            return !list.isEmpty() && Boolean.TRUE.equals(list.get(0).getPartTime());
        } catch (Exception e) {
            return false;
        }
    }

    /** Kết quả đếm công/cơm/phút trễ từ 1 block. */
    private record DayCount(int presentDays, double actualDays, double mealDays,
                            int lateMinutesForPenalty) {}

    /** Ca chuẩn (khớp FactoryPayrollService). */
    private static final LocalTime SHIFT_START = LocalTime.of(8, 0);
    private static final LocalTime SHIFT_END   = LocalTime.of(17, 0);

    /**
     * Đếm công, ngày cơm, phút trễ từ dailyJson theo logic KHỚP
     * {@code FactoryPayrollService.resolveDay}.
     *
     * <p><b>Full-time:</b>
     * <ul>
     *   <li>worked ≥ 360 phút (6h) → 1.0 công, có cơm.</li>
     *   <li>worked ≥ 240 phút (4h) → 0.5 công, KHÔNG cơm.</li>
     *   <li>worked &lt; 240 phút         → 0 công, KHÔNG cơm.</li>
     * </ul>
     *
     * <p><b>Part-time (ca ~4h sáng):</b>
     * <ul>
     *   <li>worked ≥ 120 phút (2h) → 0.5 công, cơm chỉ nếu worked ≥ 360 phút.</li>
     *   <li>worked &lt; 120 phút         → 0 công, KHÔNG cơm.</li>
     * </ul>
     *
     * <p><b>Phút trễ áp DU DI 5 phút MỖI NGÀY:</b>
     * <ul>
     *   <li>Vào ≤ 8:05 → 0 phút trễ (đúng giờ, du di).</li>
     *   <li>Vào ≥ 8:06 → tính FULL số phút sau 8:00 (8:06 = 6 phút, 8:13 = 13 phút).</li>
     * </ul>
     * Về sớm KHÔNG trừ cho mọi bộ phận (theo yêu cầu 10/2026).
     */
    private DayCount computeDayCount(EmployeeBlock b, boolean isPartTime) {
        if (b.getDays() == null) return new DayCount(0, 0.0, 0.0, 0);

        int present = 0;
        double actual = 0.0, meal = 0.0;
        int lateMinSum = 0;

        for (DayRecord d : b.getDays()) {
            if (!d.hasPunch()) continue;
            present++;

            LocalTime in = d.firstIn(), out = d.lastOut();

            // FIX (10/2026 — user request): nhân viên quên quẹt 1 đầu thì
            // mặc định đầu còn lại là mốc chuẩn, để vẫn tính được "worked"
            // (từ đó quyết định ngày này có cơm / có công hay không).
            //   • Có giờ VÀO, thiếu giờ RA → mặc định ra 17:00.
            //   • Có giờ RA, thiếu giờ VÀO → mặc định vào 08:00.
            // Dấu hiệu "mặc định" đã được serializeDaily ghi cờ `defaultedOut`
            // / `defaultedIn` để UI Chuyên cần hiện viền vàng dashed.
            //
            // Lưu ý: KHÔNG áp cho ngày CN; ngày lễ thì đã bị `!hasPunch()`
            // filter rồi, không rơi vào đây.
            if (d.getDate() != null && d.getDate().getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                if (in != null && out == null)      out = SHIFT_END;
                else if (out != null && in == null) in  = SHIFT_START;
            }

            int worked = (in != null && out != null)
                    ? Math.max(0, (int) java.time.Duration.between(in, out).toMinutes())
                    : 0;

            // Công
            if (isPartTime) {
                if (worked >= PART_TIME_MIN_WORKED) actual += 0.5;
                if (worked >= FULL_DAY_MIN_WORKED)  meal   += 1.0;
            } else {
                if      (worked >= FULL_DAY_MIN_WORKED) { actual += 1.0; meal += 1.0; }
                else if (worked >= HALF_DAY_MIN_WORKED) { actual += 0.5; }
            }

            // Phút trễ ngày này (áp du di 5' per-day) — chỉ cộng cho full-time.
            // Dùng `in` đã có thể đã được default 08:00 ở trên → ngày thiếu
            // giờ vào sẽ coi như KHÔNG trễ (lateMin = 0), đúng tinh thần
            // "nhân viên có đi làm, chỉ quên quẹt" không bị phạt.
            if (!isPartTime && in != null) {
                int lateMin = (int) java.time.Duration.between(SHIFT_START, in).toMinutes();
                if (lateMin > GRACE_MINUTES) lateMinSum += lateMin;
            }
        }
        return new DayCount(present, actual, meal, lateMinSum);
    }

    /**
     * Serialize list of DayRecord thành JSON array.
     *
     * <p>HOTFIX (10/2026): bổ sung các field mà {@link AttendanceDetailService}
     * cần cho trang Chuyên cần:
     * <ul>
     *   <li>{@code "d"} — day-of-month số nguyên. Trước đây chỉ có {@code "date"}
     *       nên AttendanceDetailService không parse được từng ngày.</li>
     *   <li>{@code "defaultedOut"} — true nếu giờ ra được fill mặc định 17:00
     *       (Phase 6a yêu cầu). Logic default-checkout áp ngay trong hàm này
     *       cho đồng bộ với FactoryPayrollService.buildEntry.</li>
     * </ul>
     *
     * <p>Phase 7 là luồng duy nhất tạo AttendanceEntry (per-dept đã gỡ), nên
     * ĐÂY là chỗ cần ghi đủ thông tin cho mọi UI tiêu thụ dailyJson.
     */
    private String serializeDaily(List<DayRecord> days) {
        if (days == null) return "[]";
        try {
            // Nạp danh sách ngày lễ tháng này 1 lần để check holiday khi default checkout
            java.time.YearMonth ym = null;
            Set<java.time.LocalDate> holidays = Set.of();
            if (!days.isEmpty() && days.get(0).getDate() != null) {
                ym = java.time.YearMonth.from(days.get(0).getDate());
                holidays = holidayService.datesOfMonth(ym.getMonthValue(), ym.getYear());
            }

            // SHIFT_START / SHIFT_END đã khai báo ở cấp class.

            List<Map<String, Object>> arr = new ArrayList<>();
            for (DayRecord d : days) {
                Map<String, Object> m = new LinkedHashMap<>();
                java.time.LocalDate date = d.getDate();

                // "d" số thứ tự ngày — bắt buộc để AttendanceDetailService
                // index đúng ô trong matrix Chuyên cần.
                if (date != null) m.put("d", date.getDayOfMonth());
                m.put("date", date != null ? date.toString() : null);
                m.put("w", d.getWeekdayLabel());
                m.put("sym", d.getSymbol());

                LocalTime in = d.firstIn(), out = d.lastOut();
                boolean defaultedOut = false, defaultedIn = false;

                // Fill giờ còn thiếu — ĐỒNG BỘ với computeDayCount để UI
                // Chuyên cần nhìn ra ngày này được tính "worked" vì default
                // bên nào. Áp dụng cho ngày thường (không CN, không lễ) và
                // ngày đó phải có ít nhất một đầu chấm công.
                //   • Có VÀO, thiếu RA → ra = 17:00 (viền vàng dashed).
                //   • Có RA, thiếu VÀO → vào = 08:00 (viền vàng dashed khác).
                if (date != null
                        && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY
                        && !holidays.contains(date)) {
                    if (in != null && out == null) {
                        out = SHIFT_END;
                        defaultedOut = true;
                    } else if (out != null && in == null) {
                        in = SHIFT_START;
                        defaultedIn = true;
                    }
                }

                if (in  != null) m.put("in",  in.toString());
                if (out != null) m.put("out", out.toString());
                if (defaultedOut) m.put("defaultedOut", true);
                if (defaultedIn)  m.put("defaultedIn", true);
                arr.add(m);
            }
            return JSON.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("[CompanyPayroll] Lỗi serialize daily JSON", e);
            return "[]";
        }
    }

    /**
     * Trích {@code [weekdayMinutes, sundayMinutes, holidayMinutes]} từ chuỗi
     * JSON dailyJson. Áp dụng công thức Phase 1 cho từng ngày.
     *
     * <p>JSON hỗ trợ 2 schema:
     * <ul>
     *   <li>Phase 2 (do {@link #serializeDaily} sinh ra): {@code [{"date":"2025-09-01","in":"08:00","out":"18:03"}, ...]}</li>
     *   <li>Legacy (FactoryPayrollService): {@code [{"d":1,"in":"08:00","out":"18:03"}, ...]} — không có year/month,
     *       được bù từ sheet.</li>
     * </ul>
     */
    int[] extractOtMinutesFromDaily(String dailyJson, Set<LocalDate> holidays,
                                    List<String> warnings, String contextName) {
        if (dailyJson == null || dailyJson.isBlank()) return new int[]{0, 0, 0};
        int wk = 0, sn = 0, hd = 0;
        try {
            JsonNode arr = JSON.readTree(dailyJson);
            if (!arr.isArray()) return new int[]{0, 0, 0};
            for (JsonNode n : arr) {
                LocalDate date = readDate(n);
                if (date == null) continue;
                LocalTime in = readTime(n, "in");
                LocalTime out = readTime(n, "out");
                DayCategory cat = calc.categorize(date, holidays);
                switch (cat) {
                    case WEEKDAY -> wk += calc.weekdayOtMinutes(out);
                    case SUNDAY  -> sn += calc.weekendOrHolidayWorkedMinutes(in, out);
                    case HOLIDAY -> hd += calc.weekendOrHolidayWorkedMinutes(in, out);
                }
            }
        } catch (Exception e) {
            warnings.add("Không parse được dailyJson của " + contextName + ": " + e.getMessage());
        }
        return new int[]{wk, sn, hd};
    }

    private LocalDate readDate(JsonNode n) {
        JsonNode d = n.get("date");
        if (d != null && !d.isNull()) {
            try { return LocalDate.parse(d.asText()); } catch (Exception ignored) {}
        }
        return null;
    }

    private LocalTime readTime(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        try { return LocalTime.parse(v.asText()); } catch (Exception ignored) { return null; }
    }

    /**
     * SNAPSHOT lương của 1 nhân viên vào {@link AttendanceEntry} — Phase 4.
     *
     * <p>Gọi vào cuối vòng lặp calculate() SAU khi khoản AUTO_OT đã được flush,
     * nên breakdown trả ra đã bao gồm OT. Lưu dạng JSON cho đầy đủ + 10 cột
     * denormalized để file export đọc không cần parse JSON.
     *
     * <p>Nếu HrService lỗi (user chưa cấu hình lương…) thì không fail cả calc
     * — chỉ warning và để snapshot = null; export sẽ fallback về compute live.
     */
    private void saveSnapshot(AttendanceEntry entry, User u, int month, int year, List<String> warnings) {
        try {
            SalaryBreakdownDto bd = hrService.getSalaryBreakdownForUser(u.getId(), month, year);
            if (bd == null) return;

            // Lưu JSON đầy đủ
            try {
                entry.setSalarySnapshotJson(JSON.writeValueAsString(bd));
            } catch (Exception ignored) { /* non-critical */ }

            entry.setSnapBaseSalary(nz(bd.getStandardBaseSalary() != null
                    ? bd.getStandardBaseSalary() : bd.getBaseSalary()));
            entry.setSnapProratedBase(nz(bd.getBaseSalary()));
            entry.setSnapInsuranceSalary(nz(bd.getInsuranceSalary()));
            entry.setSnapInsuranceTotal(nz(bd.getEmployeeInsuranceTotal()) + nz(bd.getEmployerInsuranceTotal()));

            // Phân tách phụ cấp thành 5 nhóm: cơm / điện thoại / OT / trách nhiệm / khác
            long meal = 0, phone = 0, ot = 0, responsibility = 0, other = 0;
            if (bd.getAllowances() != null) {
                for (var a : bd.getAllowances()) {
                    String lbl = a.getLabel() == null ? "" : a.getLabel().toLowerCase();
                    long amt = nz(a.getAmount());
                    if (lbl.contains("cơm") || lbl.contains("com")) meal += amt;
                    else if (lbl.contains("điện thoại") || lbl.contains("dien thoai") || lbl.contains("phone")) phone += amt;
                    else if (lbl.contains("ot") || lbl.contains("overtime") || lbl.contains("tăng ca")) ot += amt;
                    else if (lbl.contains("trách nhiệm") || lbl.contains("trach nhiem") || lbl.contains("responsibility")) responsibility += amt;
                    else other += amt;
                }
            }
            entry.setSnapMealAllowance(meal);
            entry.setSnapPhoneAllowance(phone);
            entry.setSnapOtAllowance(ot);
            entry.setSnapResponsibilityAllowance(responsibility);
            entry.setSnapOtherAllowance(other);

            // Thưởng: cộng kpiBonus + revenueBonus + bonus (manual)
            long bonus = 0;
            try {
                var m = bd.getClass().getMethod("getKpiBonus");
                Object v = m.invoke(bd); if (v instanceof Long l) bonus += l;
            } catch (Exception ignored) {}
            try {
                var m = bd.getClass().getMethod("getRevenueBonus");
                Object v = m.invoke(bd); if (v instanceof Long l) bonus += l;
            } catch (Exception ignored) {}
            try {
                var m = bd.getClass().getMethod("getBonus");
                Object v = m.invoke(bd); if (v instanceof Long l) bonus += l;
            } catch (Exception ignored) {}
            entry.setSnapBonusTotal(bonus);

            // Tổng thu nhập visibile (chưa trừ BH NV) + thực nhận
            long totalIncome = nz(bd.getBaseSalary()) + meal + phone + ot + responsibility + other + bonus;
            entry.setSnapTotalIncome(totalIncome);

            Long net = null;
            try {
                var m = bd.getClass().getMethod("getNetSalary");
                Object v = m.invoke(bd); if (v instanceof Long l) net = l;
            } catch (Exception ignored) {}
            if (net == null) {
                // Fallback: totalIncome − phần BH NV đóng
                long empIns = nz(bd.getEmployeeInsuranceTotal());
                net = totalIncome - empIns;
            }
            entry.setSnapNetPay(net);
            entry.setSnapComputedAt(System.currentTimeMillis());

        } catch (Exception e) {
            warnings.add("Không snapshot được lương của " + u.getFullName() + ": " + e.getMessage());
            log.warn("[CompanyPayroll] snapshot fail user {}", u.getId(), e);
        }
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    /**
     * Lương cơ bản dùng cho công thức OT: ưu tiên {@code standardBaseSalary} (lương
     * đủ công, chưa prorate). Rơi về {@code baseSalary} nếu standard chưa có.
     */
    private long resolveBaseSalary(User u, int month, int year) {
        try {
            SalaryBreakdownDto bd = hrService.getSalaryBreakdownForUser(u.getId(), month, year);
            if (bd == null) return 0L;
            Long std = bd.getStandardBaseSalary();
            if (std != null && std > 0) return std;
            return bd.getBaseSalary() != null ? bd.getBaseSalary() : 0L;
        } catch (Exception e) {
            log.warn("[CompanyPayroll] Không lấy được lương cơ bản của user {}: {}",
                    u.getId(), e.getMessage());
            return 0L;
        }
    }
}