package com.nhatnam.server.service.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.dto.hr.EmployeeRequestDtos.*;
import com.nhatnam.server.entity.EmployeeRequest;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.EmployeeRequestStatus;
import com.nhatnam.server.enumtype.EmployeeRequestType;
import com.nhatnam.server.enumtype.PayrollDepartment;
import com.nhatnam.server.repository.EmployeeRequestRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.utils.LeaveBalanceCalculator;
import com.nhatnam.server.utils.SeniorityCalculator;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * NGHIỆP VỤ ĐƠN NHÂN VIÊN — tạo, duyệt, thông báo, và quy đổi ra tác động lên
 * bảng chấm công.
 *
 * <p>Service này là NGUỒN SỰ THẬT DUY NHẤT cho câu hỏi "ngày X của nhân viên Y
 * được ưu đãi gì". {@code FactoryPayrollService} không tự đọc bảng đơn mà gọi
 * {@link #effectsForPeriod} rồi áp dụng — nhờ vậy luật duyệt đơn chỉ nằm ở một
 * chỗ, không bị chép lại trong công thức tính công.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeRequestService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Các trạng thái được coi là có hiệu lực khi tính công. */
    private static final List<EmployeeRequestStatus> EFFECTIVE = List.of(
            EmployeeRequestStatus.APPROVED_PAID,
            EmployeeRequestStatus.APPROVED_UNPAID,
            EmployeeRequestStatus.APPROVED_DEDUCTED);

    private final EmployeeRequestRepository repo;
    /** Cần ngày vào làm để suy ra quỹ phép theo thâm niên. */
    private final UserRepository userRepository;
    private final PayrollDepartmentResolver deptResolver;
    private final NotificationService notificationService;
    // ── MỚI: đọc usage lịch sử để đồng bộ với "Quản lý phép" ─────────────────
    // Trước đây leaveBalance() CHỈ đếm EmployeeRequest.paidLeaveDays →
    // bỏ sót phần OWNER nhập tay ở T1..T8 (usage lịch sử, trong đó có bù trễ/
    // sớm cộng dồn từ tháng cũ) và bỏ sót AttendanceEntry.leaveMinutesUsed ở
    // T9..T12. Kết quả: "Phiếu của tôi" của nhân viên thấy còn phép NHIỀU HƠN
    // thực tế → nhân viên gửi thêm đơn được → OWNER duyệt → quỹ về ÂM.
    private final com.nhatnam.server.repository.ManualLeaveUsageRepository manualLeaveUsageRepo;
    private final com.nhatnam.server.repository.AttendanceEntryRepository attendanceEntryRepo;

    /**
     * Tháng cuối cùng dùng số OWNER NHẬP TAY (ManualLeaveUsage). Tháng lớn hơn
     * → tự tính từ EmployeeRequest + AttendanceEntry.leaveMinutesUsed.
     * <p>Phải KHỚP với {@code LeaveManagementService.MANUAL_USAGE_MAX_MONTH}
     * (hiện = 8). Nếu OWNER mở thêm tháng manual thì đổi CẢ 2 nơi.
     */
    private static final int MANUAL_USAGE_MAX_MONTH = 8;

    /** 1 ngày công = 480 phút (8h). Khớp {@code LeaveManagementService.LEAVE_MINUTES_PER_DAY}. */
    private static final int LEAVE_MINUTES_PER_DAY = 480;



    // ══════════════════════════════════════════════════════════════════════════
    // 1. CẤU HÌNH FORM TẠO ĐƠN
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Giới hạn lịch cho từng loại đơn, tính sẵn ra NGÀY THẬT.
     *
     * <p>Trả ngày thật thay vì offset là cố ý: FE không phải biết "hôm nay" là
     * ngày nào theo múi giờ nào. Máy người dùng lệch múi giờ hay để sai đồng hồ
     * cũng không mở rộng được cửa sổ chọn ngày, vì server vẫn kiểm lại y hệt lúc
     * nhận đơn.
     *
     * <p><b>Nghỉ phép</b> dùng cửa sổ LÙI = ngày 1 THÁNG HIỆN TẠI; chiều TIẾN
     * KHÔNG giới hạn (hôm nay tháng 9 vẫn xin nghỉ cho tháng 12 được).
     * Các loại khác vẫn theo offset như cũ.
     */
    public RequestFormConfigDto formConfig() {
        LocalDate today = LocalDate.now(VN);
        YearMonth ym = YearMonth.from(today);

        List<RequestTypeOptionDto> types = new ArrayList<>();
        for (EmployeeRequestType t : EmployeeRequestType.values()) {
            LocalDate minDate, maxDate;
            if (t.isMonthScoped()) {
                // Nghỉ phép: lùi tối đa về ngày 1 tháng hiện tại; tiến không giới hạn.
                minDate = ym.atDay(1);
                maxDate = null;
            } else {
                minDate = today.plusDays(t.minOffsetDays());
                maxDate = t.maxOffsetDays() == null ? null : today.plusDays(t.maxOffsetDays());
            }

            types.add(RequestTypeOptionDto.builder()
                    .value(t.name())
                    .label(t.getLabel())
                    .rangeBased(t.isRangeBased())
                    .minutesBased(t.isMinutesBased())
                    .minDate(minDate)
                    .maxDate(maxDate)
                    .allowPartialDay(t == EmployeeRequestType.LEAVE)
                    .build());
        }

        return RequestFormConfigDto.builder()
                .today(today)
                .types(types)
                .shiftStart("08:00")
                .shiftEnd("17:00")
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. NHÂN VIÊN TẠO ĐƠN
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public EmployeeRequestDto create(User me, CreateRequestDto body) {
        EmployeeRequestType type = body.getType();
        LocalDate today = LocalDate.now(VN);

        LocalDate from = body.getFromDate();
        LocalDate to = type.isRangeBased()
                ? (body.getToDate() != null ? body.getToDate() : from)
                : from;   // loại 1 ngày: bỏ qua toDate client gửi lên

        if (to.isBefore(from))
            throw new IllegalArgumentException("Ngày kết thúc không được trước ngày bắt đầu.");

        validateWindow(type, from, today);
        validateWindow(type, to, today);

        Integer minutes = null;
        if (type.isMinutesBased()) {
            if (body.getMinutes() == null || body.getMinutes() <= 0)
                throw new IllegalArgumentException(
                        "Phiếu %s phải khai số phút.".formatted(type.getLabel().toLowerCase()));
            minutes = body.getMinutes();
        }

        // ── DANH SÁCH BUỔI NGHỈ ───────────────────────────────────────────
        //   Nguồn sự thật của phiếu nghỉ phép. Client gửi từng ngày kèm cờ
        //   sáng/chiều, nhờ vậy khai được cả nửa ngày lẫn ngày ngắt quãng.
        //   Không gửi thì suy ra nghỉ nguyên khoảng, cả ngày — giữ tương thích
        //   với client cũ và với các loại phiếu khác.
        List<EmployeeRequest.LeaveDay> days = buildLeaveDays(type, body, from, to);
        if (!days.isEmpty()) {
            from = days.get(0).getDate();
            to   = days.get(days.size() - 1).getDate();
            // Ngày thật đã đổi (bỏ bớt buổi ở hai đầu) nên phải kiểm lại cửa sổ.
            validateWindow(type, from, today);
            validateWindow(type, to, today);
        }

        if (body.getReason() == null || body.getReason().isBlank())
            throw new IllegalArgumentException("Bắt buộc nhập lý do.");

        // Chặn trùng — kiểm tra ở CẤP ĐỘ NGÀY thay vì khoảng ngày.
        //
        // Khoảng from–to của phiếu MỚI có thể bao trùm phiếu CŨ trên lịch nhưng
        // thực tế không nghỉ trùng ngày nào (nghỉ ngắt quãng). Chỉ throw khi TỒN
        // TẠI NGÀY CỤ THỂ nào đó vừa nằm trong phiếu mới vừa thuộc phiếu cũ.
        //
        // IDEMPOTENT: phiếu PENDING khớp chính xác → trả về luôn, không tạo mới.
        List<EmployeeRequest> overlapping = repo.findOverlapping(me.getId(), type, from, to);
        if (!overlapping.isEmpty()) {
            // ── Idempotent: cùng khoảng ngày + PENDING → coi như retry ──────
            EmployeeRequest first = overlapping.get(0);
            if (first.isPending()
                    && first.getFromDate().equals(from)
                    && first.getToDate().equals(to)) {
                log.info("[Request] Idempotent hit: {} đã có phiếu #{} PENDING {} — trả về phiếu cũ",
                        me.getFullName(), first.getId(), rangeText(from, to));
                return toDto(first);
            }

            // ── Kiểm tra xung đột từng ngày ────────────────────────────────
            // Gom tất cả ngày đang xin (từ danh sách buổi, hoặc trải toàn khoảng).
            Set<LocalDate> requestedDates = new HashSet<>();
            if (!days.isEmpty()) {
                for (EmployeeRequest.LeaveDay d : days) requestedDates.add(d.getDate());
            } else {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) requestedDates.add(d);
            }

            // Tìm ngày nào THẬT SỰ bị trùng
            List<String> conflicts = new ArrayList<>();
            for (EmployeeRequest existing : overlapping) {
                for (LocalDate d = existing.getFromDate(); !d.isAfter(existing.getToDate()); d = d.plusDays(1)) {
                    if (existing.covers(d) && requestedDates.contains(d)) {
                        conflicts.add("%s (%s)".formatted(d.format(D), existing.getStatus().getLabel()));
                    }
                }
            }

            if (!conflicts.isEmpty()) {
                throw new IllegalArgumentException(
                        "Các ngày sau đã có phiếu nghỉ: %s. Bỏ các ngày đó khỏi phiếu hoặc huỷ phiếu cũ trước."
                                .formatted(String.join(", ", conflicts)));
            }
            // Khoảng ngày overlap nhưng không có ngày cụ thể nào trùng → cho qua
        }

        PayrollDepartment dept = deptResolver.departmentOf(me);

        EmployeeRequest r = repo.save(EmployeeRequest.builder()
                .user(me)
                .userFullName(me.getFullName())
                .department(dept)
                .type(type)
                .fromDate(from).toDate(to)
                .days(days)
                .minutes(minutes)
                .reason(body.getReason().trim())
                .status(EmployeeRequestStatus.PENDING)
                .build());

        // Báo OWNER có đơn mới để panel "Phiếu nghỉ" không phải chờ người ta F5.
        notifyOwners(r);

        log.info("[Request] {} tạo phiếu {} {} ({})",
                me.getFullName(), type, rangeText(from, to), dept);
        return toDto(r);
    }

    /**
     * Dựng danh sách buổi nghỉ từ payload.
     *
     * <p>Chỉ áp dụng cho phiếu NGHỈ PHÉP. Các loại khác (đi trễ, về sớm, quên
     * chấm công, công tác) vẫn theo khoảng ngày như cũ — chúng không tiêu quỹ
     * phép nên chia buổi không có ý nghĩa gì.
     *
     * <p>Ngày KHÔNG tick buổi nào bị loại thẳng khỏi danh sách, chứ không lưu
     * bản ghi rỗng: "có mặt trong phiếu nhưng không nghỉ buổi nào" là mâu thuẫn,
     * và để lại sẽ làm {@code covers()} báo phủ nhầm ngày đi làm bình thường.
     */
    private List<EmployeeRequest.LeaveDay> buildLeaveDays(EmployeeRequestType type,
                                                          CreateRequestDto body,
                                                          LocalDate from, LocalDate to) {
        if (type != EmployeeRequestType.LEAVE) return new ArrayList<>();

        List<EmployeeRequest.LeaveDay> out = new ArrayList<>();

        if (body.getDays() != null && !body.getDays().isEmpty()) {
            Set<LocalDate> seen = new HashSet<>();
            for (LeaveDayDto d : body.getDays()) {
                if (d == null || d.getDate() == null) continue;
                boolean am = Boolean.TRUE.equals(d.getMorning());
                boolean pm = Boolean.TRUE.equals(d.getAfternoon());
                if (!am && !pm) continue;                       // không nghỉ buổi nào
                if (!seen.add(d.getDate()))
                    throw new IllegalArgumentException(
                            "Ngày %s bị khai hai lần trong phiếu.".formatted(d.getDate().format(D)));
                out.add(new EmployeeRequest.LeaveDay(d.getDate(), am, pm));
            }
            if (out.isEmpty())
                throw new IllegalArgumentException("Chưa chọn buổi nghỉ nào.");
            out.sort(Comparator.comparing(EmployeeRequest.LeaveDay::getDate));
            return out;
        }

        // Client cũ: suy ra nghỉ nguyên khoảng, cả ngày.
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(new EmployeeRequest.LeaveDay(d, true, true));
        }
        return out;
    }

    /** Nhân viên tự huỷ đơn của mình khi OWNER chưa xử lý. */
    @Transactional
    public void cancel(User me, Long id) {
        EmployeeRequest r = mustFind(id);
        if (r.getUser() == null || r.getUser().getId() != me.getId())
            throw new IllegalArgumentException("Không thể huỷ phiếu của người khác.");
        if (!r.isPending())
            throw new IllegalArgumentException("Phiếu đã được xử lý, không huỷ được.");
        repo.delete(r);
    }

    /**
     * Cửa sổ ngày cho phép, kiểm ở SERVER dù FE đã khoá lịch.
     * Giới hạn ở FE chỉ là trợ giúp thao tác; ràng buộc thật phải nằm ở đây vì
     * request hoàn toàn có thể được gửi thẳng, không qua giao diện.
     *
     * <p><b>Nghỉ phép</b>: LÙI tối đa về ngày 1 THÁNG HIỆN TẠI; TIẾN không giới
     * hạn. Các loại khác vẫn theo offset như cũ.
     */
    private void validateWindow(EmployeeRequestType type, LocalDate date, LocalDate today) {
        LocalDate min, max;
        if (type.isMonthScoped()) {
            // Nghỉ phép: chặn lùi quá ngày 1 tháng hiện tại; không chặn tiến.
            min = YearMonth.from(today).atDay(1);
            max = null;
        } else {
            min = today.plusDays(type.minOffsetDays());
            max = type.maxOffsetDays() == null ? null : today.plusDays(type.maxOffsetDays());
        }

        if (date.isBefore(min) || (max != null && date.isAfter(max))) {
            String allowed = max == null
                    ? "từ %s trở đi".formatted(min.format(D))
                    : "trong khoảng %s – %s".formatted(min.format(D), max.format(D));
            throw new IllegalArgumentException(
                    "Phiếu %s chỉ được chọn ngày %s.".formatted(type.getLabel().toLowerCase(), allowed));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. OWNER DUYỆT — 3 THAO TÁC
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public EmployeeRequestDto decide(User owner, Long id, DecideRequestDto body) {
        EmployeeRequest r = mustFind(id);
        if (!r.isPending())
            throw new IllegalArgumentException(
                    "Phiếu đã ở trạng thái \"%s\", không xử lý lại được.".formatted(r.getStatus().getLabel()));

        String action = body.getAction() == null ? "" : body.getAction().trim().toUpperCase();

        switch (action) {
            // ── 1. DUYỆT: có phép (đủ công) hoặc không phép (0 công) ──────────
            case "APPROVE" -> {
                if (body.getPaid() == null)
                    throw new IllegalArgumentException("Chưa chọn nghỉ có lương hay không lương.");
                r.setStatus(body.getPaid()
                        ? EmployeeRequestStatus.APPROVED_PAID
                        : EmployeeRequestStatus.APPROVED_UNPAID);
                r.setDeductedDays(null);
                applyLeaveDays(r, body);
            }
            // ── 2. DUYỆT NHƯNG TRỪ CÔNG ───────────────────────────────────────
            case "DEDUCT" -> {
                Double d = body.getDeductedDays();
                if (d == null || d < 0.01 || d > 1.0)
                    throw new IllegalArgumentException("Số công trừ phải nằm trong khoảng 0.01 – 1.");
                r.setStatus(EmployeeRequestStatus.APPROVED_DEDUCTED);
                r.setDeductedDays(round2(d));
            }
            // ── 3. TỪ CHỐI — lý do bắt buộc ───────────────────────────────────
            case "REJECT" -> {
                if (body.getNote() == null || body.getNote().isBlank())
                    throw new IllegalArgumentException("Bắt buộc nhập lý do từ chối.");
                r.setStatus(EmployeeRequestStatus.REJECTED);
                r.setDeductedDays(null);
            }
            default -> throw new IllegalArgumentException(
                    "Thao tác không hợp lệ: \"%s\" (APPROVE | DEDUCT | REJECT).".formatted(body.getAction()));
        }

        r.setDecisionNote(body.getNote() != null ? body.getNote().trim() : null);
        r.setDecidedBy(owner);
        r.setDecidedByName(owner != null ? owner.getFullName() : null);
        r.setDecidedAt(System.currentTimeMillis());
        repo.save(r);

        notifyRequester(r);

        log.info("[Request] Phiếu #{} của {} → {} bởi {}",
                r.getId(), r.getUserFullName(), r.getStatus(),
                owner != null ? owner.getFullName() : "?");
        return toDto(r);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3b. QUỸ NGÀY PHÉP
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * GHI SỐ NGÀY PHÉP khi duyệt phiếu nghỉ phép.
     *
     * <p>Chỉ áp dụng cho {@code type = LEAVE}; các loại khác (đi trễ, công tác…)
     * không đụng tới quỹ phép.
     *
     * <p><b>Người duyệt tự quyết cách chia.</b> Xin nghỉ 3 ngày mà quỹ còn 1 thì
     * duyệt 1 ngày phép + 2 ngày không lương. Không suy ra tự động vì máy không
     * biết trong khoảng đó có bao nhiêu ngày cuối tuần / ngày lễ, và có thoả
     * thuận riêng nào không.
     *
     * <p>Không truyền gì thì mặc định theo nhánh có lương / không lương đã chọn:
     * trọn số ngày của phiếu vào quỹ phép, hoặc trọn vào không lương — giữ thao
     * tác một chạm cho trường hợp thường gặp.
     *
     * <p><b>CÓ CHO PHÉP duyệt vượt quỹ</b>, chỉ ghi log cảnh báo. Chặn cứng sẽ
     * làm kẹt các tình huống thật (nghỉ ốm dài, công ty đồng ý ứng phép năm sau);
     * số dư âm hiện rõ trên màn hình để người duyệt tự chịu trách nhiệm.
     */
    private void applyLeaveDays(EmployeeRequest r, DecideRequestDto body) {
        if (r.getType() != EmployeeRequestType.LEAVE) {
            r.setPaidLeaveDays(null);
            r.setUnpaidLeaveDays(null);
            return;
        }

        double total = r.leaveDays();
        Double paid   = body.getPaidLeaveDays();
        Double unpaid = body.getUnpaidLeaveDays();

        if (paid == null && unpaid == null) {
            if (Boolean.TRUE.equals(body.getPaid())) {
                // ── AUTO-SPLIT: duyệt có lương, tự phân bổ dựa trên quỹ còn lại ──
                LeaveBalanceDto balance = leaveBalance(r.getUser().getId(), r.getFromDate().getYear());
                double remaining = balance.getRemainingDays() != null
                        ? Math.max(0.0, balance.getRemainingDays()) : 0.0;

                if (remaining >= total) {
                    // Quỹ đủ → toàn bộ nghỉ có lương
                    paid   = total;
                    unpaid = 0.0;
                } else {
                    // Quỹ KHÔNG đủ → phần có phép = quỹ còn lại, phần thừa = không lương
                    paid   = round2(remaining);
                    unpaid = round2(total - remaining);
                    log.info("[Leave] {} xin nghỉ {} ngày nhưng quỹ chỉ còn {} → auto-split: {} paid + {} unpaid",
                            r.getUserFullName(), fmt(total), fmt(remaining), fmt(paid), fmt(unpaid));
                }
            } else {
                // Duyệt không lương → toàn bộ là không lương
                paid   = 0.0;
                unpaid = total;
            }
        } else {
            paid   = paid   == null ? 0.0 : Math.max(0.0, paid);
            unpaid = unpaid == null ? 0.0 : Math.max(0.0, unpaid);
        }

        if (paid + unpaid > total + 0.001) {
            throw new IllegalArgumentException(
                    "Tổng ngày phép + không lương (%s) vượt quá số ngày của phiếu (%s)."
                            .formatted(fmt(paid + unpaid), fmt(total)));
        }

        r.setPaidLeaveDays(round2(paid));
        r.setUnpaidLeaveDays(round2(unpaid));

        if (paid > 0) {
            LeaveBalanceDto after = leaveBalance(r.getUser().getId(), r.getFromDate().getYear());
            double newRemaining = after.getRemainingDays() != null ? after.getRemainingDays() : 0.0;
            // Cảnh báo nếu SAU KHI TRỪ vẫn bị âm (trường hợp người duyệt chỉ định trực tiếp)
            if (newRemaining - paid < -0.001) {
                log.warn("[Leave] {} duyệt VƯỢT QUỸ: trừ {} ngày nhưng chỉ còn {} ngày (năm {})",
                        r.getUserFullName(), fmt(paid), fmt(newRemaining),
                        r.getFromDate().getYear());
            }
        }
    }

    /**
     * SỐ DƯ PHÉP của nhân viên trong một năm.
     *
     * <p>Số ngày đã dùng được CỘNG THẲNG từ các phiếu đã duyệt, không có bảng số
     * dư riêng — bảng riêng sẽ lệch với thực tế ngay lần đầu ai đó sửa/huỷ phiếu.
     */
    @Transactional(readOnly = true)
    public LeaveBalanceDto leaveBalance(Long userId, Integer year) {
        int y = (year != null) ? year : LocalDate.now(VN).getYear();

        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên."));

        long endOfPriorYear = LocalDate.of(y - 1, 12, 31).atTime(23, 59, 59)
                .atZone(SeniorityCalculator.ZONE).toInstant().toEpochMilli();
        int seniority = SeniorityCalculator.years(u.getWorkStartDate(), endOfPriorYear);

        // ── ĐỒNG BỘ VỚI "Quản lý phép" (LeaveManagementService.buildRow) ─────
        // Trang "Quản lý phép" là NGUỒN CHÂN LÝ. Trước đây hàm này lệch với
        // "Quản lý phép" vì 2 lý do:
        //   1. QUÊN cộng entitledOffsetDays (offset OWNER nhập tay ở bảng
        //      quản lý phép để chỉnh khởi điểm cho từng nhân viên).
        //   2. VẪN tính seniorityBonusDays trong khi "Quản lý phép" đã tắt
        //      (hardcode 0.0 — chính sách thâm niên đang tạm ngưng, cần bật
        //      lại thì bật đồng thời ở CẢ 2 chỗ).
        // → Kết quả: nhân viên có thâm niên ≥5 năm hoặc có offset thấy số dư
        //   phép ở "Phiếu của tôi" khác với ở "Quản lý phép".
        //
        // Sửa: mirror y hệt công thức của LeaveManagementService.buildRow.

        // Ngày phép cơ bản cộng dồn theo tháng (1 ngày / tháng đủ) + offset tay
        double autoEntitled    = LeaveBalanceCalculator.entitledDaysFor(u.getWorkStartDate(), y);
        double offset          = u.getEntitledOffsetDays() != null
                ? u.getEntitledOffsetDays() : 0.0;
        double currentEntitled = autoEntitled + offset;

        // Thâm niên đang tắt — khớp với LeaveManagementService.buildRow.
        // KHI muốn bật lại, bật đồng thời cả 2 chỗ để không lệch nữa.
        double seniorityBonus  = 0.0;

        // Ngày phép tồn năm trước + ngày phép thưởng thêm (OT, công tác…)
        double prior = u.getPriorYearLeaveBalance() != null ? u.getPriorYearLeaveBalance() : 0.0;
        double bonus = u.getBonusLeaveDays() != null ? u.getBonusLeaveDays() : 0.0;
        double entitled = currentEntitled + seniorityBonus + prior + bonus;

        // ── ĐỒNG BỘ VỚI "Quản lý phép" — usage = 3 nguồn cộng dồn theo tháng ─
        // T1..MANUAL_USAGE_MAX_MONTH: OWNER nhập tay ở ManualLeaveUsage. Số
        //   này ĐÈ luôn phần auto — dù T1..T8 có EmployeeRequest thì cũng
        //   bỏ qua, chỉ dùng manual (khớp LeaveManagementService.buildRow).
        // T(MANUAL_USAGE_MAX_MONTH+1)..T12: tự động từ 2 nguồn:
        //   (a) paidLeaveDays của EmployeeRequest, chia tỷ lệ vào từng tháng
        //       cho phiếu vắt qua nhiều tháng (dùng calcPaidLeaveDaysInMonth
        //       — cùng công thức đã sửa ở LeaveManagementService).
        //   (b) AttendanceEntry.leaveMinutesUsed — phần đi trễ/về sớm đã được
        //       trừ bù vào quỹ phép khi tính lương.
        //
        // Trước đây chỉ đếm effectivePaidLeaveDays (EmployeeRequest.paidLeaveDays)
        // cho CẢ năm, không phân tháng, không đọc manual, không đọc attendance.
        // → nhân viên có bù trễ/sớm ở tháng cũ sẽ thấy quỹ nhiều hơn thực tế.
        List<EmployeeRequest> yearLeaves = repo.findApprovedLeavesOfYear(userId, y);
        long usedMinutes = 0;
        for (int m = 1; m <= 12; m++) {
            if (m <= MANUAL_USAGE_MAX_MONTH) {
                usedMinutes += manualLeaveUsageRepo
                        .findByUserIdAndYearAndMonth(userId, y, m)
                        .map(com.nhatnam.server.entity.ManualLeaveUsage::getTotalMinutes)
                        .orElse(0);
            } else {
                double paidDays = calcPaidLeaveDaysInMonth(yearLeaves, m, y);
                int deductMinutes = attendanceEntryRepo
                        .findByUserAndPeriod(userId, m, y)
                        .map(e -> e.getLeaveMinutesUsed() != null ? e.getLeaveMinutesUsed() : 0)
                        .orElse(0);
                usedMinutes += Math.round(paidDays * LEAVE_MINUTES_PER_DAY) + deductMinutes;
            }
        }
        double used = usedMinutes / (double) LEAVE_MINUTES_PER_DAY;

        return LeaveBalanceDto.builder()
                .userId(u.getId()).fullName(u.getFullName()).year(y)
                .workStartDate(u.getWorkStartDate())
                .seniorityYears(seniority)
                .entitledDays(round2(entitled))
                .usedDays(round2(used))
                .remainingDays(round2(entitled - used))
                .missingWorkStartDate(u.getWorkStartDate() == null)
                .build();
    }

    /**
     * Số ngày phép CÓ LƯƠNG đã nghỉ trong 1 tháng của user.
     *
     * <p>PHẢI KHỚP CÔNG THỨC với {@code LeaveManagementService.calcPaidLeaveDaysInMonth}
     * — không thì "Quản lý phép" (OWNER) và "Phiếu của tôi" (nhân viên) sẽ
     * ra số khác nhau. Về dài hạn nên gom vào 1 helper chung.
     *
     * <p>Với phiếu bị auto-split (paidLeaveDays &lt; tổng ngày trong phiếu),
     * chia tỷ lệ paidDays × (dayValue tháng này / tổng dayValue phiếu) — không
     * cộng nguyên dayValue của mọi buổi (sẽ ra sai như bug cũ).
     */
    private double calcPaidLeaveDaysInMonth(List<EmployeeRequest> leaves, int month, int year) {
        java.time.LocalDate monthStart = java.time.LocalDate.of(year, month, 1);
        java.time.LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        double total = 0;

        for (EmployeeRequest r : leaves) {
            if (r.getFromDate() == null || r.getToDate() == null) continue;
            if (r.getToDate().isBefore(monthStart) || r.getFromDate().isAfter(monthEnd)) continue;

            double paidDays = r.getPaidLeaveDays() != null ? r.getPaidLeaveDays() : 0.0;
            if (paidDays <= 0) continue;

            if (r.getDays() != null && !r.getDays().isEmpty()) {
                // Chia tỷ lệ để tránh trừ quá phần được duyệt CÓ phép.
                double totalDayValues = 0;
                double thisMonthDayValues = 0;
                for (EmployeeRequest.LeaveDay ld : r.getDays()) {
                    if (ld.getDate() == null) continue;
                    double dv = ld.dayValue();
                    totalDayValues += dv;
                    if (!ld.getDate().isBefore(monthStart) && !ld.getDate().isAfter(monthEnd)) {
                        thisMonthDayValues += dv;
                    }
                }
                if (totalDayValues > 0) {
                    total += paidDays * thisMonthDayValues / totalDayValues;
                }
            } else {
                // Phiếu cũ không có LeaveDay list → chia tỷ lệ theo ngày lịch.
                java.time.LocalDate effStart = r.getFromDate().isBefore(monthStart) ? monthStart : r.getFromDate();
                java.time.LocalDate effEnd   = r.getToDate().isAfter(monthEnd) ? monthEnd : r.getToDate();
                long daysInMonth = effStart.until(effEnd).getDays() + 1;
                long totalDays   = r.getFromDate().until(r.getToDate()).getDays() + 1;
                if (totalDays > 0) total += paidDays * daysInMonth / totalDays;
            }
        }
        return total;
    }

    /** LỊCH SỬ nghỉ phép đã duyệt trong năm — dữ liệu cho modal "xem chi tiết". */
    @Transactional(readOnly = true)
    public List<LeaveHistoryItemDto> leaveHistory(Long userId, Integer year) {
        int y = (year != null) ? year : LocalDate.now(VN).getYear();
        return repo.findApprovedLeavesOfYear(userId, y).stream()
                .map(r -> LeaveHistoryItemDto.builder()
                        .id(r.getId())
                        .fromDate(r.getFromDate() != null ? r.getFromDate().toString() : null)
                        .toDate(r.getToDate() != null ? r.getToDate().toString() : null)
                        .calendarDays(r.calendarDays())
                        .paidLeaveDays(r.getPaidLeaveDays())
                        .unpaidLeaveDays(r.getUnpaidLeaveDays())
                        .status(r.getStatus() != null ? r.getStatus().name() : null)
                        .statusLabel(r.getStatus() != null ? r.getStatus().getLabel() : null)
                        .reason(r.getReason())
                        .decisionNote(r.getDecisionNote())
                        .decidedByName(r.getDecidedByName())
                        .decidedAt(r.getDecidedAt())
                        .build())
                .toList();
    }

    /**
     * CÁC NGÀY ĐÃ BỊ CHIẾM bởi phiếu nghỉ phép (PENDING hoặc đã duyệt) của
     * một nhân viên trong khoảng ngày.
     *
     * <p>FE dùng dữ liệu này để disable ngày đã nghỉ/đang xin, tránh nhân viên
     * xin nghỉ trùng → bị trừ phép hai lần cho cùng một ngày.
     */
    @Transactional(readOnly = true)
    public List<OccupiedDateDto> occupiedDates(Long userId, LocalDate from, LocalDate to) {
        List<EmployeeRequest> requests = repo.findOverlapping(
                userId, EmployeeRequestType.LEAVE, from, to);

        List<OccupiedDateDto> out = new ArrayList<>();
        for (EmployeeRequest r : requests) {
            if (r.hasDays()) {
                for (EmployeeRequest.LeaveDay d : r.getDays()) {
                    if (!d.getDate().isBefore(from) && !d.getDate().isAfter(to) && !d.isEmpty()) {
                        out.add(OccupiedDateDto.builder()
                                .date(d.getDate())
                                .morning(d.isMorning())
                                .afternoon(d.isAfternoon())
                                .status(r.getStatus().name())
                                .statusLabel(r.getStatus().getLabel())
                                .requestId(r.getId())
                                .build());
                    }
                }
            } else {
                // Phiếu cũ không có danh sách buổi → trải toàn khoảng, cả ngày
                for (LocalDate d = r.getFromDate().isBefore(from) ? from : r.getFromDate();
                     !d.isAfter(r.getToDate()) && !d.isAfter(to);
                     d = d.plusDays(1)) {
                    out.add(OccupiedDateDto.builder()
                            .date(d)
                            .morning(true).afternoon(true)
                            .status(r.getStatus().name())
                            .statusLabel(r.getStatus().getLabel())
                            .requestId(r.getId())
                            .build());
                }
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. THÔNG BÁO WEBSOCKET
    // ══════════════════════════════════════════════════════════════════════════

    private void notifyOwners(EmployeeRequest r) {
        String msg = "%s vừa gửi %s cho %s."
                .formatted(r.getUserFullName(), r.getType().getLabel().toLowerCase(),
                        periodText(r));
        try {
            notificationService.sendToRole("OWNER", "EMPLOYEE_REQUEST_CREATED", msg, payload(r));
        } catch (Exception e) {
            log.warn("[Request] Không gửi được thông báo tới OWNER: {}", e.getMessage());
        }
    }

    /**
     * Báo cho người tạo đơn biết kết quả.
     *
     * <p>Nội dung nêu ĐỦ ba thứ nhân viên cần: đơn nào, kết quả ra sao, và hệ quả
     * lên công. Chỉ nói "đã được duyệt" thì người nhận vẫn phải mở app ra xem
     * mình có bị trừ công hay không.
     */
    private void notifyRequester(EmployeeRequest r) {
        String what = "%s cho %s".formatted(r.getType().getLabel(), periodText(r));
        String msg = switch (r.getStatus()) {
            case APPROVED_PAID -> "%s đã được duyệt — nghỉ có lương, vẫn hưởng đủ công ngày đó.".formatted(what);
            case APPROVED_UNPAID -> "%s đã được duyệt — nghỉ không lương, công ngày đó tính 0.".formatted(what);
            case APPROVED_DEDUCTED -> "%s đã được duyệt nhưng bị trừ %s công.".formatted(what, fmt(r.getDeductedDays()));
            case REJECTED -> "%s bị từ chối. Lý do: %s".formatted(what,
                    r.getDecisionNote() != null ? r.getDecisionNote() : "(không ghi)");
            default -> "%s đã được cập nhật trạng thái.".formatted(what);
        };
        if (r.getStatus() != EmployeeRequestStatus.REJECTED
                && r.getDecisionNote() != null && !r.getDecisionNote().isBlank()) {
            msg += " Ghi chú: " + r.getDecisionNote();
        }

        try {
            // Gửi theo ĐÚNG role nhận lương của nhân viên — đó là role họ đang
            // dùng khi vào trang phiếu của mình, nên chuông sẽ nổ đúng chỗ.
            var payrollRole = deptResolver.payrollRoleOf(r.getUser());
            String activeRole = payrollRole != null ? payrollRole.name()
                    : (r.getUser().getRole() != null ? r.getUser().getRole().name() : "USER");

            notificationService.sendToUser(r.getUser(), activeRole,
                    "EMPLOYEE_REQUEST_DECIDED", msg, payload(r));
        } catch (Exception e) {
            log.warn("[Request] Không gửi được thông báo tới nhân viên {}: {}",
                    r.getUserFullName(), e.getMessage());
        }
    }

    private String payload(EmployeeRequest r) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("requestId", r.getId());
            p.put("type", r.getType().name());
            p.put("typeLabel", r.getType().getLabel());
            p.put("status", r.getStatus().name());
            p.put("statusLabel", r.getStatus().getLabel());
            p.put("fromDate", r.getFromDate().toString());
            p.put("toDate", r.getToDate().toString());
            p.put("periodText", periodText(r));
            if (r.getDeductedDays() != null) p.put("deductedDays", r.getDeductedDays());
            if (r.getDecisionNote() != null) p.put("note", r.getDecisionNote());
            return MAPPER.writeValueAsString(p);
        } catch (Exception e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. TRUY VẤN
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<EmployeeRequestDto> searchForOwner(PayrollDepartment dept,
                                                   EmployeeRequestStatus status,
                                                   LocalDate from, LocalDate to,
                                                   int page, int size) {
        return searchForOwner(dept, status, null, null, from, to, page, size);
    }

    /** Phase 6b overload: thêm {@code typeFilter} để tách tab WFH khỏi LEAVE. */
    @Transactional(readOnly = true)
    public Page<EmployeeRequestDto> searchForOwner(PayrollDepartment dept,
                                                   EmployeeRequestStatus status,
                                                   Long userId,
                                                   LocalDate from, LocalDate to,
                                                   int page, int size) {
        return searchForOwner(dept, status, userId, null, from, to, page, size);
    }

    /**
     * Phase 6b core overload — thêm filter {@code type}:
     * <ul>
     *   <li>null → trả toàn bộ (hành vi cũ, tương thích ngược).</li>
     *   <li>{@link EmployeeRequestType#LEAVE} → chỉ phiếu nghỉ.</li>
     *   <li>{@link EmployeeRequestType#WORK_FROM_HOME} → chỉ phiếu WFH.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Page<EmployeeRequestDto> searchForOwner(PayrollDepartment dept,
                                                   EmployeeRequestStatus status,
                                                   Long userId,
                                                   EmployeeRequestType type,
                                                   LocalDate from, LocalDate to,
                                                   int page, int size) {
        Pageable p = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        return repo.search(dept, status, userId, type, from, to, p).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<EmployeeRequestDto> myRequests(User me, int page, int size) {
        Pageable p = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        return repo.findByUser_IdOrderByCreatedAtDesc(me.getId(), p).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public EmployeeRequestDto detail(Long id) { return toDto(mustFind(id)); }

    @Transactional(readOnly = true)
    public RequestSummaryDto summary(PayrollDepartment dept) {
        return RequestSummaryDto.builder()
                .pending(dept == null
                        ? repo.countByStatus(EmployeeRequestStatus.PENDING)
                        : repo.countByDepartmentAndStatus(dept, EmployeeRequestStatus.PENDING))
                .total(repo.count())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. QUY ĐỔI RA TÁC ĐỘNG LÊN BẢNG CHẤM CÔNG
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * TÁC ĐỘNG của các đơn đã duyệt lên MỘT NGÀY của MỘT NHÂN VIÊN.
     *
     * <p>Gom nhiều đơn cùng ngày lại thành một kết quả duy nhất để công thức tính
     * công không phải xử lý danh sách. Một ngày hoàn toàn có thể vừa nằm trong đơn
     * nghỉ nửa buổi, vừa có đơn xin đi trễ cho buổi còn lại.
     *
     * @param fullDayCredit  nghỉ / công tác cả ngày, có lương → tính đủ 1 công
     * @param zeroDay        nghỉ không phép → công ngày đó = 0
     * @param mealEligible   ngày đó có được phụ cấp cơm không
     * @param shiftStart     giờ vào ca đã dời (đi trễ được duyệt), null nếu không đổi
     * @param shiftEnd       giờ tan ca đã dời (về sớm được duyệt), null nếu không đổi
     * @param excusedFrom    đầu khoảng giờ được miễn có mặt (nghỉ ít hơn 1 ngày)
     * @param excusedTo      cuối khoảng giờ được miễn có mặt
     * @param deduction      tổng số công bị trừ theo quyết định của OWNER (0–1)
     * @param minCredit      MỨC SÀN CÔNG cho ngày MIX (paid + unpaid): 0.5 khi nửa
     *                       buổi ăn quỹ + nửa buổi không lương, không đi làm.
     *                       {@code FactoryPayrollService.applyEffect} lan sang
     *                       {@code DayPlan.minCredit} — đã có sẵn cơ chế này cho
     *                       nghỉ nửa buổi bên ngoài.
     * @param labels         mô tả để hiển thị trên lịch chi tiết ngày công
     */
    public record DayEffect(boolean fullDayCredit,
                            boolean zeroDay,
                            boolean mealEligible,
                            LocalTime shiftStart,
                            LocalTime shiftEnd,
                            LocalTime excusedFrom,
                            LocalTime excusedTo,
                            double deduction,
                            double minCredit,
                            List<String> labels,
                            /**
                             * PHASE 6b — TRUE nếu ngày này có đơn WORK_FROM_HOME đã duyệt.
                             * Dùng để UI tô màu khác với LEAVE và để UI công chi tiết
                             * hiển thị badge "Làm ở nhà".
                             */
                            boolean wfh) {

        public static DayEffect none() {
            return new DayEffect(false, false, true, null, null, null, null, 0.0, 0.0, List.of(), false);
        }

        public boolean isEmpty() {
            return !fullDayCredit && !zeroDay && shiftStart == null && shiftEnd == null
                    && excusedFrom == null && deduction == 0.0 && minCredit == 0.0;
        }

        public String label() { return labels.isEmpty() ? null : String.join(" · ", labels); }
    }

    /**
     * Bảng tra cứu tác động cho CẢ KỲ LƯƠNG của một bộ phận:
     * {@code userId → (ngày trong tháng → tác động)}.
     *
     * <p>Nạp một lần rồi tra trong vòng lặp thay vì query theo từng ngày × từng
     * nhân viên — bảng chấm công 40 người × 31 ngày sẽ là 1240 lượt query nếu làm
     * ngược lại.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<Integer, DayEffect>> effectsForPeriod(int month, int year,
                                                               PayrollDepartment dept) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<EmployeeRequest> all = repo.findEffectiveForPeriod(dept, start, end, EFFECTIVE);
        return groupByUserAndDay(all, ym);
    }

    /** Bảng tra cứu cho MỘT nhân viên — dùng khi dựng lại phiếu lương cá nhân. */
    @Transactional(readOnly = true)
    public Map<Integer, DayEffect> effectsForUser(Long userId, int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        List<EmployeeRequest> all = repo.findEffectiveForUserPeriod(
                userId, ym.atDay(1), ym.atEndOfMonth(), EFFECTIVE);
        return groupByUserAndDay(all, ym).getOrDefault(userId, Map.of());
    }

    private Map<Long, Map<Integer, DayEffect>> groupByUserAndDay(List<EmployeeRequest> all, YearMonth ym) {
        // Bước 1 — trải đơn ra từng ngày. Một đơn 3/6–5/6 sinh ra 3 mục.
        Map<Long, Map<Integer, List<EmployeeRequest>>> spread = new HashMap<>();
        for (EmployeeRequest r : all) {
            if (r.getUser() == null) continue;
            LocalDate d = r.getFromDate().isBefore(ym.atDay(1)) ? ym.atDay(1) : r.getFromDate();
            LocalDate last = r.getToDate().isAfter(ym.atEndOfMonth()) ? ym.atEndOfMonth() : r.getToDate();
            for (; !d.isAfter(last); d = d.plusDays(1)) {
                // Phiếu ngắt quãng: ngày nằm giữa from–to nhưng KHÔNG được chọn
                // thì vẫn là ngày đi làm bình thường, không được hưởng gì.
                if (!r.covers(d)) continue;
                spread.computeIfAbsent(r.getUser().getId(), k -> new HashMap<>())
                        .computeIfAbsent(d.getDayOfMonth(), k -> new ArrayList<>())
                        .add(r);
            }
        }

        // Bước 2 — gộp các đơn của cùng một ngày thành một tác động.
        Map<Long, Map<Integer, DayEffect>> out = new HashMap<>();
        spread.forEach((userId, byDay) -> {
            Map<Integer, DayEffect> m = new HashMap<>();
            byDay.forEach((day, list) -> m.put(day, merge(list, ym.atDay(day))));
            out.put(userId, m);
        });
        return out;
    }

    /**
     * Gộp nhiều đơn của CÙNG MỘT NGÀY.
     *
     * <p>Thứ tự ưu tiên khi mâu thuẫn: <b>không phép thắng có phép</b>. Nếu một
     * ngày vừa có đơn nghỉ không lương vừa có đơn khác được duyệt có lương thì
     * ngày đó vẫn về 0 công — hướng bảo thủ này tránh việc chồng đơn trở thành
     * cách lách để được trả công cho ngày đã bị quyết là không phép.
     */
    private DayEffect merge(List<EmployeeRequest> list, LocalDate date) {
        boolean fullCredit = false, zero = false;
        LocalTime shiftStart = null, shiftEnd = null, exFrom = null, exTo = null;
        double deduction = 0.0;
        double minCredit = 0.0;
        List<String> labels = new ArrayList<>();
        // PHASE 6b: TRUE nếu có bất kỳ đơn WORK_FROM_HOME nào duyệt cho ngày này.
        boolean wfh = false;

        for (EmployeeRequest r : list) {
            labels.add("%s (%s)".formatted(r.getType().getLabel(), r.getStatus().getLabel()));
            deduction += r.effectiveDeduction();

            boolean paid = r.getStatus().isPaid() || r.getType().alwaysFullCredit();

            switch (r.getType()) {
                // ─ PHASE 6b: Làm ở nhà — luôn 1 công đầy đủ, không trừ quỹ phép ─
                case WORK_FROM_HOME -> {
                    if (paid) {
                        fullCredit = true;
                        wfh = true;    // UI dùng để tô màu + hiển thị badge "WFH"
                    } else {
                        zero = true;   // Bị từ chối duyệt → không công
                    }
                }
                // Nghỉ / công tác / quên chấm công: ảnh hưởng CẢ NGÀY,
                // trừ khi là nghỉ theo khung giờ.MISSING_PUNCH
                case LEAVE, BUSINESS_TRIP -> {
                    // Buổi nghỉ CỦA ĐÚNG NGÀY ĐANG XÉT — cùng một phiếu có thể
                    // nghỉ cả ngày 24 nhưng chỉ nghỉ sáng ngày 27.
                    var sess = r.dayAt(date);
                    boolean halfThisDay = (sess != null && !sess.isFullDay()) || r.isHalfDay();

                    // ── MIX: phiếu NGHỈ PHÉP có cả paid & unpaid ────────────
                    // Chỉ có ý nghĩa khi APPROVED_UNPAID (paid=false) — nếu
                    // APPROVED_PAID thì OWNER đã chọn hưởng đủ công cho toàn
                    // phiếu, dùng nhánh cũ (fullCredit) là đủ.
                    boolean handledAsMix = false;
                    if (r.getType() == com.nhatnam.server.enumtype.EmployeeRequestType.LEAVE
                            && !paid) {
                        Map<LocalDate, EmployeeRequest.SessionPay> spm = r.sessionPayMap();
                        EmployeeRequest.SessionPay info = spm.get(date);
                        if (info != null && sess != null) {
                            boolean mP = info.morningPaid();
                            boolean aP = info.afternoonPaid();

                            if (halfThisDay) {
                                // Ngày nghỉ NỬA BUỔI:
                                //   Buổi nghỉ đó paid → nửa quỹ + nửa đi làm = 1 công.
                                //   Buổi nghỉ đó unpaid → 0.5 công theo chấm công.
                                boolean thisSessionPaid = sess.isMorning() ? mP : aP;
                                if (thisSessionPaid) fullCredit = true;
                                else deduction += 0.5;
                            } else {
                                // Ngày nghỉ FULL:
                                //   2 buổi paid → 1 công.
                                //   1 paid + 1 unpaid → 0.5 công (mức sàn).
                                //   0 buổi paid → 0 công.
                                int paidSessions = (mP ? 1 : 0) + (aP ? 1 : 0);
                                switch (paidSessions) {
                                    case 2 -> fullCredit = true;
                                    case 1 -> minCredit = Math.max(minCredit, 0.5);
                                    default -> zero = true;
                                }
                            }
                            handledAsMix = true;
                        }
                    }

                    if (!handledAsMix) {
                        if (halfThisDay) {
                            // NGHỈ NỬA NGÀY:
                            //   · có phép  → nửa ngày làm + nửa ngày phép = vẫn đủ công,
                            //                phần trừ nằm ở QUỸ PHÉP (0,5 ngày) chứ không ở công.
                            //   · không phép → chỉ được nửa công.
                            if (paid) fullCredit = true;
                            else deduction += 0.5;
                        } else if (r.isPartialDay()) {
                            // Dữ liệu CŨ còn khung giờ — giữ nguyên cách tính để phiếu
                            // đã duyệt trước đây không đổi kết quả lương.
                            exFrom = min(exFrom, r.getFromTime());
                            exTo = max(exTo, r.getToTime());
                        } else if (paid) {
                            fullCredit = true;
                        } else {
                            zero = true;
                        }
                    }
                }
                // Đi trễ được duyệt → dời giờ VÀO ca, ngày đó không tính là trễ.
//                case LATE_ARRIVAL -> {
//                    if (paid && r.getMinutes() != null) {
//                        shiftStart = max(shiftStart, SHIFT_START.plusMinutes(r.getMinutes()));
//                    } else if (!paid) {
//                        zero = true;
//                    }
//                }
                // Về sớm được duyệt → kéo giờ TAN ca về sớm tương ứng.
//                case EARLY_LEAVE -> {
//                    if (paid && r.getMinutes() != null) {
//                        shiftEnd = min(shiftEnd, SHIFT_END.minusMinutes(r.getMinutes()));
//                    } else if (!paid) {
//                        zero = true;
//                    }
//                }
            }
        }

        if (zero) {
            fullCredit = false;
            wfh = false;   // Nếu bị override thành 0 công thì không coi là WFH thành công
        }

        return new DayEffect(fullCredit, zero,
                /* mealEligible */ !zero,
                shiftStart, shiftEnd, exFrom, exTo,
                Math.min(1.0, round2(deduction)),
                Math.min(1.0, minCredit),
                labels,
                wfh);
    }

    /** Ca chuẩn — giữ trùng khớp với {@code FactoryPayrollService}. */
    private static final LocalTime SHIFT_START = LocalTime.of(8, 0);
    private static final LocalTime SHIFT_END = LocalTime.of(17, 0);

    // ══════════════════════════════════════════════════════════════════════════
    // TIỆN ÍCH
    // ══════════════════════════════════════════════════════════════════════════

    private EmployeeRequest mustFind(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu: " + id));
    }

    public EmployeeRequestDto toDto(EmployeeRequest r) {
        PayrollDepartment d = r.getDepartment();
        return EmployeeRequestDto.builder()
                .id(r.getId())
                .userId(r.getUser() != null ? r.getUser().getId() : null)
                .userFullName(r.getUserFullName())
                .roleLabel(r.getUser() != null ? deptResolver.roleLabelOf(r.getUser()) : null)
                .department(d != null ? d.name() : null)
                .departmentLabel(d != null ? d.getLabel() : null)
                .type(r.getType().name())
                .typeLabel(r.getType().getLabel())
                .fromDate(r.getFromDate()).toDate(r.getToDate())
                .fromTime(r.getFromTime()).toTime(r.getToTime())
                .halfDay(r.isHalfDay())
                .days(r.getDays() == null ? List.of() : r.getDays().stream()
                        .map(dl -> LeaveDayDto.builder()
                                .date(dl.getDate())
                                .morning(dl.isMorning())
                                .afternoon(dl.isAfternoon())
                                .build())
                        .toList())
                .leaveDays(r.leaveDays())
                .minutes(r.getMinutes())
                .periodText(periodText(r))
                .totalDays(daysBetween(r))
                .reason(r.getReason())
                .status(r.getStatus().name())
                .statusLabel(r.getStatus().getLabel())
                .deductedDays(r.getDeductedDays())
                .paidLeaveDays(r.getPaidLeaveDays())
                .unpaidLeaveDays(r.getUnpaidLeaveDays())
                .decisionNote(r.getDecisionNote())
                .decidedByName(r.getDecidedByName())
                .decidedAt(r.getDecidedAt())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .actionable(r.isPending())
                .build();
    }

    /**
     * Câu mô tả thời gian xin phép, tuỳ loại đơn:
     * <pre>
     *   Nghỉ phép nhiều ngày  → "3/6/2026 → 4/6/2026 (2 ngày)"
     *   Nghỉ theo giờ         → "3/6/2026, 13:00–16:00"
     *   Đi trễ / Về sớm       → "3/6/2026 · 30 phút"
     *   Quên chấm công        → "3/6/2026"
     * </pre>
     */
    public String periodText(EmployeeRequest r) {
        if (r.hasDays()) return daysText(r);
        if (r.isHalfDay())
            return "%s (nửa ngày)".formatted(rangeText(r.getFromDate(), r.getToDate()));
        if (r.isPartialDay())
            return "%s, %s–%s".formatted(r.getFromDate().format(D),
                    r.getFromTime().format(HHMM), r.getToTime().format(HHMM));

        if (r.getType().isMinutesBased())
            return "%s · %d phút".formatted(r.getFromDate().format(D), r.getMinutes());

        return rangeText(r.getFromDate(), r.getToDate());
    }

    private static String rangeText(LocalDate from, LocalDate to) {
        if (from.equals(to)) return from.format(D);
        long n = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        return "%s → %s (%d ngày)".formatted(from.format(D), to.format(D), n);
    }

    /**
     * Mô tả các buổi nghỉ cho người đọc.
     * <pre>
     *   24/7 → 26/7, 27/7 (sáng)            — liên tục rồi nửa ngày
     *   24/7, 25/7, 27/7 (chiều), 29/7      — ngắt quãng
     * </pre>
     */
    private static String daysText(EmployeeRequest r) {
        List<String> parts = new ArrayList<>();
        for (EmployeeRequest.LeaveDay d : r.getDays()) {
            String suffix = d.isFullDay() ? "" : (d.isMorning() ? " (sáng)" : " (chiều)");
            parts.add(d.getDate().format(D) + suffix);
        }
        return "%s — %s ngày".formatted(String.join(", ", parts), fmt(r.leaveDays()));
    }

    private static Double daysBetween(EmployeeRequest r) {
        if (r.hasDays()) return r.leaveDays();
        if (r.isHalfDay()) return 0.5;
        if (r.isPartialDay()) {
            long mins = java.time.Duration.between(r.getFromTime(), r.getToTime()).toMinutes();
            return round2(mins / 480.0);   // 480 phút = 1 công
        }
        return (double) (java.time.temporal.ChronoUnit.DAYS.between(r.getFromDate(), r.getToDate()) + 1);
    }

    private static LocalTime min(LocalTime a, LocalTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static String fmt(Double d) {
        if (d == null) return "0";
        return d == Math.floor(d) ? String.valueOf(d.intValue()) : String.valueOf(round2(d));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}