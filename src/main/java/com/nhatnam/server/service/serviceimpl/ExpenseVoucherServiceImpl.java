package com.nhatnam.server.service.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.expense.CreateExpenseVoucherRequest;
import com.nhatnam.server.dto.expense.ExpenseVoucherDto;
import com.nhatnam.server.entity.ExpenseItem;
import com.nhatnam.server.entity.ExpenseVoucher;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.ExpenseVoucherRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.ExpenseVoucherService;
import com.nhatnam.server.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpenseVoucherServiceImpl implements ExpenseVoucherService {

    private final ExpenseVoucherRepository voucherRepo;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ExpenseVoucherDto create(Long createdByUserId, CreateExpenseVoucherRequest req) {
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        String creatorName = creator.getFullName() != null && !creator.getFullName().isBlank()
                ? creator.getFullName() : creator.getUsername();

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

        ExpenseVoucher voucher = ExpenseVoucher.builder()
                .voucherCode(generateCode())
                .vendorName(req.getVendorName())
                .reason(req.getReason())
                .createdByName(creatorName)
                .createdBy(creator)
                .requestedByName(requestedByName)
                .requestedBy(requestedByUser)
                .status(ExpenseVoucher.VoucherStatus.PENDING)
                .imageUrls(imageUrlsJson)
                .items(new ArrayList<>())
                .build();

        voucher = voucherRepo.save(voucher);

        for (CreateExpenseVoucherRequest.ExpenseItemRequest itemReq : req.getItems()) {
            ExpenseItem item = ExpenseItem.builder()
                    .voucher(voucher)
                    .itemName(itemReq.getItemName())
                    .amount(itemReq.getAmount())
                    .note(itemReq.getNote())
                    .build();
            voucher.getItems().add(item);
        }
        voucher = voucherRepo.save(voucher);

        String payload = "{\"voucherId\":" + voucher.getId() + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
        String msg = creatorName + " đã tạo phiếu chi [" + voucher.getVoucherCode() + "] cần duyệt. Lý do: " + req.getReason();
        notificationService.sendToRole("ADMIN", "EXPENSE_PENDING", msg, payload);
        notificationService.sendToRole("OWNER", "EXPENSE_PENDING", msg, payload);

        return toDto(voucher);
    }

    @Override
    public ExpenseVoucherDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Override
    public PageResponse<ExpenseVoucherDto> listForCreator(Long userId, Pageable pageable) {
        Page<ExpenseVoucher> page = voucherRepo.findByCreatedByIdOrderByCreatedAtDesc(userId, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toDto).toList());
    }

    @Override
    public PageResponse<ExpenseVoucherDto> listAll(Pageable pageable) {
        Page<ExpenseVoucher> page = voucherRepo.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toDto).toList());
    }

    @Override
    public PageResponse<ExpenseVoucherDto> listByDateRange(Long from, Long to, Pageable pageable) {
        Page<ExpenseVoucher> page = voucherRepo.findByDateRange(from, to, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toDto).toList());
    }

    @Override
    public PageResponse<ExpenseVoucherDto> search(String q, Long from, Long to, Pageable pageable) {
        Page<ExpenseVoucher> page = (from != null && to != null)
                ? voucherRepo.searchWithDateRange(q, from, to, pageable)
                : voucherRepo.searchAll(q, pageable);
        return PageResponse.from(page, page.getContent().stream().map(this::toDto).toList());
    }

    @Override
    @Transactional
    public ExpenseVoucherDto approve(Long id, Long approverUserId, String note) {
        ExpenseVoucher voucher = findOrThrow(id);
        if (voucher.getStatus() != ExpenseVoucher.VoucherStatus.PENDING)
            throw new BusinessException("Phiếu đã được xử lý rồi");

        User approver = userRepository.findById(approverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));
        String approverName = approver.getFullName() != null ? approver.getFullName() : approver.getUsername();

        voucher.setStatus(ExpenseVoucher.VoucherStatus.APPROVED);
        voucher.setApprovedBy(approver);
        voucher.setApprovedByName(approverName);
        voucher.setApprovedAt(System.currentTimeMillis());
        voucherRepo.save(voucher);

        String payload = "{\"voucherId\":" + id + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
        String msg = "Phiếu chi [" + voucher.getVoucherCode() + "] đã được " + approverName + " DUYỆT.";
        notificationService.sendToUser(voucher.getCreatedBy(), "EXPENSE_APPROVED", msg, payload);
        if (voucher.getRequestedBy() != null &&
                voucher.getRequestedBy().getId() != voucher.getCreatedBy().getId()) {
            notificationService.sendToUser(voucher.getRequestedBy(), "EXPENSE_APPROVED", msg, payload);
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
        String approverName = approver.getFullName() != null ? approver.getFullName() : approver.getUsername();

        voucher.setStatus(ExpenseVoucher.VoucherStatus.REJECTED);
        voucher.setApprovedBy(approver);
        voucher.setApprovedByName(approverName);
        voucher.setApprovedAt(System.currentTimeMillis());
        voucher.setRejectReason(reason);
        voucherRepo.save(voucher);

        String payload = "{\"voucherId\":" + id + ",\"voucherCode\":\"" + voucher.getVoucherCode() + "\"}";
        String msg = "Phiếu chi [" + voucher.getVoucherCode() + "] đã bị " + approverName + " TỪ CHỐI. Lý do: " + reason;
        notificationService.sendToUser(voucher.getCreatedBy(), "EXPENSE_REJECTED", msg, payload);
        if (voucher.getRequestedBy() != null &&
                voucher.getRequestedBy().getId() != voucher.getCreatedBy().getId()) {
            notificationService.sendToUser(voucher.getRequestedBy(), "EXPENSE_REJECTED", msg, payload);
        }
        return toDto(voucher);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ExpenseVoucher findOrThrow(Long id) {
        return voucherRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu chi không tồn tại: " + id));
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
                        .amount(i.getAmount())
                        .note(i.getNote())
                        .build())
                .toList();

        return ExpenseVoucherDto.builder()
                .id(v.getId())
                .voucherCode(v.getVoucherCode())
                .vendorName(v.getVendorName())
                .reason(v.getReason())
                .createdByName(v.getCreatedByName())
                .requestedByName(v.getRequestedByName())
                .createdById(v.getCreatedBy() != null ? v.getCreatedBy().getId() : null)
                .status(v.getStatus().name())
                .approvedByName(v.getApprovedByName())
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
