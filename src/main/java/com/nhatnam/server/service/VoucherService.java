package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.voucher.VoucherDtos.*;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.entity.Voucher;
import com.nhatnam.server.repository.CategoryRepository;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.ProductRepository;
import com.nhatnam.server.repository.VoucherRepository;
import com.nhatnam.server.utils.AnniversaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QUẢN LÝ VOUCHER TẶNG KHÁCH.
 *
 * <p>Tạo / sửa / thu hồi voucher, và resolve tên danh mục + sản phẩm để FE hiển thị
 * điều kiện áp dụng mà không phải gọi thêm API.
 *
 * <p><b>Chưa có phần TRỪ TIỀN khi thanh toán</b> — theo yêu cầu, phần đó làm sau.
 * Service này chỉ quản lý vòng đời voucher.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final CustomerRepository customerRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // bỏ I,O,0,1 cho dễ đọc tay

    // ── Đọc ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<VoucherDto> list(String q, Long customerId, String reason,
                                         String status, String effectiveStatus, Pageable pageable) {
        Voucher.VoucherReason reasonEnum = _parseEnum(Voucher.VoucherReason.class, reason);
        Voucher.VoucherStatus statusEnum = _parseEnum(Voucher.VoucherStatus.class, status);

        Page<Voucher> page = voucherRepository.search(
                (q == null || q.isBlank()) ? null : q.trim(), customerId, reasonEnum, statusEnum, pageable);

        long now = System.currentTimeMillis();
        List<VoucherDto> content = page.getContent().stream()
                .map(v -> toDto(v, now))
                .filter(d -> effectiveStatus == null || effectiveStatus.isBlank()
                        || effectiveStatus.equalsIgnoreCase(d.getEffectiveStatus()))
                .toList();

        return PageResponse.from(page, content);
    }

    @Transactional(readOnly = true)
    public VoucherDto getById(Long id) {
        return toDto(_findOrThrow(id), System.currentTimeMillis());
    }

    @Transactional(readOnly = true)
    public List<VoucherDto> listByCustomer(Long customerId) {
        long now = System.currentTimeMillis();
        return voucherRepository.findByCustomer_IdOrderByCreatedAtDesc(customerId).stream()
                .map(v -> toDto(v, now)).toList();
    }

    @Transactional(readOnly = true)
    public Voucher getEntity(Long id) {
        return _findOrThrow(id);
    }

    // ── Ghi ──────────────────────────────────────────────────────────────────

    @Transactional
    public VoucherDto create(CreateVoucherRequest req, User actor) {
        Customer customer = customerRepository.findById(req.getCustomerId())
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy khách hàng #" + req.getCustomerId()));

        _validateDateRange(req.getValidFrom(), req.getValidTo());
        Voucher.ApplyScope scope = _resolveScope(req.getApplyScope());
        _validateScope(scope, req.getCategoryIds(), req.getProductIds());

        Voucher.VoucherReason reason = Optional
                .ofNullable(_parseEnum(Voucher.VoucherReason.class, req.getReason()))
                .orElse(Voucher.VoucherReason.OTHER);

        _validateReasonAgainstCustomer(reason, customer);

        Voucher v = Voucher.builder()
                .code(_generateCode())
                .title(_blankToNull(req.getTitle()))
                .customer(customer)
                .amount(req.getAmount())
                .usedAmount(0L)
                .validFrom(req.getValidFrom())
                .validTo(req.getValidTo())
                .status(Voucher.VoucherStatus.ACTIVE)
                .reason(reason)
                .applyScope(scope)
                .categoryIds(scope == Voucher.ApplyScope.CATEGORY
                        ? new HashSet<>(req.getCategoryIds()) : new HashSet<>())
                .productIds(scope == Voucher.ApplyScope.PRODUCT
                        ? new HashSet<>(req.getProductIds()) : new HashSet<>())
                .note(_blankToNull(req.getNote()))
                .createdBy(actor)
                .createdByName(actor != null
                        ? (actor.getFullName() != null ? actor.getFullName() : actor.getUsername())
                        : null)
                .build();

        return toDto(voucherRepository.save(v), System.currentTimeMillis());
    }

    @Transactional
    public VoucherDto update(Long id, UpdateVoucherRequest req) {
        Voucher v = _findOrThrow(id);

        if (req.getCustomerId() != null && !req.getCustomerId().equals(
                v.getCustomer() != null ? v.getCustomer().getId() : null)) {
            Customer newCustomer = customerRepository.findById(req.getCustomerId())
                    .filter(c -> c.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Không tìm thấy khách hàng #" + req.getCustomerId()));
            v.setCustomer(newCustomer);
        }

        if (req.getTitle() != null) v.setTitle(_blankToNull(req.getTitle()));
        if (req.getNote() != null)  v.setNote(_blankToNull(req.getNote()));

        if (req.getAmount() != null) {
            if (req.getAmount() <= 0) throw new BusinessException("Hạn mức phải lớn hơn 0");
            // Không cho hạ hạn mức xuống dưới phần đã dùng — số dư sẽ thành âm.
            long used = v.getUsedAmount() != null ? v.getUsedAmount() : 0L;
            if (req.getAmount() < used)
                throw new BusinessException("Hạn mức mới nhỏ hơn số tiền đã sử dụng ("
                        + String.format("%,d", used).replace(',', '.') + " đ)");
            v.setAmount(req.getAmount());
        }

        Long from = req.getValidFrom() != null ? req.getValidFrom() : v.getValidFrom();
        Long to   = req.getValidTo()   != null ? req.getValidTo()   : v.getValidTo();
        if (req.getValidFrom() != null || req.getValidTo() != null) {
            _validateDateRange(from, to);
            v.setValidFrom(from);
            v.setValidTo(to);
        }

        if (req.getReason() != null) {
            Voucher.VoucherReason reason = _parseEnum(Voucher.VoucherReason.class, req.getReason());
            if (reason != null) {
                _validateReasonAgainstCustomer(reason, v.getCustomer());
                v.setReason(reason);
            }
        }

        if (req.getApplyScope() != null) {
            Voucher.ApplyScope scope = _resolveScope(req.getApplyScope());
            Set<Long> cats = req.getCategoryIds() != null ? req.getCategoryIds() : v.getCategoryIds();
            Set<Long> prods = req.getProductIds() != null ? req.getProductIds() : v.getProductIds();
            _validateScope(scope, cats, prods);
            v.setApplyScope(scope);
            v.setCategoryIds(scope == Voucher.ApplyScope.CATEGORY ? new HashSet<>(cats) : new HashSet<>());
            v.setProductIds(scope == Voucher.ApplyScope.PRODUCT ? new HashSet<>(prods) : new HashSet<>());
        } else {
            // Giữ nguyên scope nhưng cho phép sửa danh sách id trong scope đó.
            if (req.getCategoryIds() != null && v.getApplyScope() == Voucher.ApplyScope.CATEGORY) {
                _validateScope(Voucher.ApplyScope.CATEGORY, req.getCategoryIds(), null);
                v.setCategoryIds(new HashSet<>(req.getCategoryIds()));
            }
            if (req.getProductIds() != null && v.getApplyScope() == Voucher.ApplyScope.PRODUCT) {
                _validateScope(Voucher.ApplyScope.PRODUCT, null, req.getProductIds());
                v.setProductIds(new HashSet<>(req.getProductIds()));
            }
        }

        if (req.getStatus() != null) {
            Voucher.VoucherStatus st = _parseEnum(Voucher.VoucherStatus.class, req.getStatus());
            // Chỉ cho chuyển thủ công giữa ACTIVE ↔ CANCELLED. USED/EXPIRED là kết quả
            // tính động, đặt cứng vào DB sẽ mâu thuẫn với effectiveStatus.
            if (st == Voucher.VoucherStatus.ACTIVE || st == Voucher.VoucherStatus.CANCELLED)
                v.setStatus(st);
        }

        return toDto(voucherRepository.save(v), System.currentTimeMillis());
    }

    /** Thu hồi voucher (không xoá — vẫn cần lịch sử ai tặng ai, bao giờ). */
    @Transactional
    public VoucherDto cancel(Long id) {
        Voucher v = _findOrThrow(id);
        v.setStatus(Voucher.VoucherStatus.CANCELLED);
        return toDto(voucherRepository.save(v), System.currentTimeMillis());
    }

    @Transactional
    public void delete(Long id) {
        voucherRepository.delete(_findOrThrow(id));
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    public VoucherDto toDto(Voucher v, long now) {
        Customer c = v.getCustomer();

        String customerName = null;
        if (c != null) {
            customerName = c.getCustomerType() == Customer.CustomerType.COMPANY
                    && c.getCompanyName() != null && !c.getCompanyName().isBlank()
                    ? c.getCompanyName() : c.getName();
        }

        Long daysLeft = null;
        if (v.getValidTo() != null) {
            LocalDate end = AnniversaryUtil.toLocalDate(v.getValidTo());
            daysLeft = java.time.temporal.ChronoUnit.DAYS.between(AnniversaryUtil.today(), end);
        }

        return VoucherDto.builder()
                .id(v.getId())
                .code(v.getCode())
                .title(v.getTitle())
                .customerId(c != null ? c.getId() : null)
                .customerName(customerName)
                .customerPhone(c != null ? (c.getPhone() != null ? c.getPhone() : c.getCompanyPhone()) : null)
                .customerType(c != null && c.getCustomerType() != null ? c.getCustomerType().name() : null)
                .amount(v.getAmount())
                .usedAmount(v.getUsedAmount())
                .remaining(v.remaining())
                .validFrom(v.getValidFrom())
                .validTo(v.getValidTo())
                .status(v.getStatus() != null ? v.getStatus().name() : null)
                .effectiveStatus(v.effectiveStatus(now).name())
                .daysLeft(daysLeft)
                .reason(v.getReason() != null ? v.getReason().name() : null)
                .applyScope(v.getApplyScope() != null ? v.getApplyScope().name() : null)
                .categoryIds(v.getCategoryIds())
                .productIds(v.getProductIds())
                .categories(_resolveCategories(v.getCategoryIds()))
                .products(_resolveProducts(v.getProductIds()))
                .note(v.getNote())
                .createdByName(v.getCreatedByName())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }

    private List<NamedRef> _resolveCategories(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return categoryRepository.findAllById(ids).stream()
                .map(c -> new NamedRef(c.getId(), c.getName()))
                .collect(Collectors.toList());
    }

    private List<NamedRef> _resolveProducts(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return productRepository.findAllById(ids).stream()
                .map(p -> new NamedRef(p.getId(), p.getName()))
                .collect(Collectors.toList());
    }

    // ── Validate & helpers ───────────────────────────────────────────────────

    private Voucher _findOrThrow(Long id) {
        return voucherRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy voucher #" + id));
    }

    private void _validateDateRange(Long from, Long to) {
        if (from == null || to == null)
            throw new BusinessException("Vui lòng nhập đầy đủ thời hạn sử dụng");
        if (to < from)
            throw new BusinessException("Ngày hết hạn phải sau ngày bắt đầu");
    }

    private Voucher.ApplyScope _resolveScope(String raw) {
        Voucher.ApplyScope scope = _parseEnum(Voucher.ApplyScope.class, raw);
        return scope != null ? scope : Voucher.ApplyScope.ALL;
    }

    private void _validateScope(Voucher.ApplyScope scope, Set<Long> categoryIds, Set<Long> productIds) {
        if (scope == Voucher.ApplyScope.CATEGORY && (categoryIds == null || categoryIds.isEmpty()))
            throw new BusinessException("Vui lòng chọn ít nhất 1 danh mục áp dụng");
        if (scope == Voucher.ApplyScope.PRODUCT && (productIds == null || productIds.isEmpty()))
            throw new BusinessException("Vui lòng chọn ít nhất 1 sản phẩm áp dụng");
    }

    /**
     * Voucher sinh nhật chỉ hợp lệ với khách LẺ, voucher khai trương chỉ với khách CÔNG TY —
     * và dịp tương ứng phải đã được khai báo, nếu không phiếu in ra sẽ không có ngày để ghi.
     */
    private void _validateReasonAgainstCustomer(Voucher.VoucherReason reason, Customer c) {
        if (c == null) return;
        if (reason == Voucher.VoucherReason.BIRTHDAY) {
            if (c.getCustomerType() != Customer.CustomerType.RETAIL)
                throw new BusinessException("Voucher sinh nhật chỉ áp dụng cho khách lẻ");
            if (c.getBirthday() == null)
                throw new BusinessException("Khách hàng này chưa khai báo ngày sinh nhật");
        }
        if (reason == Voucher.VoucherReason.STORE_OPENING) {
            if (c.getCustomerType() != Customer.CustomerType.COMPANY)
                throw new BusinessException("Voucher khai trương chỉ áp dụng cho khách công ty");
            if (c.getStoreOpeningDate() == null)
                throw new BusinessException("Khách hàng này chưa khai báo ngày khai trương cửa hàng mới");
        }
    }

    /** Mã dạng {@code VC-yyMM-XXXXXX}; thử lại nếu trùng (xác suất rất thấp). */
    private String _generateCode() {
        String prefix = "VC-" + LocalDate.now(AnniversaryUtil.VN_ZONE)
                .format(DateTimeFormatter.ofPattern("yyMM")) + "-";
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 6; i++)
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            String code = sb.toString();
            if (!voucherRepository.existsByCode(code)) return code;
        }
        return prefix + System.currentTimeMillis();
    }

    private static <E extends Enum<E>> E _parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Enum.valueOf(type, raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static String _blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
