package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.vendordebt.VendorDebtDtos;
import com.nhatnam.server.dto.vendordebt.VendorDebtDtos.*;
import com.nhatnam.server.entity.*;
import com.nhatnam.server.entity.MaterialRequestVendor.DebtSettlementStatus;
import com.nhatnam.server.entity.MaterialRequestVendor.VendorPaymentStatus;
import com.nhatnam.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Công nợ nhà cung cấp (NGUYÊN LIỆU) + Phiếu chi trả công nợ.
 *
 * Công nợ được ghi nhận trên {@link MaterialRequestVendor} khi kế toán chọn
 * "Công nợ" lúc Hoàn thành phiếu đặt hàng (xem MaterialRequestService#complete).
 * Trang này tổng hợp công nợ theo {@link MaterialVendor} và cho phép tạo
 * {@link VendorExpenseVoucher} để trả dần (FIFO theo debtSince).
 */
@Service
@RequiredArgsConstructor
public class VendorDebtService {

    private final MaterialRequestVendorRepository requestVendorRepo;
    private final MaterialVendorRepository materialVendorRepo;
    private final VendorExpenseVoucherRepository voucherRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // ── Danh sách công nợ theo NCC (cho trang Owner) ────────────────────────

    public List<VendorDebtSummaryDto> listVendorDebts(String sortBy) {
        List<Long> vendorIds = requestVendorRepo.findVendorIdsWithOutstandingDebt();
        if (vendorIds.isEmpty()) return List.of();

        List<VendorDebtSummaryDto> result = new ArrayList<>();
        for (Long vendorId : vendorIds) {
            MaterialVendor vendor = materialVendorRepo.findById(vendorId).orElse(null);
            if (vendor == null) continue;

            List<MaterialRequestVendor> debts = requestVendorRepo
                    .findUnsettledDebtsByVendorOrderByDebtSinceAsc(vendorId);
            if (debts.isEmpty()) continue;

            BigDecimal total = debts.stream()
                    .map(this::remainingOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Long oldest = debts.get(0).getDebtSince(); // đã sort ASC -> đầu tiên là lâu nhất

            result.add(VendorDebtSummaryDto.builder()
                    .vendorId(vendor.getId())
                    .vendorName(vendor.getName())
                    .vendorType(vendor.getVendorType() != null ? vendor.getVendorType().name() : null)
                    .contactPerson(vendor.getContactPerson())
                    .contactPhone(vendor.getContactPhone())
                    .totalDebt(total)
                    .unsettledRequestCount(debts.size())
                    .oldestDebtSince(oldest)
                    .build());
        }

        Comparator<VendorDebtSummaryDto> cmp = "amount".equalsIgnoreCase(sortBy)
                ? Comparator.comparing(VendorDebtSummaryDto::getTotalDebt).reversed()
                : Comparator.comparing(VendorDebtSummaryDto::getOldestDebtSince,
                        Comparator.nullsLast(Comparator.naturalOrder())); // oldest = lâu nhất trước
        result.sort(cmp);
        return result;
    }

    public List<VendorDebtDetailDto> getVendorDebtHistory(Long vendorId) {
        materialVendorRepo.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Nhà cung cấp không tồn tại"));

        List<MaterialRequestVendor> history = requestVendorRepo.findAllDebtHistoryByVendor(vendorId);
        return history.stream().map(rv -> VendorDebtDetailDto.builder()
                        .requestVendorId(rv.getId())
                        .materialRequestId(rv.getMaterialRequest().getId())
                        .requestCode(rv.getMaterialRequest().getRequestCode())
                        .totalAmount(rv.getTotalAmount())
                        .paidAmount(rv.getPaidAmount())
                        .remaining(remainingOf(rv))
                        .debtSettlementStatus(rv.getDebtSettlementStatus() != null ? rv.getDebtSettlementStatus().name() : null)
                        .debtSince(rv.getDebtSince())
                        .completedAt(rv.getMaterialRequest().getCompletedAt())
                        .build())
                .collect(Collectors.toList());
    }

    public BigDecimal getOutstandingDebt(Long vendorId) {
        return requestVendorRepo.findUnsettledDebtsByVendorOrderByDebtSinceAsc(vendorId).stream()
                .map(this::remainingOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── Tạo phiếu chi trả công nợ — trừ FIFO theo debtSince ─────────────────

    @Transactional
    public VendorExpenseVoucherDto createExpense(CreateVendorExpenseRequest req, String username) {
        if (req.getVendorId() == null) throw new BusinessException("Vui lòng chọn nhà cung cấp");
        MaterialVendor vendor = materialVendorRepo.findById(req.getVendorId())
                .orElseThrow(() -> new ResourceNotFoundException("Nhà cung cấp không tồn tại"));

        User creator = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User không tồn tại"));

        List<MaterialRequestVendor> debts = requestVendorRepo
                .findUnsettledDebtsByVendorOrderByDebtSinceAsc(vendor.getId());
        BigDecimal totalDebt = debts.stream().map(this::remainingOf).reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDebt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Nhà cung cấp này không có công nợ");
        }

        BigDecimal amount = req.isFullSettlement() ? totalDebt : req.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Số tiền chi phải lớn hơn 0");
        }
        if (amount.compareTo(totalDebt) > 0) {
            throw new BusinessException("Số tiền chi (" + amount + ") vượt quá tổng công nợ hiện tại (" + totalDebt + ")");
        }
        if (req.getProofImages() == null || req.getProofImages().isEmpty()) {
            throw new BusinessException("Bắt buộc ít nhất 1 ảnh chứng từ thanh toán");
        }

        String creatorName = creator.getFullName() != null && !creator.getFullName().isBlank()
                ? creator.getFullName() : creator.getUsername();

        VendorExpenseVoucher voucher = VendorExpenseVoucher.builder()
                .voucherCode(generateCode())
                .vendor(vendor)
                .vendorName(vendor.getName())
                .totalAmount(amount)
                .fullSettlement(req.isFullSettlement())
                .note(req.getNote())
                .proofImages(serializeStringList(req.getProofImages()))
                .createdBy(creator)
                .createdByName(creatorName)
                .allocations(new ArrayList<>())
                .build();
        voucher = voucherRepo.save(voucher);

        // Trừ FIFO: phiếu có debtSince nhỏ nhất (lâu nhất) trước
        BigDecimal remaining = amount;
        for (MaterialRequestVendor rv : debts) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal debtRemainingOfThis = remainingOf(rv);
            if (debtRemainingOfThis.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal take = remaining.min(debtRemainingOfThis);
            BigDecimal newPaid = (rv.getPaidAmount() != null ? rv.getPaidAmount() : BigDecimal.ZERO).add(take);
            rv.setPaidAmount(newPaid);

            BigDecimal afterRemaining = rv.getTotalAmount().subtract(newPaid);
            rv.setDebtSettlementStatus(afterRemaining.compareTo(BigDecimal.ZERO) <= 0
                    ? DebtSettlementStatus.SETTLED : DebtSettlementStatus.PARTIAL);
            requestVendorRepo.save(rv);

            voucher.getAllocations().add(VendorExpenseAllocation.builder()
                    .voucher(voucher)
                    .materialRequestVendor(rv)
                    .requestCode(rv.getMaterialRequest().getRequestCode())
                    .amount(take)
                    .remainingAfter(afterRemaining.max(BigDecimal.ZERO))
                    .build());

            remaining = remaining.subtract(take);
        }

        voucher = voucherRepo.save(voucher);

        notificationService.sendToRole(
                "OWNER", "VENDOR_EXPENSE_CREATED",
                creatorName + " đã tạo phiếu chi trả công nợ [" + voucher.getVoucherCode() + "] cho " + vendor.getName()
                        + " — " + amount,
                "{\"voucherId\":" + voucher.getId() + "}"
        );

        return toDto(voucher);
    }

    public Page<VendorExpenseVoucherDto> listExpenses(Long vendorId, String search, Pageable pageable) {
        Page<VendorExpenseVoucher> page = voucherRepo.findByFilters(vendorId, search, pageable);
        return page.map(this::toDto);
    }

    public VendorExpenseVoucherDto getExpenseById(Long id) {
        VendorExpenseVoucher v = voucherRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Phiếu chi không tồn tại"));
        return toDto(v);
    }

    // ── Dùng để gộp vào trang "Phiếu chi" chung (ExpenseVoucherServiceImpl) ──

    /** Tất cả phiếu chi trả công nợ NCC trong khoảng ngày, dạng ExpenseVoucherDto (voucherType=VENDOR_DEBT_PAYMENT) */
    public List<com.nhatnam.server.dto.expense.ExpenseVoucherDto> listVendorPaymentsAsExpenseDto(Long from, Long to) {
        return voucherRepo.findByDateRangeNoPaging(from, to).stream()
                .map(this::toExpenseDto)
                .collect(Collectors.toList());
    }

    /** Tìm kiếm phiếu chi trả công nợ NCC (kèm hoặc không kèm khoảng ngày), dạng ExpenseVoucherDto */
    public List<com.nhatnam.server.dto.expense.ExpenseVoucherDto> searchVendorPaymentsAsExpenseDto(String q, Long from, Long to) {
        List<VendorExpenseVoucher> list = (from != null && to != null)
                ? voucherRepo.searchWithDateRangeNoPaging(q, from, to)
                : voucherRepo.searchAllNoPaging(q);
        return list.stream().map(this::toExpenseDto).collect(Collectors.toList());
    }

    private com.nhatnam.server.dto.expense.ExpenseVoucherDto toExpenseDto(VendorExpenseVoucher v) {
        String reason = "Thanh toán công nợ nhà cung cấp" + (v.isFullSettlement() ? " (thanh toán hết)" : " (thanh toán 1 phần)");
        if (v.getNote() != null && !v.getNote().isBlank()) reason += " — " + v.getNote();
        return com.nhatnam.server.dto.expense.ExpenseVoucherDto.builder()
                .id(v.getId())
                .voucherCode(v.getVoucherCode())
                .vendorName(v.getVendorName())
                .reason(reason)
                .createdByName(v.getCreatedByName())
                .createdById(v.getCreatedBy() != null ? v.getCreatedBy().getId() : null)
                .status("APPROVED") // phiếu trả công nợ không qua duyệt — coi như đã hoàn tất ngay
                .totalAmount(v.getTotalAmount())
                .imageUrls(deserializeStringList(v.getProofImages()))
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getCreatedAt())
                .voucherType("VENDOR_DEBT_PAYMENT")
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BigDecimal remainingOf(MaterialRequestVendor rv) {
        if (rv.getTotalAmount() == null) return BigDecimal.ZERO;
        BigDecimal paid = rv.getPaidAmount() != null ? rv.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal r = rv.getTotalAmount().subtract(paid);
        return r.compareTo(BigDecimal.ZERO) > 0 ? r : BigDecimal.ZERO;
    }

    private String generateCode() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        long count = voucherRepo.countByVoucherCodeStartingWith("VEV-" + date);
        return String.format("VEV-%s-%04d", date, count + 1);
    }

    private VendorExpenseVoucherDto toDto(VendorExpenseVoucher v) {
        return VendorExpenseVoucherDto.builder()
                .id(v.getId())
                .voucherCode(v.getVoucherCode())
                .vendorId(v.getVendor() != null ? v.getVendor().getId() : null)
                .vendorName(v.getVendorName())
                .totalAmount(v.getTotalAmount())
                .fullSettlement(v.isFullSettlement())
                .note(v.getNote())
                .proofImages(deserializeStringList(v.getProofImages()))
                .createdByName(v.getCreatedByName())
                .createdAt(v.getCreatedAt())
                .allocations(v.getAllocations().stream().map(a -> AllocationDto.builder()
                                .materialRequestVendorId(a.getMaterialRequestVendor().getId())
                                .requestCode(a.getRequestCode())
                                .amount(a.getAmount())
                                .remainingAfter(a.getRemainingAfter())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private String serializeStringList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
