package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.expense.CreateExpenseVoucherRequest;
import com.nhatnam.server.dto.expense.ExpenseApprovalConfigDto;
import com.nhatnam.server.dto.expense.ExpenseVoucherDto;
import com.nhatnam.server.dto.expense.UpdateExpenseVoucherRequest;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.ExpenseApprovalConfigRepository;
import com.nhatnam.server.repository.ExpenseVoucherRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.ExpenseVoucherService;
import com.nhatnam.server.service.NotificationService;
import com.nhatnam.server.service.VendorDebtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpenseVoucherServiceImpl implements ExpenseVoucherService {

    private final ExpenseVoucherRepository voucherRepo;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final VendorDebtService vendorDebtService;
    private final ExpenseApprovalConfigRepository approvalConfigRepo;
    private final com.nhatnam.server.repository.VendorExpenseCategoryRepository categoryRepo;
    private final com.nhatnam.server.repository.MaterialVendorRepository materialVendorRepo;
    private final com.nhatnam.server.repository.ExpenseVoucherLogRepository voucherLogRepo;
    private final com.nhatnam.server.repository.OrderRepository orderRepository;

    /**
     * Tham chiếu CHÍNH bean này qua proxy — dùng khi nhập Excel để mỗi lần gọi
     * {@link #create} chạy trong MỘT giao dịch RIÊNG (lỗi phiếu này không kéo đổ phiếu khác).
     * Gọi trực tiếp {@code this.create(...)} sẽ bỏ qua proxy @Transactional nên không dùng.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ExpenseVoucherService self;

    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("3000000");

    /** Số id tối đa cho mỗi câu UPDATE khi tính lại cấp duyệt hàng loạt. */
    private static final int SCOPE_UPDATE_BATCH = 500;

    // ── Phòng ban ────────────────────────────────────────────────────────────
    private static final Set<String> DEPT_ACCOUNTING = Set.of("ACCOUNTANT", "SUPER_ACCOUNTANT");
    private static final Set<String> DEPT_WAREHOUSE   = Set.of("WAREHOUSE", "SUPER_WAREHOUSE");
    private static final Set<String> DEPT_FACTORY     = Set.of("FACTORY_WORKER", "SUPER_FACTORY_WORKER");

    /** Tập role được xem theo phòng ban của người gọi. null = xem tất cả. */
    private Set<String> deptRolesFor(String callerRole) {
        if (callerRole == null) return null;
        String r = callerRole.toUpperCase();
        if (r.equals("ADMIN") || r.equals("OWNER") || r.equals("SUPERADMIN")) return null;
        if (DEPT_ACCOUNTING.contains(r)) return DEPT_ACCOUNTING;
        if (DEPT_WAREHOUSE.contains(r))   return DEPT_WAREHOUSE;
        if (DEPT_FACTORY.contains(r))     return DEPT_FACTORY;
        return null;
    }

    /** role snapshot của phiếu (phiếu cũ null ⇒ coi như ACCOUNTANT vì tính năng vốn của kế toán). */
    private String voucherDeptRole(ExpenseVoucherDto v) {
        return v.getCreatedByRole() == null || v.getCreatedByRole().isBlank()
                ? "ACCOUNTANT" : v.getCreatedByRole().toUpperCase();
    }

    @Override
    @Transactional
    public ExpenseVoucherDto createOverpayRefund(Long createdByUserId, String orderCode) {
        return createOverpayRefund(createdByUserId, orderCode, null, null, null, null);
    }

    @Override
    @Transactional
    public ExpenseVoucherDto createOverpayRefund(Long createdByUserId, String orderCode,
                                                 String paymentType, String customerBankName,
                                                 String customerBankAccount, String customerBankHolder) {
        if (orderCode == null || orderCode.isBlank())
            throw new BusinessException("Thiếu mã đơn hàng");

        com.nhatnam.server.entity.Order order = orderRepository.findByOrderCode(orderCode.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng " + orderCode));

        BigDecimal overpay = order.getOverpaidAmount();
        if (overpay == null || overpay.compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("Đơn " + order.getOrderCode() + " không có phần dư cần hoàn.");

        if (order.getOverpaidRefundVoucherCode() != null)
            throw new BusinessException("Đơn " + order.getOrderCode()
                    + " đã có phiếu chi hoàn phần dư (" + order.getOverpaidRefundVoucherCode() + ").");

        BigDecimal refundAmount = overpay.setScale(0, java.math.RoundingMode.HALF_UP);

        String customerName = order.getCustomerName();
        if ((customerName == null || customerName.isBlank()) && order.getCustomer() != null) {
            String comp = order.getCustomer().getCompanyName();
            customerName = (comp != null && !comp.isBlank()) ? comp : order.getCustomer().getName();
        }
        if (customerName == null || customerName.isBlank()) customerName = "Khách hàng";

        String reason = "Thanh toán phần dư của đơn hàng " + order.getOrderCode()
                + " do khách thanh toán dư.";

        // Xác định phương thức thanh toán
        boolean isBankTransfer = "BANK_TRANSFER".equalsIgnoreCase(paymentType);
        // Thông tin STK khách hàng là TUỲ CHỌN khi chuyển khoản — kế toán có thể
        // bổ sung sau hoặc xử lý ngoài hệ thống.

        // Không gắn NCC (vendorId = null) ⇒ không cần chọn nhãn khoản chi. Đi qua
        // duyệt bình thường theo role người tạo (owner/admin tự duyệt; kế toán chờ duyệt).
        CreateExpenseVoucherRequest req = new CreateExpenseVoucherRequest();
        req.setVendorName(customerName);
        req.setVendorId(null);
        req.setReason(reason);
        req.setPaymentType(isBankTransfer ? "BANK_TRANSFER" : "CASH");
        var item = new CreateExpenseVoucherRequest.ExpenseItemRequest();
        item.setItemName(reason);
        item.setAmount(refundAmount);
        req.setItems(List.of(item));

        ExpenseVoucherDto dto = create(createdByUserId, req);

        // Ghi thông tin tài khoản khách hàng vào phiếu chi (nếu chuyển khoản)
        if (isBankTransfer) {
            ExpenseVoucher saved = voucherRepo.findById(dto.getId()).orElse(null);
            if (saved != null) {
                if (customerBankName != null && !customerBankName.isBlank()) {
                    saved.setCustomerBankName(customerBankName.trim());
                    dto.setCustomerBankName(saved.getCustomerBankName());
                }
                if (customerBankAccount != null && !customerBankAccount.isBlank()) {
                    saved.setCustomerBankAccount(customerBankAccount.trim());
                    dto.setCustomerBankAccount(saved.getCustomerBankAccount());
                }
                if (customerBankHolder != null && !customerBankHolder.isBlank()) {
                    saved.setCustomerBankHolder(customerBankHolder.trim());
                    dto.setCustomerBankHolder(saved.getCustomerBankHolder());
                }
                voucherRepo.save(saved);
            }
        }

        // Ghi mã phiếu chi vào đơn để chặn lập trùng.
        order.setOverpaidRefundVoucherCode(dto.getVoucherCode());
        orderRepository.save(order);

        return dto;
    }

    @Override
    @Transactional
    public ExpenseVoucherDto updateVoucher(Long id, Long editorUserId, UpdateExpenseVoucherRequest req) {
        ExpenseVoucher v = findOrThrow(id);

        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        Set<Role> roles = editor.getAllRoles();
        boolean isOwnerAdmin = roles.contains(Role.OWNER) || roles.contains(Role.ADMIN)
                || roles.contains(Role.SUPERADMIN);
        boolean isAccountant = roles.contains(Role.ACCOUNTANT) || roles.contains(Role.SUPER_ACCOUNTANT);

        // ── 1. Kiểm tra quyền theo trạng thái ────────────────────────────────
        ExpenseVoucher.VoucherStatus status = v.getStatus();
        if (status == ExpenseVoucher.VoucherStatus.REJECTED) {
            throw new BusinessException("Phiếu chi đã bị từ chối, không thể sửa");
        }
        if (!isOwnerAdmin && !isAccountant) {
            throw new BusinessException("Bạn không có quyền sửa phiếu chi này");
        }
        if (!isOwnerAdmin && status != ExpenseVoucher.VoucherStatus.PENDING) {
            throw new BusinessException("Phiếu đã duyệt — chỉ Owner/Admin mới được sửa");
        }

        // ── 2. Validate và chuẩn bị dữ liệu ──────────────────────────────────

        // Lưu giá trị cũ để ghi log
        String oldReason = v.getReason();
        String oldVendorName = v.getVendorName();
        Long oldVendorId = v.getVendorId();
        Long oldExpenseDate = v.getExpenseDate();
        String oldExpensePeriod = v.getExpensePeriod();
        String oldPaymentType = v.getPaymentType() != null ? v.getPaymentType().name() : "CASH";
        BigDecimal oldTotal = v.getItems().stream()
                .map(ExpenseItem::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ExpenseVoucher.ApproverScope oldScope = v.getApproverScope();

        // ── Cập nhật nhà cung cấp ──────────────────────────────────────────────
        String newVendorName = req.getVendorName() != null ? req.getVendorName().trim() : null;
        Long newVendorId = req.getVendorId();

        // Nếu có vendorId thì tìm và lấy tên từ database
        if (newVendorId != null) {
            // Tìm nhà cung cấp theo ID
            var vendor = materialVendorRepo.findById(newVendorId).orElse(null);
            if (vendor != null) {
                v.setVendorId(newVendorId);
                v.setVendorName(vendor.getName());
                // Cập nhật vendorType nếu có
                if (vendor.getVendorType() != null) {
                    v.setVendorType(vendor.getVendorType().name());
                }
            } else {
                // Nếu không tìm thấy vendor, vẫn dùng tên được gửi lên
                v.setVendorId(null);
                v.setVendorName(newVendorName);
            }
        } else if (newVendorName != null && !newVendorName.isBlank()) {
            // Nếu chỉ có tên (trường hợp nhập thủ công)
            v.setVendorId(null);
            v.setVendorName(newVendorName);
        } else {
            // Không có nhà cung cấp
            v.setVendorId(null);
            v.setVendorName(null);
        }

        // Lý do
        String reason = req.getReason() != null ? req.getReason().trim() : "";
        if (reason.isBlank()) {
            throw new BusinessException("Lý do chi không được để trống");
        }
        v.setReason(reason);

        // Ngày chi / Kỳ chi
        Long newExpenseDate = req.getExpenseDate();
        String newExpensePeriod = req.getExpensePeriod();

// Không cho cả 2 null
        if (newExpenseDate == null && (newExpensePeriod == null || newExpensePeriod.isBlank())) {
            throw new BusinessException("Phải chọn ngày chi hoặc kỳ chi");
        }
// Không cho cả 2 có giá trị
        if (newExpenseDate != null && newExpensePeriod != null && !newExpensePeriod.isBlank()) {
            throw new BusinessException("Chỉ chọn 1 trong 2: ngày chi hoặc kỳ chi");
        }

        if (newExpenseDate != null) {
            // Chế độ NGÀY CỤ THỂ: chỉ ghi expense_date, expense_period = null
            v.setExpenseDate(newExpenseDate);
            v.setExpensePeriod(null);
        } else {
            // Chế độ KỲ: ghi cả expense_date (ngày đầu tháng) và expense_period
            String period = normalizePeriod(newExpensePeriod);
            if (period == null) {
                throw new BusinessException("Kỳ chi không hợp lệ. Định dạng yyyy-MM");
            }
            v.setExpensePeriod(period);
            // Chuyển kỳ thành ngày đầu tháng
            String[] parts = period.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            long firstDayOfMonth = java.time.LocalDate.of(year, month, 1)
                    .atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            v.setExpenseDate(firstDayOfMonth);
        }

        // Phương thức thanh toán
        String newPaymentType = req.getPaymentType() != null ? req.getPaymentType().trim().toUpperCase() : "CASH";

        ExpenseVoucher.PaymentType pt;
        try {
            pt = ExpenseVoucher.PaymentType.valueOf(newPaymentType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Phương thức thanh toán không hợp lệ");
        }
        v.setPaymentType(pt);

        if (pt == ExpenseVoucher.PaymentType.BANK_TRANSFER) {
            String bankName = req.getBankName() != null ? req.getBankName().trim() : "";
            String bankRef = req.getBankRef() != null ? req.getBankRef().trim() : "";
            if (bankName.isBlank()) {
                throw new BusinessException("Tên ngân hàng là bắt buộc khi chọn chuyển khoản");
            }
            if (bankRef.isBlank()) {
                throw new BusinessException("Mã tham chiếu giao dịch là bắt buộc khi chọn chuyển khoản");
            }
            v.setBankName(bankName);
            v.setBankRef(bankRef);
        } else {
            v.setBankName(null);
            v.setBankRef(null);
        }

        // ── 3. Cập nhật danh sách khoản chi ──────────────────────────────────
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException("Phiếu chi phải có ít nhất 1 khoản chi");
        }

        // Nạp danh mục khoản chi đang bật
        Map<Long, com.nhatnam.server.entity.VendorExpenseCategory> categoryById = new HashMap<>();
        categoryRepo.findByActiveTrueOrderByNameAsc().forEach(c -> categoryById.put(c.getId(), c));

        Map<Long, ExpenseItem> existingById = new HashMap<>();
        for (ExpenseItem it : v.getItems()) {
            if (it.getId() != null) existingById.put(it.getId(), it);
        }

        List<ExpenseItem> result = new ArrayList<>();
        Set<Long> keptIds = new HashSet<>();
        List<String> changeLog = new ArrayList<>();

        for (var p : req.getItems()) {
            if (p.getCategoryId() == null) {
                throw new BusinessException("Mỗi khoản chi phải chọn một nhãn từ danh mục khoản chi");
            }
            var cat = categoryById.get(p.getCategoryId());
            if (cat == null) {
                throw new BusinessException("Nhãn khoản chi không hợp lệ hoặc đã bị ẩn (id=" + p.getCategoryId() + ")");
            }
            if (p.getAmount() == null || p.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Số tiền của \"" + cat.getName() + "\" phải lớn hơn 0");
            }
            String note = (p.getNote() == null || p.getNote().isBlank()) ? null : p.getNote().trim();

            if (p.getId() == null) {
                result.add(ExpenseItem.builder()
                        .voucher(v)
                        .itemName(cat.getName())
                        .categoryId(cat.getId())
                        .amount(p.getAmount())
                        .note(note)
                        .build());
                changeLog.add("thêm \"" + cat.getName() + "\" (" + p.getAmount().toPlainString() + ")");
                continue;
            }

            ExpenseItem item = existingById.get(p.getId());
            if (item == null) {
                throw new BusinessException("Khoản chi (id=" + p.getId() + ") không thuộc phiếu chi này");
            }
            keptIds.add(p.getId());

            if (!cat.getId().equals(item.getCategoryId())) {
                changeLog.add("\"" + item.getItemName() + "\" → \"" + cat.getName() + "\"");
                item.setCategoryId(cat.getId());
                item.setItemName(cat.getName());
            }
            if (item.getAmount() == null || p.getAmount().compareTo(item.getAmount()) != 0) {
                changeLog.add(cat.getName() + ": " + (item.getAmount() == null ? "0" : item.getAmount().toPlainString())
                        + " → " + p.getAmount().toPlainString());
                item.setAmount(p.getAmount());
            }
            item.setNote(note);
            result.add(item);
        }

        // Xóa khoản chi cũ không còn trong danh sách
        for (ExpenseItem old : existingById.values()) {
            if (!keptIds.contains(old.getId())) {
                changeLog.add("xoá \"" + old.getItemName() + "\"");
            }
        }

        v.getItems().clear();
        v.getItems().addAll(result);

        // ── 4. Tính lại cấp duyệt theo tổng tiền mới ──────────────────────────
        BigDecimal newTotal = result.stream()
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ExpenseApprovalConfig cfg = loadConfigEntity();
        boolean saCanApprove = evaluateSaCanApprove(cfg, newTotal, v.getVendorType());
        ExpenseVoucher.ApproverScope newScope = saCanApprove
                ? ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                : ExpenseVoucher.ApproverScope.OWNER;
        v.setApproverScope(newScope);

        ExpenseVoucher saved = voucherRepo.save(v);

        // ── 5. Ghi log ──────────────────────────────────────────────────────────
        List<String> allChanges = new ArrayList<>();

        // So sánh nhà cung cấp
        String oldVendorDisplay = oldVendorName != null ? oldVendorName : "(trống)";
        String newVendorDisplay = v.getVendorName() != null ? v.getVendorName() : "(trống)";
        if (!oldVendorDisplay.equals(newVendorDisplay)) {
            allChanges.add("Nhà cung cấp: " + oldVendorDisplay + " → " + newVendorDisplay);
        }

        // So sánh lý do
        if (!oldReason.equals(reason)) {
            allChanges.add("Lý do: \"" + oldReason + "\" → \"" + reason + "\"");
        }

        // So sánh ngày/kỳ chi
        String oldDisplay = "";
        if (oldExpenseDate != null) {
            oldDisplay = "Ngày " + formatVnDate(oldExpenseDate);
        } else if (oldExpensePeriod != null && !oldExpensePeriod.isBlank()) {
            String[] parts = oldExpensePeriod.split("-");
            oldDisplay = "Kỳ Tháng " + Integer.parseInt(parts[1]) + "/" + parts[0];
        } else {
            oldDisplay = "(trống)";
        }

        String newDisplay = "";
        if (v.getExpenseDate() != null) {
            newDisplay = "Ngày " + formatVnDate(v.getExpenseDate());
        } else if (v.getExpensePeriod() != null && !v.getExpensePeriod().isBlank()) {
            String[] parts = v.getExpensePeriod().split("-");
            newDisplay = "Kỳ Tháng " + Integer.parseInt(parts[1]) + "/" + parts[0];
        } else {
            newDisplay = "(trống)";
        }

        if (!oldDisplay.equals(newDisplay)) {
            allChanges.add("Ngày/Kỳ: " + oldDisplay + " → " + newDisplay);
        }

        // So sánh phương thức thanh toán
        if (!oldPaymentType.equals(newPaymentType)) {
            allChanges.add("PTTT: " + oldPaymentType + " → " + newPaymentType);
            if (newPaymentType.equals("BANK_TRANSFER")) {
                allChanges.add("Ngân hàng: " + v.getBankName() + ", TK: " + v.getBankRef());
            }
        }

        // Thêm các thay đổi về khoản chi
        allChanges.addAll(changeLog);

        // Ghi log nếu có thay đổi
        if (!allChanges.isEmpty()) {
            String logMessage = String.join("; ", allChanges);
            if (oldTotal.compareTo(newTotal) != 0) {
                logMessage += ". Tổng tiền: " + oldTotal.toPlainString() + " → " + newTotal.toPlainString();
            }
            if (status == ExpenseVoucher.VoucherStatus.PENDING && oldScope != newScope) {
                logMessage += ". Cấp duyệt: "
                        + (newScope == ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                        ? "Kế toán trưởng duyệt được"
                        : "cần Owner/Admin duyệt");
            }
            writeLog(v, "VOUCHER_UPDATED", editor,
                    status.name(), v.getStatus().name(), logMessage);
        }

        // ── 6. Thông báo ────────────────────────────────────────────────────────
        String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
        StringBuilder msg = new StringBuilder(editorName).append(" đã cập nhật phiếu chi ").append(v.getVoucherCode());
        if (!allChanges.isEmpty()) {
            msg.append(": ").append(String.join("; ", allChanges));
        }
        if (oldTotal.compareTo(newTotal) != 0) {
            msg.append(". Tổng tiền: ").append(oldTotal.toPlainString()).append(" → ").append(newTotal.toPlainString());
        }
        if (status == ExpenseVoucher.VoucherStatus.PENDING && oldScope != newScope) {
            msg.append(". Cấp duyệt: ")
                    .append(newScope == ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                            ? "Kế toán trưởng duyệt được" : "cần Owner/Admin duyệt");
        }

        String payload = "{\"expenseVoucherId\":" + id + ",\"voucherCode\":\"" + v.getVoucherCode() + "\"}";
        notificationService.sendToRole("SUPER_ACCOUNTANT", "EXPENSE_UPDATED", msg.toString(), payload);
        notificationService.sendToRole("OWNER", "EXPENSE_UPDATED", msg.toString(), payload);
        notificationService.sendToRole("ADMIN", "EXPENSE_UPDATED", msg.toString(), payload);
        if (v.getCreatedBy() != null) {
            notificationService.sendToUser(v.getCreatedBy(), "EXPENSE_UPDATED", msg.toString(), payload);
        }

        return toDto(saved);
    }

    @Override
    @Transactional
    public ExpenseVoucherDto create(Long createdByUserId, CreateExpenseVoucherRequest req) {
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        String creatorName = creator.getFullName() != null && !creator.getFullName().isBlank()
                ? creator.getFullName() : creator.getUsername();
        Role creatorRole = effectiveRoleOf(creator);
        String creatorRoleName = creatorRole != null ? creatorRole.name() : "ACCOUNTANT";

        User requestedByUser = null;
        String requestedByName = req.getRequestedByName();
        if (req.getRequestedById() != null) {
            requestedByUser = userRepository.findById(req.getRequestedById()).orElse(null);
            if (requestedByUser != null && (requestedByName == null || requestedByName.isBlank())) {
                requestedByName = requestedByUser.getFullName() != null
                        ? requestedByUser.getFullName() : requestedByUser.getUsername();
            }
        }
        if (requestedByName == null || requestedByName.isBlank()) requestedByName = creatorName;

        String imageUrlsJson = null;
        if (req.getImageUrls() != null && !req.getImageUrls().isEmpty()) {
            try { imageUrlsJson = objectMapper.writeValueAsString(req.getImageUrls()); }
            catch (Exception e) { log.warn("Failed to serialize imageUrls"); }
        }

        // ── Loại thanh toán ──────────────────────────────────────────────────────
        ExpenseVoucher.PaymentType paymentType = ExpenseVoucher.PaymentType.CASH;
        String bankName = null, bankRef = null;
        if ("BANK_TRANSFER".equalsIgnoreCase(req.getPaymentType())) {
            paymentType = ExpenseVoucher.PaymentType.BANK_TRANSFER;
            bankName = req.getBankName() != null ? req.getBankName().trim() : "";
            bankRef  = req.getBankRef()  != null ? req.getBankRef().trim()  : "";
            if (bankName.isBlank()) throw new BusinessException("Tên ngân hàng là bắt buộc khi chuyển khoản");
            if (bankRef.isBlank())  throw new BusinessException("Mã tham chiếu giao dịch là bắt buộc khi chuyển khoản");
        }

        // ── Số phiếu chi ──────────────────────────────────────────────────────
        String paymentNumber = req.getPaymentNumber() != null ? req.getPaymentNumber().trim() : "";
        if (paymentNumber.isBlank()) paymentNumber = suggestNextPaymentNumber();

        // ── Ngày chi / Kỳ chi ──────────────────────────────────────────────────
        // Quy tắc:
        // - Nếu là ngày cụ thể: chỉ ghi expense_date, expense_period = null
        // - Nếu là kỳ: ghi cả expense_date (ngày đầu tháng) và expense_period
        long nowMs = System.currentTimeMillis();
        Long expenseDate = null;
        String expensePeriod = null;
        long effectiveAt;

        Long reqExpenseDate = req.getExpenseDate();      // Ngày cụ thể (epoch ms)
        String reqExpensePeriod = req.getExpensePeriod(); // Kỳ (yyyy-MM)

        if (reqExpenseDate != null) {
            // Chế độ NGÀY CỤ THỂ: chỉ ghi expense_date, expense_period = null
            expenseDate = reqExpenseDate;
            expensePeriod = null;
            effectiveAt = isSameVnDay(expenseDate, nowMs) ? nowMs : at8amVnOf(expenseDate);
        } else if (reqExpensePeriod != null && !reqExpensePeriod.isBlank()) {
            // Chế độ KỲ: ghi cả expense_date (ngày đầu tháng) và expense_period
            expensePeriod = reqExpensePeriod;
            // Chuyển kỳ thành ngày đầu tháng
            String[] parts = expensePeriod.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            // Ngày đầu tháng 00:00 giờ VN
            expenseDate = java.time.LocalDate.of(year, month, 1)
                    .atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
            effectiveAt = nowMs; // Kỳ: dòng tiền tính theo thời điểm tạo
        } else {
            // Không chọn gì → mặc định hôm nay (ngày cụ thể)
            expenseDate = startOfTodayMs();
            expensePeriod = null;
            effectiveAt = nowMs;
        }

        // ── Tổng tiền ───────────────────────────────────────────────────────────
        BigDecimal total = req.getItems().stream()
                .map(CreateExpenseVoucherRequest.ExpenseItemRequest::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Đánh giá điều kiện SUPER_ACCOUNTANT được duyệt ────────────────────
        ExpenseApprovalConfig cfg = loadConfigEntity();
        boolean saCanApprove = evaluateSaCanApprove(cfg, total, req.getVendorType());

        boolean creatorIsOwnerAdmin = creatorRole == Role.OWNER || creatorRole == Role.ADMIN
                || creatorRole == Role.SUPERADMIN;
        boolean creatorIsSA = creatorRole == Role.SUPER_ACCOUNTANT;

        ExpenseVoucher.VoucherStatus status;
        ExpenseVoucher.ApproverScope scope;
        User approver = null;
        String approverName = null;
        Long approvedAt = null;

        if (creatorIsOwnerAdmin) {
            status = ExpenseVoucher.VoucherStatus.APPROVED;
            scope  = ExpenseVoucher.ApproverScope.OWNER;
            approver = creator; approverName = creatorName; approvedAt = System.currentTimeMillis();
        } else if (creatorIsSA) {
            scope = saCanApprove ? ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                    : ExpenseVoucher.ApproverScope.OWNER;
            if (saCanApprove) {
                status = ExpenseVoucher.VoucherStatus.APPROVED;
                approver = creator; approverName = creatorName; approvedAt = System.currentTimeMillis();
            } else {
                status = ExpenseVoucher.VoucherStatus.PENDING;
            }
        } else {
            status = ExpenseVoucher.VoucherStatus.PENDING;
            scope  = saCanApprove ? ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                    : ExpenseVoucher.ApproverScope.OWNER;
        }

        // ── DANH MỤC KHOẢN CHI ──────────────────────────────────────────────────
        Map<Long, com.nhatnam.server.entity.VendorExpenseCategory> categoryById = new HashMap<>();
        if (req.getVendorId() != null) {
            var activeCats = categoryRepo.findByActiveTrueOrderByNameAsc();
            if (activeCats.isEmpty()) {
                throw new BusinessException("Chưa có danh mục khoản chi nào. "
                        + "Vui lòng liên hệ Owner thêm nhãn khoản chi trước khi lập phiếu.");
            }
            activeCats.forEach(c -> categoryById.put(c.getId(), c));
            for (CreateExpenseVoucherRequest.ExpenseItemRequest it : req.getItems()) {
                if (it.getCategoryId() == null) {
                    throw new BusinessException("Mỗi khoản chi phải chọn một nhãn từ danh mục khoản chi");
                }
                if (!categoryById.containsKey(it.getCategoryId())) {
                    throw new BusinessException("Nhãn khoản chi không hợp lệ hoặc đã bị ẩn (id=" + it.getCategoryId() + ")");
                }
            }
        }

        ExpenseVoucher voucher = ExpenseVoucher.builder()
                .voucherCode(generateCode())
                .paymentNumber(paymentNumber)
                .vendorName(req.getVendorName())
                .vendorId(req.getVendorId())
                .vendorType(req.getVendorType())
                .reason(req.getReason())
                .expensePeriod(expensePeriod)  // null nếu là ngày cụ thể
                .expenseDate(expenseDate)      // luôn có giá trị (ngày cụ thể hoặc ngày đầu tháng)
                .effectiveAt(effectiveAt)
                .paymentType(paymentType)
                .bankName(bankName)
                .bankRef(bankRef)
                .createdByName(creatorName)
                .createdBy(creator)
                .createdByRole(creatorRoleName)
                .requestedByName(requestedByName)
                .requestedBy(requestedByUser)
                .status(status)
                .approverScope(scope)
                .approvedBy(approver)
                .approvedByName(approverName)
                .approvedAt(approvedAt)
                .imageUrls(imageUrlsJson)
                .items(new ArrayList<>())
                .build();

        voucher = voucherRepo.save(voucher);
        for (CreateExpenseVoucherRequest.ExpenseItemRequest itemReq : req.getItems()) {
            String itemName;
            Long categoryId = itemReq.getCategoryId();
            if (categoryId != null && categoryById.containsKey(categoryId)) {
                itemName = categoryById.get(categoryId).getName();
            } else {
                itemName = itemReq.getItemName();
                if (itemName == null || itemName.isBlank()) {
                    throw new BusinessException("Tên khoản chi là bắt buộc");
                }
            }
            voucher.getItems().add(ExpenseItem.builder()
                    .voucher(voucher)
                    .itemName(itemName)
                    .categoryId(categoryId)
                    .amount(itemReq.getAmount())
                    .note(itemReq.getNote())
                    .build());
        }
        voucher = voucherRepo.save(voucher);

        notifyOnCreate(voucher, creatorName, saCanApprove, creatorIsOwnerAdmin, creatorIsSA);
        return toDto(voucher);
    }


    // ══════════════════════════════════════════════════════════════════════════
    //  NHẬP HÀNG LOẠT TỪ EXCEL
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public byte[] buildImportTemplate() {
        List<String> vendorNames = materialVendorRepo.findByActiveTrueOrderByNameAsc()
                .stream().map(com.nhatnam.server.entity.MaterialVendor::getName)
                .filter(java.util.Objects::nonNull).toList();
        List<String> categoryNames = categoryRepo.findByActiveTrueOrderByNameAsc()
                .stream().map(com.nhatnam.server.entity.VendorExpenseCategory::getName)
                .filter(java.util.Objects::nonNull).toList();
        try {
            return com.nhatnam.server.utils.ExpenseVoucherExcel.buildTemplate(vendorNames, categoryNames);
        } catch (Exception e) {
            log.error("Không dựng được template phiếu chi", e);
            throw new BusinessException("Không tạo được file mẫu. Vui lòng thử lại.");
        }
    }

    @Override
    public com.nhatnam.server.dto.expense.ExpenseImportResultDto importFromExcel(
            Long createdByUserId, org.springframework.web.multipart.MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new BusinessException("Chưa chọn file để nhập");
        }

        // Map tên → entity (không phân biệt hoa/thường) để tra cứu nhanh khi ghép
        java.util.Map<String, com.nhatnam.server.entity.MaterialVendor> vendorByName = new java.util.HashMap<>();
        for (var v : materialVendorRepo.findByActiveTrueOrderByNameAsc()) {
            if (v.getName() != null) vendorByName.put(v.getName().trim().toLowerCase(), v);
        }
        java.util.Map<String, com.nhatnam.server.entity.VendorExpenseCategory> catByName = new java.util.HashMap<>();
        for (var c : categoryRepo.findByActiveTrueOrderByNameAsc()) {
            if (c.getName() != null) catByName.put(c.getName().trim().toLowerCase(), c);
        }

        List<com.nhatnam.server.utils.ExpenseVoucherExcel.RawVoucher> groups;
        try {
            groups = com.nhatnam.server.utils.ExpenseVoucherExcel.parse(file.getInputStream());
        } catch (Exception e) {
            log.error("Lỗi đọc file Excel phiếu chi", e);
            throw new BusinessException("File không hợp lệ hoặc sai định dạng. Vui lòng dùng đúng file mẫu.");
        }

        if (groups.isEmpty()) {
            throw new BusinessException("File không có dòng dữ liệu nào để nhập.");
        }

        List<String> createdCodes = new ArrayList<>();
        List<com.nhatnam.server.dto.expense.ExpenseImportResultDto.VoucherError> errors = new ArrayList<>();

        for (var g : groups) {
            try {
                CreateExpenseVoucherRequest req = toCreateRequest(g, vendorByName, catByName);
                ExpenseVoucherDto dto = self.create(createdByUserId, req);
                createdCodes.add(dto.getVoucherCode());
            } catch (Exception ex) {
                errors.add(com.nhatnam.server.dto.expense.ExpenseImportResultDto.VoucherError.builder()
                        .groupKey(g.paymentNumber == null ? "" : g.paymentNumber)
                        .excelRow(g.firstExcelRow)
                        .message(ex.getMessage() != null ? ex.getMessage() : "Lỗi không xác định")
                        .build());
            }
        }

        return com.nhatnam.server.dto.expense.ExpenseImportResultDto.builder()
                .totalVouchers(groups.size())
                .created(createdCodes.size())
                .failed(errors.size())
                .createdCodes(createdCodes)
                .errors(errors)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  XUẤT BÁO CÁO EXCEL
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public byte[] exportReport(Long from, Long to, String exportedBy, String paymentType) throws Exception {
        List<ExpenseVoucher> vouchers = voucherRepo.findByDateRange(from, to, Pageable.unpaged()).getContent();

        // Lọc theo phương thức thanh toán: CASH / BANK_TRANSFER; null/""/ALL/BOTH = cả hai (+ cột PTTT)
        String pt = paymentType == null ? "" : paymentType.trim().toUpperCase();
        boolean showPaymentCol = pt.isEmpty() || pt.equals("ALL") || pt.equals("BOTH");
        if (!showPaymentCol) {
            ExpenseVoucher.PaymentType want = pt.equals("BANK_TRANSFER")
                    ? ExpenseVoucher.PaymentType.BANK_TRANSFER : ExpenseVoucher.PaymentType.CASH;
            vouchers = vouchers.stream()
                    .filter(v -> {
                        ExpenseVoucher.PaymentType vp = v.getPaymentType() != null
                                ? v.getPaymentType() : ExpenseVoucher.PaymentType.CASH;
                        return vp == want;
                    })
                    .toList();
        }

        // Sắp xếp tăng dần theo thời gian tạo cho báo cáo dễ đọc
        vouchers = vouchers.stream()
                .sorted(Comparator.comparing(v -> v.getCreatedAt() == null ? 0L : v.getCreatedAt()))
                .toList();

        String methodLabel = showPaymentCol ? "Tiền mặt + Chuyển khoản"
                : (pt.equals("BANK_TRANSFER") ? "Chuyển khoản" : "Tiền mặt");

        return com.nhatnam.server.utils.ExpenseReportExcel.buildReport(
                vouchers, from, to, exportedBy, showPaymentCol, methodLabel);
    }

    /** Chuyển một phiếu thô từ Excel thành request tạo phiếu (validate + map tên → ID). */
    private CreateExpenseVoucherRequest toCreateRequest(
            com.nhatnam.server.utils.ExpenseVoucherExcel.RawVoucher g,
            java.util.Map<String, com.nhatnam.server.entity.MaterialVendor> vendorByName,
            java.util.Map<String, com.nhatnam.server.entity.VendorExpenseCategory> catByName) {

        // ── Số phiếu chi (bắt buộc) ──────────────────────────────────────────
        if (g.paymentNumber == null || g.paymentNumber.isBlank()) {
            throw new BusinessException("Thiếu \"Số phiếu chi\"");
        }

        // ── Nhà cung cấp (bắt buộc chọn từ danh sách) ────────────────────────
        if (g.vendorName == null || g.vendorName.isBlank()) {
            throw new BusinessException("Thiếu \"Tên nhà cung cấp\"");
        }
        var vendor = vendorByName.get(g.vendorName.trim().toLowerCase());
        if (vendor == null) {
            throw new BusinessException("Nhà cung cấp không có trong danh sách: \"" + g.vendorName + "\"");
        }

        // ── Lý do (bắt buộc) ─────────────────────────────────────────────────
        if (g.reason == null || g.reason.isBlank()) {
            throw new BusinessException("Thiếu \"Lý do\"");
        }

        // ── Khoản chi (bắt buộc chọn từ danh mục) ────────────────────────────
        if (g.items.isEmpty()) {
            throw new BusinessException("Phiếu chưa có khoản chi nào");
        }
        List<CreateExpenseVoucherRequest.ExpenseItemRequest> items = new ArrayList<>();
        for (var it : g.items) {
            if (it.categoryName == null || it.categoryName.isBlank()) {
                throw new BusinessException("Có khoản chi thiếu tên \"Tên khoản chi\"");
            }
            var cat = catByName.get(it.categoryName.trim().toLowerCase());
            if (cat == null) {
                throw new BusinessException("Khoản chi không có trong danh mục: \"" + it.categoryName + "\"");
            }
            if (it.amount == null || it.amount.signum() <= 0) {
                throw new BusinessException("Khoản \"" + it.categoryName + "\" phải có số tiền lớn hơn 0");
            }
            var itemReq = new CreateExpenseVoucherRequest.ExpenseItemRequest();
            itemReq.setCategoryId(cat.getId());
            itemReq.setAmount(it.amount);
            items.add(itemReq);
        }

        // ── Loại phiếu chi: Kỳ (tháng) | Ngày (ngày cụ thể) ──────────────────
        String type = g.voucherTypeRaw == null ? "" : g.voucherTypeRaw.trim().toLowerCase();
        boolean isPeriod;
        if (type.startsWith("k")) isPeriod = true;          // "Kỳ" / "Ky"
        else if (type.startsWith("ng")) isPeriod = false;   // "Ngày" / "Ngay"
        else isPeriod = (g.periodRaw != null && !g.periodRaw.isBlank()) && g.expenseDateMs == null;

        String expensePeriod = null;
        Long expenseDate = null;
        if (isPeriod) {
            expensePeriod = normalizePeriod(g.periodRaw);
            if (expensePeriod == null) {
                throw new BusinessException("Loại \"Kỳ\" nhưng cột \"Tháng\" trống hoặc sai định dạng (yyyy-MM)");
            }
        } else {
            // Ngày: dùng ngày đã chọn; trống → mặc định hôm nay
            expenseDate = g.expenseDateMs != null ? g.expenseDateMs : startOfTodayMs();
        }

        // ── Chặn nhập trùng ──────────────────────────────────────────────────
        // Số phiếu chi quay vòng tới 15000 nên CÓ THỂ trùng ở các ngày/kỳ khác nhau,
        // nhưng KHÔNG được trùng trong cùng một ngày (hoặc cùng một kỳ).
        String pn = g.paymentNumber.trim();
        if (expenseDate != null) {
            long dayStart = startOfVnDayMs(expenseDate);
            long dayEnd = dayStart + 86_399_999L;
            if (voucherRepo.existsByPaymentNumberOnDay(pn, dayStart, dayEnd)) {
                throw new BusinessException("Số phiếu chi \"" + pn + "\" đã tồn tại trong ngày "
                        + formatVnDate(expenseDate) + " — có thể bạn đã nhập file này rồi");
            }
        } else {
            if (voucherRepo.existsByPaymentNumberInPeriod(pn, expensePeriod)) {
                throw new BusinessException("Số phiếu chi \"" + pn + "\" đã tồn tại trong kỳ "
                        + expensePeriod + " — có thể bạn đã nhập file này rồi");
            }
        }

        CreateExpenseVoucherRequest req = new CreateExpenseVoucherRequest();
        req.setPaymentNumber(g.paymentNumber.trim());
        req.setVendorName(vendor.getName());
        req.setVendorId(vendor.getId());
        req.setVendorType(vendor.getVendorType() != null ? vendor.getVendorType().name() : null);
        req.setReason(g.reason.trim());
        req.setExpensePeriod(expensePeriod);
        req.setExpenseDate(expenseDate);
        req.setPaymentType("CASH");
        req.setItems(items);
        return req;
    }

    /**
     * Chuẩn hoá cột "Tháng" về "yyyy-MM".
     * Chấp nhận: "Tháng 7/2026" (dropdown), "yyyy-MM", "yyyy/MM", "MM/yyyy", "M-yyyy".
     */
    private String normalizePeriod(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Bỏ tiền tố "Tháng " (không phân biệt hoa thường, có/không dấu)
        s = s.replaceFirst("(?iu)^th[áa]ng\\s*", "").trim();
        java.util.regex.Matcher m;
        // yyyy-MM hoặc yyyy/MM
        m = java.util.regex.Pattern.compile("^(\\d{4})[-/](\\d{1,2})$").matcher(s);
        if (m.matches()) return fmtYm(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        // MM/yyyy hoặc MM-yyyy
        m = java.util.regex.Pattern.compile("^(\\d{1,2})[-/](\\d{4})$").matcher(s);
        if (m.matches()) return fmtYm(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        return null;
    }

    private String fmtYm(int year, int month) {
        if (month < 1 || month > 12) return null;
        return String.format("%04d-%02d", year, month);
    }

    /** Gửi noti/ws theo đúng luồng đã thống nhất. */
    private void notifyOnCreate(ExpenseVoucher v, String creatorName,
                                boolean saCanApprove, boolean creatorIsOwnerAdmin, boolean creatorIsSA) {
        String payload = "{\"voucherId\":" + v.getId() + ",\"voucherCode\":\"" + v.getVoucherCode() + "\"}";

        if (v.getStatus() == ExpenseVoucher.VoucherStatus.APPROVED) {
            // Tự duyệt (OWNER/ADMIN, hoặc SA đủ điều kiện) → báo OWNER + ADMIN để nắm
            String msg = creatorName + " đã tạo & duyệt phiếu chi [" + v.getVoucherCode() + "]. Lý do: " + v.getReason();
            notificationService.sendToRole("OWNER", "EXPENSE_CREATED", msg, payload);
            notificationService.sendToRole("ADMIN", "EXPENSE_CREATED", msg, payload);
            return;
        }

        // PENDING
        if (v.getApproverScope() == ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT) {
            // Nhân viên tạo & SA đủ điều kiện → báo SUPER_ACCOUNTANT duyệt
            String msg = creatorName + " tạo phiếu chi [" + v.getVoucherCode() + "] cần bạn duyệt. Lý do: " + v.getReason();
            notificationService.sendToRole("SUPER_ACCOUNTANT", "EXPENSE_PENDING", msg, payload);
        } else {
            // Cần OWNER/ADMIN duyệt
            String msg = creatorName + " tạo phiếu chi [" + v.getVoucherCode() + "] cần duyệt. Lý do: " + v.getReason();
            notificationService.sendToRole("OWNER", "EXPENSE_PENDING", msg, payload);
            notificationService.sendToRole("ADMIN", "EXPENSE_PENDING", msg, payload);
        }
    }

    private ExpenseApprovalConfig loadConfigEntity() {
        return approvalConfigRepo.findById(1L).orElseGet(() -> {
            ExpenseApprovalConfig c = ExpenseApprovalConfig.builder()
                    .id(1L).thresholdAmount(DEFAULT_THRESHOLD).allowedCategories("[]")
                    .updatedAt(System.currentTimeMillis()).build();
            return approvalConfigRepo.save(c);
        });
    }

    private boolean evaluateSaCanApprove(ExpenseApprovalConfig cfg, BigDecimal total, String vendorType) {
        if (vendorType == null || vendorType.isBlank()) return false;
        BigDecimal threshold = cfg.getThresholdAmount() != null ? cfg.getThresholdAmount() : DEFAULT_THRESHOLD;
        boolean underThreshold = total.compareTo(threshold) < 0;
        List<String> allowed = parseCategories(cfg.getAllowedCategories());
        boolean inCategory = allowed.contains(vendorType);
        return underThreshold && inCategory;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseCategories(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    @Override
    public ExpenseVoucherDto getById(Long id) {
        return toDto(findOrThrow(id));
    }


    private static final Set<String> SEE_ALL_ROLES =
            Set.of("OWNER", "ADMIN", "SUPERADMIN", "SUPER_ACCOUNTANT");

    private boolean canSeeAll(String callerRole) {
        return callerRole != null && SEE_ALL_ROLES.contains(callerRole.toUpperCase());
    }

    private List<ExpenseVoucherDto> filterByScope(String callerRole, Long callerUserId,
                                                  List<ExpenseVoucherDto> list) {
        if (canSeeAll(callerRole)) return list;
        if (callerUserId == null) return List.of();
        return list.stream()
                .filter(v -> callerUserId.equals(v.getCreatedById()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ExpenseVoucherDto> listAll(String callerRole, Long callerUserId, Pageable pageable) {
        List<ExpenseVoucher> all = voucherRepo
                .findAllByOrderByCreatedAtDesc(Pageable.unpaged()).getContent();

        List<ExpenseVoucherDto> merged = new ArrayList<>(all.stream().map(this::toDto).toList());
        merged.addAll(vendorDebtService.listVendorPaymentsAsExpenseDto(null, null));
        List<ExpenseVoucherDto> filtered = filterByScope(callerRole, callerUserId, merged);

        return paginateMerged(filtered, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ExpenseVoucherDto> listByExpenseDateRange(String callerRole, Long callerUserId,
                                                                  Long from, Long to, Pageable pageable) {
        // Sử dụng findByExpenseDateRangeList để lấy tất cả phiếu theo ngày chi/kỳ chi
        List<ExpenseVoucher> all = voucherRepo.findByExpenseDateRangeList(from, to);
        List<ExpenseVoucherDto> merged = new ArrayList<>(all.stream().map(this::toDto).toList());
        merged.addAll(vendorDebtService.listVendorPaymentsAsExpenseDto(from, to));

        // Lọc theo scope (phòng ban)
        List<ExpenseVoucherDto> filtered = filterByScope(callerRole, callerUserId, merged);

        return paginateMerged(filtered, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ExpenseVoucherDto> searchByExpenseDateRange(String callerRole, Long callerUserId,
                                                                    String q, Long from, Long to, Pageable pageable) {
        List<ExpenseVoucher> all = new ArrayList<>();

        // Kiểm tra nếu q là số tiền
        BigDecimal amount = parseAmount(q);
        if (amount != null) {
            // Tìm theo số tiền
            if (from != null && to != null) {
                all = voucherRepo.searchByAmountAndExpenseDateRange(amount, from, to);
            } else {
                all = voucherRepo.searchAllByAmount(amount);
            }
        } else if (q != null && !q.trim().isEmpty()) {
            // Tìm theo text
            if (from != null && to != null) {
                all = voucherRepo.searchByExpenseDateRangeList(q, from, to);
            } else {
                all = voucherRepo.searchAllByExpenseDate(q);
            }
        } else {
            // Không có từ khóa, chỉ lọc theo ngày
            if (from != null && to != null) {
                all = voucherRepo.findByExpenseDateRangeList(from, to);
            } else {
                all = voucherRepo.findAllByOrderByCreatedAtDesc(Pageable.unpaged()).getContent();
            }
        }

        List<ExpenseVoucherDto> merged = new ArrayList<>(all.stream().map(this::toDto).toList());
        merged.addAll(vendorDebtService.searchVendorPaymentsAsExpenseDto(q, from, to));

        List<ExpenseVoucherDto> filtered = filterByScope(callerRole, callerUserId, merged);

        return paginateMerged(filtered, pageable);
    }

    /**
     * Chuyển từ khoá thành SỐ TIỀN CHÍNH XÁC để so khớp tổng phiếu (khớp đúng, không
     * phải chứa-trong-chuỗi). "700000"/"700.000" → 700000; có chữ cái → null.
     */
    private static java.math.BigDecimal parseAmount(String q) {
        if (q == null) return null;
        String t = q.trim();
        if (t.isEmpty() || !t.matches("[0-9][0-9.,\\s]*")) return null;
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try { return new java.math.BigDecimal(digits); }
        catch (NumberFormatException e) { return null; }
    }


    /** Lọc danh sách theo phòng ban của người gọi. Phiếu trả công nợ NCC coi là kế toán. */
    private List<ExpenseVoucherDto> filterByDept(String callerRole, List<ExpenseVoucherDto> list) {
        Set<String> allowed = deptRolesFor(callerRole);
        if (allowed == null) return list;
        return list.stream().filter(v -> {
            if ("VENDOR_DEBT_PAYMENT".equals(v.getVoucherType())) return allowed.contains("ACCOUNTANT");
            return allowed.contains(voucherDeptRole(v));
        }).toList();
    }

    private PageResponse<ExpenseVoucherDto> paginateMerged(List<ExpenseVoucherDto> merged, Pageable pageable) {
        merged = new ArrayList<>(merged);
        merged.sort(Comparator.comparing(ExpenseVoucherDto::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = merged.size();
        int pageSize = pageable.getPageSize();
        int pageNum = pageable.getPageNumber();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 1;
        int fromIdx = Math.min(pageNum * pageSize, total);
        int toIdx = Math.min(fromIdx + pageSize, total);
        List<ExpenseVoucherDto> pageContent = merged.subList(fromIdx, toIdx);

        return PageResponse.<ExpenseVoucherDto>builder()
                .content(pageContent)
                .page(pageNum)
                .size(pageSize)
                .totalElements(total)
                .totalPages(totalPages)
                .first(pageNum == 0)
                .last(pageNum >= totalPages - 1)
                .build();
    }

    // ── Duyệt / từ chối HÀNG LOẠT ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>KHÔNG đánh {@code @Transactional} ở đây — mỗi phiếu được xử lý qua
     * {@link #self} nên chạy trong MỘT giao dịch RIÊNG. Nhờ vậy phiếu lỗi
     * (đã duyệt rồi, không đủ quyền…) chỉ rollback chính nó, các phiếu còn lại
     * vẫn được duyệt bình thường — giống cách {@code importFromExcel} đang làm.
     */
    @Override
    public com.nhatnam.server.dto.expense.BulkExpenseActionResultDto bulkApprove(
            Long approverUserId, com.nhatnam.server.dto.expense.BulkExpenseActionRequest req) {
        return runBulk(req, id -> self.approve(id, approverUserId, req.getNote()));
    }

    @Override
    public com.nhatnam.server.dto.expense.BulkExpenseActionResultDto bulkReject(
            Long approverUserId, com.nhatnam.server.dto.expense.BulkExpenseActionRequest req) {
        String reason = req != null && req.getReason() != null ? req.getReason().trim() : "";
        if (reason.isBlank()) throw new BusinessException("Vui lòng nhập lý do từ chối");
        return runBulk(req, id -> self.reject(id, approverUserId, reason));
    }

    /** Chạy một hành động cho từng id, gom kết quả từng phiếu (không dừng khi lỗi). */
    private com.nhatnam.server.dto.expense.BulkExpenseActionResultDto runBulk(
            com.nhatnam.server.dto.expense.BulkExpenseActionRequest req,
            java.util.function.Function<Long, ExpenseVoucherDto> action) {

        if (req == null || req.getIds() == null || req.getIds().isEmpty()) {
            throw new BusinessException("Chưa chọn phiếu chi nào");
        }
        // Bỏ trùng nhưng giữ thứ tự client gửi lên
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(req.getIds()));

        List<com.nhatnam.server.dto.expense.BulkExpenseActionResultDto.ItemResult> results =
                new ArrayList<>();
        int ok = 0, fail = 0;

        for (Long id : ids) {
            if (id == null) continue;
            try {
                ExpenseVoucherDto dto = action.apply(id);
                ok++;
                results.add(com.nhatnam.server.dto.expense.BulkExpenseActionResultDto.ItemResult.builder()
                        .id(id).voucherCode(dto.getVoucherCode()).success(true).build());
            } catch (Exception e) {
                fail++;
                String msg = (e instanceof BusinessException || e instanceof ResourceNotFoundException)
                        ? e.getMessage()
                        : "Lỗi không xác định khi xử lý phiếu";
                if (!(e instanceof BusinessException) && !(e instanceof ResourceNotFoundException)) {
                    log.error("Lỗi xử lý hàng loạt phiếu chi id={}", id, e);
                }
                results.add(com.nhatnam.server.dto.expense.BulkExpenseActionResultDto.ItemResult.builder()
                        .id(id).success(false).message(msg).build());
            }
        }

        return com.nhatnam.server.dto.expense.BulkExpenseActionResultDto.builder()
                .total(ids.size()).succeeded(ok).failed(fail).results(results).build();
    }

    // ── Mở lại phiếu về CHỜ DUYỆT ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Chỉ OWNER/ADMIN (kiểm tra thêm ở service, không chỉ dựa vào {@code @PreAuthorize}
     * vì hệ thống có đa vai trò). Sau khi mở lại, cấp duyệt được TÍNH LẠI theo tổng
     * tiền hiện tại — nên phiếu có thể chuyển từ "cần Owner duyệt" sang
     * "Kế toán trưởng duyệt được" nếu trong lúc đó khoản chi đã bị sửa/xoá bớt.
     */
    @Override
    @Transactional
    public ExpenseVoucherDto reopen(Long id, Long editorUserId, String note) {
        ExpenseVoucher v = findOrThrow(id);

        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        Set<Role> roles = editor.getAllRoles();
        boolean isOwnerAdmin = roles.contains(Role.OWNER) || roles.contains(Role.ADMIN)
                || roles.contains(Role.SUPERADMIN);
        if (!isOwnerAdmin) {
            throw new BusinessException("Chỉ Owner/Admin được chuyển phiếu về trạng thái chờ duyệt");
        }

        ExpenseVoucher.VoucherStatus from = v.getStatus();
        if (from == ExpenseVoucher.VoucherStatus.PENDING) {
            throw new BusinessException("Phiếu đang ở trạng thái chờ duyệt rồi");
        }

        // Xoá dấu vết duyệt / từ chối cũ
        v.setStatus(ExpenseVoucher.VoucherStatus.PENDING);
        v.setApprovedBy(null);
        v.setApprovedByName(null);
        v.setApprovedAt(null);
        v.setRejectReason(null);

        // Tính lại cấp duyệt theo tổng tiền hiện tại
        BigDecimal total = v.getItems().stream()
                .map(ExpenseItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ExpenseApprovalConfig cfg = loadConfigEntity();
        v.setApproverScope(evaluateSaCanApprove(cfg, total, v.getVendorType())
                ? ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                : ExpenseVoucher.ApproverScope.OWNER);

        ExpenseVoucher saved = voucherRepo.save(v);

        // ── Ghi nhật ký: ai + VAI TRÒ ĐANG ACTIVE trong JWT (OWNER hay ADMIN) ─
        String actorRole = writeLog(v, "REOPENED", editor,
                from.name(), ExpenseVoucher.VoucherStatus.PENDING.name(),
                note != null && !note.isBlank() ? note.trim() : null);

        String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
        String fromLabel = from == ExpenseVoucher.VoucherStatus.APPROVED ? "Đã duyệt" : "Từ chối";
        String msg = editorName + " (" + actorRole + ") đã chuyển phiếu chi "
                + v.getVoucherCode() + " từ \"" + fromLabel + "\" về \"Chờ duyệt\""
                + (note != null && !note.isBlank() ? ". Ghi chú: " + note.trim() : "");
        String payload = "{\"expenseVoucherId\":" + id
                + ",\"voucherCode\":\"" + v.getVoucherCode() + "\"}";
        notificationService.sendToRole("SUPER_ACCOUNTANT", "EXPENSE_REOPENED", msg, payload);
        notificationService.sendToRole("OWNER", "EXPENSE_REOPENED", msg, payload);
        notificationService.sendToRole("ADMIN", "EXPENSE_REOPENED", msg, payload);
        if (v.getCreatedBy() != null) {
            notificationService.sendToUser(v.getCreatedBy(), "EXPENSE_REOPENED", msg, payload);
        }

        return toDto(saved);
    }

    // ── Nhật ký ──────────────────────────────────────────────────────────────

    @Override
    public java.util.List<com.nhatnam.server.dto.expense.ExpenseVoucherLogDto> getLogs(Long voucherId) {
        return voucherLogRepo.findByVoucher_IdOrderByCreatedAtDesc(voucherId).stream()
                .map(l -> com.nhatnam.server.dto.expense.ExpenseVoucherLogDto.builder()
                        .id(l.getId())
                        .action(l.getAction())
                        .actorName(l.getActorName())
                        .actorRole(l.getActorRole())
                        .fromStatus(l.getFromStatus())
                        .toStatus(l.getToStatus())
                        .note(l.getNote())
                        .createdAt(l.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Ghi một dòng nhật ký cho phiếu chi.
     *
     * <p>{@code actorRole} lấy từ {@link #effectiveRoleOf} = vai trò ĐANG ACTIVE trong
     * JWT, nên với người kiêm nhiều vai ta biết chính xác họ thao tác dưới vai nào
     * (VD một người vừa là OWNER vừa là SUPER_ACCOUNTANT).
     *
     * @return tên vai trò đã ghi vào log (để dùng lại trong nội dung thông báo)
     */
    private String writeLog(ExpenseVoucher voucher, String action, User actor,
                            String fromStatus, String toStatus, String note) {
        Role role = effectiveRoleOf(actor);
        String roleName = role != null ? role.name() : null;
        try {
            voucherLogRepo.save(com.nhatnam.server.entity.ExpenseVoucherLog.builder()
                    .voucher(voucher)
                    .action(action)
                    .actorName(actor.getFullName() != null ? actor.getFullName() : actor.getUsername())
                    .actorRole(roleName)
                    .fromStatus(fromStatus)
                    .toStatus(toStatus)
                    .note(note)
                    .createdAt(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            // Nhật ký không được phép làm hỏng nghiệp vụ chính
            log.warn("Không ghi được nhật ký phiếu chi {} action={}", voucher.getId(), action, e);
        }
        return roleName;
    }

    @Override
    @Transactional
    public ExpenseVoucherDto approve(Long id, Long approverUserId, String note) {
        ExpenseVoucher voucher = findOrThrow(id);
        if (voucher.getStatus() != ExpenseVoucher.VoucherStatus.PENDING)
            throw new BusinessException("Phiếu đã được xử lý rồi");

        User approver = userRepository.findById(approverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        assertCanApprove(approver, voucher);

        String approverName = approver.getFullName() != null ? approver.getFullName() : approver.getUsername();
        voucher.setStatus(ExpenseVoucher.VoucherStatus.APPROVED);
        voucher.setApprovedBy(approver);
        voucher.setApprovedByName(approverName);
        voucher.setApprovedAt(System.currentTimeMillis());
        voucherRepo.save(voucher);

        writeLog(voucher, "APPROVED", approver,
                ExpenseVoucher.VoucherStatus.PENDING.name(),
                ExpenseVoucher.VoucherStatus.APPROVED.name(), note);

        String payload = "{\"voucherId\":" + id + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
        String msg = "Phiếu chi [" + voucher.getVoucherCode() + "] đã được " + approverName + " DUYỆT.";
        // Luôn báo người tạo & người yêu cầu
        notificationService.sendToUser(voucher.getCreatedBy(), "EXPENSE_APPROVED", msg, payload);
        if (voucher.getRequestedBy() != null &&
                voucher.getRequestedBy().getId() != voucher.getCreatedBy().getId()) {
            notificationService.sendToUser(voucher.getRequestedBy(), "EXPENSE_APPROVED", msg, payload);
        }
        // Nếu SUPER_ACCOUNTANT duyệt → báo thêm OWNER + ADMIN
        if (effectiveRoleOf(approver) == Role.SUPER_ACCOUNTANT) {
            notificationService.sendToRole("OWNER", "EXPENSE_APPROVED", msg, payload);
            notificationService.sendToRole("ADMIN", "EXPENSE_APPROVED", msg, payload);
        }
        return toDto(voucher);
    }

    @Override
    @Transactional
    public ExpenseVoucherDto reject(Long id, Long approverUserId, String reason) {
        ExpenseVoucher voucher = findOrThrow(id);
        if (voucher.getStatus() != ExpenseVoucher.VoucherStatus.PENDING)
            throw new BusinessException("Phiếu đã được xử lý rồi");

        User approver = userRepository.findById(approverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        assertCanApprove(approver, voucher);

        String approverName = approver.getFullName() != null ? approver.getFullName() : approver.getUsername();
        voucher.setStatus(ExpenseVoucher.VoucherStatus.REJECTED);
        voucher.setApprovedBy(approver);
        voucher.setApprovedByName(approverName);
        voucher.setApprovedAt(System.currentTimeMillis());
        voucher.setRejectReason(reason);
        voucherRepo.save(voucher);

        writeLog(voucher, "REJECTED", approver,
                ExpenseVoucher.VoucherStatus.PENDING.name(),
                ExpenseVoucher.VoucherStatus.REJECTED.name(), reason);

        String payload = "{\"voucherId\":" + id + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
        String msg = "Phiếu chi [" + voucher.getVoucherCode() + "] đã bị " + approverName + " TỪ CHỐI. Lý do: " + reason;
        notificationService.sendToUser(voucher.getCreatedBy(), "EXPENSE_REJECTED", msg, payload);
        if (voucher.getRequestedBy() != null &&
                voucher.getRequestedBy().getId() != voucher.getCreatedBy().getId()) {
            notificationService.sendToUser(voucher.getRequestedBy(), "EXPENSE_REJECTED", msg, payload);
        }
        return toDto(voucher);
    }

    /**
     * OWNER/ADMIN duyệt được mọi phiếu. SUPER_ACCOUNTANT chỉ duyệt phiếu scope
     * SUPER_ACCOUNTANT. Còn lại không được.
     *
     * <p>LƯU Ý: hệ thống hỗ trợ ĐA VAI TRÒ (User.role chính + User.roles phụ, JWT có
     * selected_role). Vì vậy phải xét TOÀN BỘ vai trò của user — nếu chỉ đọc
     * {@code getRole()} thì một người có vai trò phụ là OWNER/SUPER_ACCOUNTANT sẽ bị
     * từ chối oan dù @PreAuthorize đã cho qua.
     */
    private void assertCanApprove(User approver, ExpenseVoucher voucher) {
        Set<Role> roles = approver.getAllRoles();
        if (roles.contains(Role.OWNER) || roles.contains(Role.ADMIN) || roles.contains(Role.SUPERADMIN)) return;
        if (roles.contains(Role.SUPER_ACCOUNTANT)
                && voucher.getApproverScope() == ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT) return;
        throw new BusinessException("Bạn không có quyền duyệt phiếu chi này");
    }

    /**
     * Vai trò ĐANG CHỌN của user (JWT selected_role → authorities). Nếu không xác định
     * được thì lấy vai trò chính. Dùng khi tạo phiếu để luật tự duyệt & snapshot
     * createdByRole khớp với vai trò người dùng đang thao tác trên giao diện.
     */
    private Role effectiveRoleOf(User user) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.getAuthorities() != null) {
                Set<Role> owned = user.getAllRoles();
                for (var ga : auth.getAuthorities()) {
                    String a = ga.getAuthority();
                    if (a == null || !a.startsWith("ROLE_")) continue;
                    try {
                        Role r = Role.valueOf(a.substring(5));
                        if (owned.contains(r)) return r;
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return user.getRole();
    }

    // ── Sửa lý do phiếu chi ──────────────────────────────────────────────────

    @Override
    @Transactional
    public ExpenseVoucherDto updateReason(Long id, Long editorUserId, String newReason) {
        ExpenseVoucher v = voucherRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu chi"));
        if (v.getStatus() == ExpenseVoucher.VoucherStatus.REJECTED) {
            throw new BusinessException("Phiếu chi đã bị từ chối, không thể sửa lý do");
        }
        if (newReason == null || newReason.isBlank()) {
            throw new BusinessException("Lý do chi không được để trống");
        }
        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        String oldReason = v.getReason();
        v.setReason(newReason.trim());
        ExpenseVoucher saved = voucherRepo.save(v);

        writeLog(v, "REASON_UPDATED", editor, null, null,
                "\"" + oldReason + "\" → \"" + newReason.trim() + "\"");

        // WS noti cho SUPER_ACCOUNTANT, OWNER, ADMIN
        String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
        String msg = editorName + " đã sửa lý do phiếu chi " + v.getVoucherCode()
                + ": \"" + newReason.trim() + "\"";
        String payload = "{\"expenseVoucherId\":" + id + ",\"voucherCode\":\"" + v.getVoucherCode() + "\"}";
        notificationService.sendToRole("SUPER_ACCOUNTANT", "EXPENSE_REASON_UPDATED", msg, payload);
        notificationService.sendToRole("OWNER", "EXPENSE_REASON_UPDATED", msg, payload);
        notificationService.sendToRole("ADMIN", "EXPENSE_REASON_UPDATED", msg, payload);

        return toDto(saved);
    }

    // ── Sửa các khoản chi ────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>Ngữ nghĩa THAY THẾ TOÀN BỘ:</b> {@code req.items} là danh sách khoản chi
     * sau khi sửa — có id thì cập nhật, không id thì thêm mới, khoản cũ không có mặt
     * trong danh sách thì bị xoá khỏi phiếu (orphanRemoval).
     *
     * <p>Ma trận quyền:
     * <pre>
     *                    CHỜ DUYỆT                          ĐÃ DUYỆT              ĐÃ TỪ CHỐI
     *  ACCOUNTANT      nhãn + tiền + thêm/xoá        ✗ (chỉ sửa lý do chi)            ✗
     *  SUPER_ACCOUNT.  nhãn + tiền + thêm/xoá        ✗ (chỉ sửa lý do chi)            ✗
     *  OWNER / ADMIN   nhãn + tiền + thêm/xoá        nhãn + tiền + thêm/xoá           ✗
     * </pre>
     *
     * <p>Sau khi sửa, {@code approverScope} được TÍNH LẠI theo tổng tiền mới
     * ({@link #evaluateSaCanApprove}). Nhờ vậy một phiếu ban đầu vượt ngưỡng
     * (scope = OWNER) sau khi xoá bớt khoản chi sẽ tụt xuống scope SUPER_ACCOUNTANT
     * và SUPER_ACCOUNTANT duyệt được — và ngược lại, thêm khoản chi làm vượt ngưỡng
     * thì phiếu tự chuyển về scope OWNER.
     */
    @Override
    @Transactional
    public ExpenseVoucherDto updateItems(Long id, Long editorUserId,
                                         com.nhatnam.server.dto.expense.UpdateExpenseItemsRequest req) {
        ExpenseVoucher v = voucherRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiếu chi"));

        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException("Phiếu chi phải còn ít nhất 1 khoản chi");
        }

        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        Set<Role> roles = editor.getAllRoles();
        boolean isOwnerAdmin = roles.contains(Role.OWNER) || roles.contains(Role.ADMIN)
                || roles.contains(Role.SUPERADMIN);
        boolean isAccountant = roles.contains(Role.ACCOUNTANT) || roles.contains(Role.SUPER_ACCOUNTANT);

        // ── 1. Kiểm tra quyền theo trạng thái phiếu ──────────────────────────
        ExpenseVoucher.VoucherStatus status = v.getStatus();
        if (status == ExpenseVoucher.VoucherStatus.REJECTED) {
            throw new BusinessException("Phiếu chi đã bị từ chối, không thể sửa khoản chi");
        }
        if (!isOwnerAdmin) {
            if (!isAccountant) {
                throw new BusinessException("Bạn không có quyền sửa khoản chi của phiếu này");
            }
            if (status != ExpenseVoucher.VoucherStatus.PENDING) {
                throw new BusinessException("Phiếu đã duyệt — bạn chỉ được sửa lý do chi. "
                        + "Liên hệ Owner/Admin để sửa khoản chi.");
            }
        }

        // ── 2. Nạp danh mục khoản chi đang bật (không cho tạo nhãn mới ở đây) ─
        java.util.Map<Long, com.nhatnam.server.entity.VendorExpenseCategory> categoryById =
                new java.util.HashMap<>();
        categoryRepo.findByActiveTrueOrderByNameAsc().forEach(c -> categoryById.put(c.getId(), c));

        java.util.Map<Long, ExpenseItem> existingById = new java.util.HashMap<>();
        for (ExpenseItem it : v.getItems()) {
            if (it.getId() != null) existingById.put(it.getId(), it);
        }

        BigDecimal oldTotal = v.getItems().stream()
                .map(ExpenseItem::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 3. Dựng danh sách khoản chi MỚI ──────────────────────────────────
        List<ExpenseItem> result = new ArrayList<>();
        java.util.Set<Long> keptIds = new java.util.HashSet<>();
        List<String> changeLog = new ArrayList<>();

        for (var p : req.getItems()) {
            if (p.getCategoryId() == null) {
                throw new BusinessException("Mỗi khoản chi phải chọn một nhãn từ danh mục khoản chi");
            }
            var cat = categoryById.get(p.getCategoryId());
            if (cat == null) {
                throw new BusinessException("Nhãn khoản chi không hợp lệ hoặc đã bị ẩn (id="
                        + p.getCategoryId() + ")");
            }
            if (p.getAmount() == null || p.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Số tiền của \"" + cat.getName() + "\" phải lớn hơn 0");
            }
            String note = (p.getNote() == null || p.getNote().isBlank()) ? null : p.getNote().trim();

            if (p.getId() == null) {
                // Thêm khoản chi mới
                result.add(ExpenseItem.builder()
                        .voucher(v)
                        .itemName(cat.getName())
                        .categoryId(cat.getId())
                        .amount(p.getAmount())
                        .note(note)
                        .build());
                changeLog.add("thêm \"" + cat.getName() + "\" ("
                        + p.getAmount().toPlainString() + ")");
                continue;
            }

            ExpenseItem item = existingById.get(p.getId());
            if (item == null) {
                throw new BusinessException("Khoản chi (id=" + p.getId()
                        + ") không thuộc phiếu chi này");
            }
            keptIds.add(p.getId());

            if (!cat.getId().equals(item.getCategoryId())) {
                changeLog.add("\"" + item.getItemName() + "\" → \"" + cat.getName() + "\"");
                item.setCategoryId(cat.getId());
                item.setItemName(cat.getName());
            }
            if (item.getAmount() == null || p.getAmount().compareTo(item.getAmount()) != 0) {
                changeLog.add(cat.getName() + ": "
                        + (item.getAmount() == null ? "0" : item.getAmount().toPlainString())
                        + " → " + p.getAmount().toPlainString());
                item.setAmount(p.getAmount());
            }
            item.setNote(note);
            result.add(item);
        }

        // Khoản chi cũ không còn trong danh sách → bị xoá
        for (ExpenseItem old : existingById.values()) {
            if (!keptIds.contains(old.getId())) {
                changeLog.add("xoá \"" + old.getItemName() + "\"");
            }
        }

        // orphanRemoval = true nên phải MUTATE chính collection đang được quản lý,
        // không được gán collection mới (Hibernate sẽ báo lỗi).
        v.getItems().clear();
        v.getItems().addAll(result);

        // ── 4. TÍNH LẠI cấp duyệt theo tổng tiền mới ─────────────────────────
        BigDecimal newTotal = result.stream()
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ExpenseVoucher.ApproverScope oldScope = v.getApproverScope();
        ExpenseApprovalConfig cfg = loadConfigEntity();
        boolean saCanApprove = evaluateSaCanApprove(cfg, newTotal, v.getVendorType());
        ExpenseVoucher.ApproverScope newScope = saCanApprove
                ? ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                : ExpenseVoucher.ApproverScope.OWNER;
        v.setApproverScope(newScope);

        ExpenseVoucher saved = voucherRepo.save(v);

        if (!changeLog.isEmpty()) {
            writeLog(v, "ITEMS_UPDATED", editor, null, null,
                    String.join("; ", changeLog)
                            + (oldTotal.compareTo(newTotal) != 0
                            ? ". Tổng tiền: " + oldTotal.toPlainString()
                            + " → " + newTotal.toPlainString()
                            : ""));
        }

        // ── 5. Thông báo ─────────────────────────────────────────────────────
        if (!changeLog.isEmpty()) {
            String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
            StringBuilder msg = new StringBuilder()
                    .append(editorName).append(" đã sửa khoản chi của phiếu ")
                    .append(v.getVoucherCode()).append(": ")
                    .append(String.join("; ", changeLog));
            if (oldTotal.compareTo(newTotal) != 0) {
                msg.append(". Tổng tiền: ").append(oldTotal.toPlainString())
                        .append(" → ").append(newTotal.toPlainString());
            }
            if (status == ExpenseVoucher.VoucherStatus.PENDING && oldScope != newScope) {
                msg.append(". Cấp duyệt: ")
                        .append(newScope == ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                                ? "Kế toán trưởng duyệt được" : "cần Owner/Admin duyệt");
            }
            String payload = "{\"expenseVoucherId\":" + id
                    + ",\"voucherCode\":\"" + v.getVoucherCode() + "\"}";
            notificationService.sendToRole("SUPER_ACCOUNTANT", "EXPENSE_ITEMS_UPDATED", msg.toString(), payload);
            notificationService.sendToRole("OWNER", "EXPENSE_ITEMS_UPDATED", msg.toString(), payload);
            notificationService.sendToRole("ADMIN", "EXPENSE_ITEMS_UPDATED", msg.toString(), payload);
        }

        return toDto(saved);
    }


    // ── Cấu hình duyệt ────────────────────────────────────────────────────────
    @Override
    public ExpenseApprovalConfigDto getApprovalConfig() {
        return toConfigDto(loadConfigEntity());
    }

    @Override
    @Transactional
    public ExpenseApprovalConfigDto updateApprovalConfig(Long editorUserId, ExpenseApprovalConfigDto dto) {
        User editor = userRepository.findById(editorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        ExpenseApprovalConfig cfg = loadConfigEntity();
        if (dto.getThresholdAmount() != null) {
            if (dto.getThresholdAmount().compareTo(BigDecimal.ZERO) < 0)
                throw new BusinessException("Ngưỡng số tiền không hợp lệ");
            cfg.setThresholdAmount(dto.getThresholdAmount());
        }
        if (dto.getAllowedCategories() != null) {
            try { cfg.setAllowedCategories(objectMapper.writeValueAsString(dto.getAllowedCategories())); }
            catch (Exception e) { throw new BusinessException("Danh mục không hợp lệ"); }
        }
        cfg.setUpdatedAt(System.currentTimeMillis());
        cfg.setUpdatedByName(editor.getFullName() != null ? editor.getFullName() : editor.getUsername());
        approvalConfigRepo.save(cfg);

        // ── ÁP CẤU HÌNH MỚI CHO CÁC PHIẾU ĐANG CHỜ DUYỆT ─────────────────────
        // approverScope được "đóng băng" lúc tạo phiếu, nên nếu không làm bước này
        // thì đổi ngưỡng/danh mục sẽ chỉ ảnh hưởng phiếu tạo về sau — phiếu cũ vẫn
        // kẹt ở cấp duyệt tính theo cấu hình lúc trước.
        //
        // CHỈ đụng vào phiếu PENDING: phiếu đã duyệt/từ chối là dữ liệu lịch sử,
        // sửa cấp duyệt của chúng sẽ làm sai vết ai đã duyệt theo luật nào.
        //
        // Hiệu năng: đọc bằng projection (1 query, tổng tiền tính bằng SUM trong SQL)
        // và ghi bằng 2 câu UPDATE gom theo cấp duyệt — không nạp entity, không N+1.
        long now = System.currentTimeMillis();
        List<Long> toSaIds = new ArrayList<>();
        List<Long> toOwnerIds = new ArrayList<>();

        for (var row : voucherRepo.findScopeRecalcRows(ExpenseVoucher.VoucherStatus.PENDING)) {
            BigDecimal total = row.getTotalAmount() != null ? row.getTotalAmount() : BigDecimal.ZERO;
            ExpenseVoucher.ApproverScope newScope =
                    evaluateSaCanApprove(cfg, total, row.getVendorType())
                            ? ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT
                            : ExpenseVoucher.ApproverScope.OWNER;

            if (newScope == row.getApproverScope()) continue;   // không đổi thì bỏ qua
            if (newScope == ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT) toSaIds.add(row.getId());
            else toOwnerIds.add(row.getId());
        }

        int movedToSa = applyScope(toSaIds, ExpenseVoucher.ApproverScope.SUPER_ACCOUNTANT, now);
        int movedToOwner = applyScope(toOwnerIds, ExpenseVoucher.ApproverScope.OWNER, now);

        if (movedToSa > 0 || movedToOwner > 0) {
            String editorName = editor.getFullName() != null ? editor.getFullName() : editor.getUsername();
            StringBuilder msg = new StringBuilder(editorName)
                    .append(" đã đổi cấu hình duyệt phiếu chi. Cập nhật cấp duyệt cho phiếu đang chờ: ");
            if (movedToSa > 0) msg.append(movedToSa).append(" phiếu chuyển sang Kế toán trưởng duyệt được");
            if (movedToSa > 0 && movedToOwner > 0) msg.append("; ");
            if (movedToOwner > 0) msg.append(movedToOwner).append(" phiếu chuyển về cần Chủ/Quản trị duyệt");
            msg.append(".");

            String payload = "{\"movedToSuperAccountant\":" + movedToSa
                    + ",\"movedToOwner\":" + movedToOwner + "}";
            notificationService.sendToRole("SUPER_ACCOUNTANT", "EXPENSE_APPROVAL_CONFIG_CHANGED",
                    msg.toString(), payload);
            notificationService.sendToRole("OWNER", "EXPENSE_APPROVAL_CONFIG_CHANGED",
                    msg.toString(), payload);
            notificationService.sendToRole("ADMIN", "EXPENSE_APPROVAL_CONFIG_CHANGED",
                    msg.toString(), payload);
            log.info("Đổi cấu hình duyệt phiếu chi: {} phiếu → SUPER_ACCOUNTANT, {} phiếu → OWNER",
                    movedToSa, movedToOwner);
        }

        ExpenseApprovalConfigDto out = toConfigDto(cfg);
        out.setRescopedToSuperAccountant(movedToSa);
        out.setRescopedToOwner(movedToOwner);
        return out;
    }

    /**
     * Ghi cấp duyệt mới cho một danh sách id bằng UPDATE gom lô.
     *
     * <p>Chia lô {@value #SCOPE_UPDATE_BATCH} id mỗi câu để mệnh đề {@code IN (...)}
     * không vượt giới hạn tham số của driver khi số phiếu chờ duyệt lớn.
     *
     * @return số phiếu thực sự được cập nhật
     */
    private int applyScope(List<Long> ids, ExpenseVoucher.ApproverScope scope, long now) {
        if (ids.isEmpty()) return 0;
        int updated = 0;
        for (int i = 0; i < ids.size(); i += SCOPE_UPDATE_BATCH) {
            List<Long> batch = ids.subList(i, Math.min(i + SCOPE_UPDATE_BATCH, ids.size()));
            updated += voucherRepo.updateApproverScopeByIds(batch, scope, now);
        }
        return updated;
    }

    private ExpenseApprovalConfigDto toConfigDto(ExpenseApprovalConfig c) {
        ExpenseApprovalConfigDto d = new ExpenseApprovalConfigDto();
        d.setThresholdAmount(c.getThresholdAmount());
        d.setAllowedCategories(parseCategories(c.getAllowedCategories()));
        d.setUpdatedAt(c.getUpdatedAt());
        d.setUpdatedByName(c.getUpdatedByName());
        return d;
    }

    // ── Số phiếu chi ─────────────────────────────────────────────────────────
    private static final long PAYMENT_MAX = 15000L;

    @Override
    public String suggestNextPaymentNumber() {
        String latest = voucherRepo.findLatestPaymentNumber();
        if (latest == null) return "1";
        String digitsOnly = latest.replaceAll("[^0-9]", "");
        if (digitsOnly.isBlank()) return "1";
        try {
            long n = Long.parseLong(digitsOnly);
            if (n >= PAYMENT_MAX || n < 1) return "1";
            return String.valueOf(n + 1);
        } catch (NumberFormatException ignored) {
            return "1";
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private ExpenseVoucher findOrThrow(Long id) {
        return voucherRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu chi không tồn tại: " + id));
    }

    private static final java.time.ZoneId VN_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    private String periodOf(long epochMs) {
        return java.time.YearMonth.from(
                        java.time.Instant.ofEpochMilli(epochMs).atZone(VN_ZONE))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private long startOfTodayMs() {
        return java.time.LocalDate.now(VN_ZONE).atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
    }

    /** 00:00 (giờ VN) của ngày mà epochMs rơi vào. */
    private long startOfVnDayMs(long epochMs) {
        return java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), VN_ZONE)
                .atStartOfDay(VN_ZONE).toInstant().toEpochMilli();
    }

    /** dd/MM/yyyy theo giờ VN — dùng cho thông báo lỗi. */
    private String formatVnDate(long epochMs) {
        return java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), VN_ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** Hai mốc có cùng một ngày theo giờ VN? */
    private boolean isSameVnDay(long a, long b) {
        return java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(a), VN_ZONE)
                .equals(java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(b), VN_ZONE));
    }

    /** 08:00 (giờ VN) của ngày mà epochMs rơi vào. */
    private long at8amVnOf(long epochMs) {
        return java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), VN_ZONE)
                .atTime(8, 0).atZone(VN_ZONE).toInstant().toEpochMilli();
    }

    private String generateCode() {
        String date = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String rand = String.format("%04d", new Random().nextInt(10000));
        String code = "EV-" + date + "-" + rand;
        while (voucherRepo.existsByVoucherCode(code)) {
            rand = String.format("%04d", new Random().nextInt(10000));
            code = "EV-" + date + "-" + rand;
        }
        return code;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseImageUrls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private ExpenseVoucherDto toDto(ExpenseVoucher v) {
        BigDecimal total = v.getItems().stream()
                .map(ExpenseItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ExpenseVoucherDto.ExpenseItemDto> itemDtos = v.getItems().stream()
                .map(i -> ExpenseVoucherDto.ExpenseItemDto.builder()
                        .id(i.getId())
                        .itemName(i.getItemName())
                        .categoryId(i.getCategoryId())
                        .amount(i.getAmount())
                        .note(i.getNote())
                        .build())
                .toList();

        return ExpenseVoucherDto.builder()
                .id(v.getId())
                .voucherCode(v.getVoucherCode())
                .paymentNumber(v.getPaymentNumber())
                .vendorName(v.getVendorName())
                .vendorId(v.getVendorId())
                .vendorType(v.getVendorType())
                .reason(v.getReason())
                .expensePeriod(v.getExpensePeriod())
                .expenseDate(v.getExpenseDate())
                .paymentType(v.getPaymentType() != null ? v.getPaymentType().name() : "CASH")
                .bankName(v.getBankName())
                .bankRef(v.getBankRef())
                .customerBankName(v.getCustomerBankName())
                .customerBankAccount(v.getCustomerBankAccount())
                .customerBankHolder(v.getCustomerBankHolder())
                .createdByName(v.getCreatedByName())
                .createdByRole(v.getCreatedByRole())
                .requestedByName(v.getRequestedByName())
                .createdById(v.getCreatedBy() != null ? v.getCreatedBy().getId() : null)
                .status(v.getStatus().name())
                .approverScope(v.getApproverScope() != null ? v.getApproverScope().name() : "OWNER")
                .approvedByName(v.getApprovedByName())
                .approvedById(v.getApprovedBy() != null ? v.getApprovedBy().getId() : null)
                .approvedAt(v.getApprovedAt())
                .rejectReason(v.getRejectReason())
                .items(itemDtos)
                .totalAmount(total)
                .imageUrls(parseImageUrls(v.getImageUrls()))
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }
}