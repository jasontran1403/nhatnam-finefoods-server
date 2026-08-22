package com.nhatnam.server.service.admin;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.request.AdminCreateCustomerRequest;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.customer.CustomerDto;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.utils.AnniversaryUtil;
import com.nhatnam.server.utils.CustomerDebtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomerAdminService {

    private final CustomerRepository customerRepository;
    private final UserRepository     userRepository;
    private final OrderRepository    orderRepository;
    private final com.nhatnam.server.repository.CustomerContractImageRepository contractRepo;
    private final com.nhatnam.server.repository.CustomerCategoryRepository customerCategoryRepository;

    @Transactional(readOnly = true)
    public PageResponse<CustomerDto> list(String q,
                                          Customer.CustomerType type,
                                          Boolean isActive,
                                          Long sellerId,
                                          Pageable pageable) {
        return list(q, type, isActive, sellerId, pageable, null);
    }

    /**
     * @param debtSort "desc" (cao→thấp), "asc" (thấp→cao), hoặc null/blank để dùng sort mặc định.
     *                 Khi sort theo công nợ, phải tính cho toàn bộ tập lọc rồi mới phân trang.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerDto> list(String q,
                                          Customer.CustomerType type,
                                          Boolean isActive,
                                          Long sellerId,
                                          Pageable pageable,
                                          String debtSort) {
        boolean sortByDebt = debtSort != null && !debtSort.isBlank();

        if (!sortByDebt) {
            Page<Customer> page = customerRepository.searchAdmin(q, type, isActive, sellerId, pageable);
            List<Customer> customers = page.getContent();
            Map<Long, Long> debtMap = _debtMap(customers);
            List<CustomerDto> content = customers.stream()
                    .map(c -> _toDtoWithDebt(c, debtMap)).toList();
            return PageResponse.from(page, content);
        }

        // ── Sort theo công nợ ─────────────────────────────────────────────────
        boolean desc = !"asc".equalsIgnoreCase(debtSort);
        Pageable all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        List<Customer> allCustomers = new ArrayList<>(
                customerRepository.searchAdmin(q, type, isActive, sellerId, all).getContent());
        Map<Long, Long> debtMap = _debtMap(allCustomers);

        Comparator<Customer> byDebt =
                Comparator.comparingLong(c -> debtMap.getOrDefault(c.getId(), 0L));
        allCustomers.sort(desc ? byDebt.reversed() : byDebt);

        int total  = allCustomers.size();
        int pageNo = pageable.getPageNumber();
        int size   = pageable.getPageSize() > 0 ? pageable.getPageSize() : 20;
        int start  = pageNo * size;
        int end    = Math.min(start + size, total);
        List<Customer> slice = start >= total ? List.of() : allCustomers.subList(start, end);

        List<CustomerDto> content = slice.stream()
                .map(c -> _toDtoWithDebt(c, debtMap)).toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return PageResponse.<CustomerDto>builder()
                .content(content)
                .page(pageNo)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .first(pageNo == 0)
                .last(pageNo >= totalPages - 1)
                .build();
    }

    /**
     * DANH SÁCH KHÁCH GOM THEO DANH MỤC — dùng cho giao diện collapse, KHÔNG phân trang.
     *
     * <p>Màn hình mới hiển thị mỗi danh mục là một nhóm gập/mở được, nên phân trang trở
     * thành vô nghĩa: cắt trang giữa chừng sẽ làm một danh mục bị xẻ đôi qua hai trang và
     * người dùng không bao giờ thấy được tổng số khách thật của nhóm đó.
     *
     * <p>Thứ tự nhóm bám theo {@code sortOrder} rồi tên danh mục — giống hệt thứ tự ở
     * {@code CustomerCategoryRepository}, để nhóm ĐẦU TIÊN mà FE tự động mở sẵn luôn là
     * danh mục đầu tiên trong DB chứ không phải một nhóm ngẫu nhiên.
     *
     * <p>Khách chưa phân loại gom vào nhóm ảo {@code categoryId = null} đặt CUỐI danh sách.
     *
     * @return danh sách nhóm, mỗi nhóm gồm thông tin danh mục + mảng khách hàng
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listGrouped(String q,
                                                 Customer.CustomerType type,
                                                 Boolean isActive,
                                                 Long sellerId) {
        Pageable all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.ASC, "id"));
        List<Customer> customers = customerRepository
                .searchAdmin(q, type, isActive, sellerId, all).getContent();

        Map<Long, Long> debtMap = _debtMap(customers);

        // Nạp TẤT CẢ danh mục, kể cả danh mục chưa có khách nào — người quản lý cần thấy
        // nhóm rỗng để biết đã tạo danh mục nhưng chưa gán ai.
        List<com.nhatnam.server.entity.CustomerCategory> categories =
                customerCategoryRepository.findAllByOrderBySortOrderAscNameAsc();

        Map<Long, List<CustomerDto>> byCategory = new java.util.LinkedHashMap<>();
        List<CustomerDto> uncategorized = new ArrayList<>();
        List<CustomerDto> upcoming = new ArrayList<>();

        for (Customer c : customers) {
            CustomerDto dto = _toDtoWithDebt(c, debtMap);

            // NHÓM ẢO "SẮP TỚI DỊP" — khách có sinh nhật (khách lẻ) hoặc ngày khai trương
            // (khách công ty) rơi vào THÁNG NÀY và CHƯA QUA.
            //
            // Khách vào nhóm này bị LOẠI khỏi danh mục gốc, cố ý không hiển thị hai lần:
            // cùng một khách xuất hiện ở hai chỗ thì mọi thao tác hàng loạt (khoá bán,
            // đổi phân loại) đều có nguy cơ bị bấm nhầm trên bản sao mà người dùng tưởng
            // là một khách khác.
            if (Boolean.TRUE.equals(dto.getAnniversaryUpcoming())) {
                upcoming.add(dto);
                continue;
            }

            if (c.getCustomerCategory() == null) uncategorized.add(dto);
            else byCategory.computeIfAbsent(c.getCustomerCategory().getId(), k -> new ArrayList<>()).add(dto);
        }

        List<Map<String, Object>> groups = new ArrayList<>();

        // Nhóm ảo đứng ĐẦU danh sách để FE tự mở sẵn nó (FE mở nhóm đầu tiên).
        // Chỉ thêm khi có khách — nhóm rỗng ở đầu trang chỉ tổ chiếm chỗ.
        if (!upcoming.isEmpty()) {
            upcoming.sort(java.util.Comparator.comparingInt(
                    (CustomerDto d) -> d.getDaysUntilAnniversary() != null
                            ? d.getDaysUntilAnniversary() : Integer.MAX_VALUE));

            Map<String, Object> g = new java.util.LinkedHashMap<>();
            g.put("categoryId",   VIRTUAL_UPCOMING_CATEGORY_ID);
            g.put("categoryName", "Sắp tới sinh nhật / khai trương");
            g.put("color",        "#E11D48");
            g.put("sortOrder",    -1);
            g.put("total",        upcoming.size());
            g.put("customers",    upcoming);
            g.put("virtual",      true);
            groups.add(g);
        }
        for (var cat : categories) {
            List<CustomerDto> items = byCategory.getOrDefault(cat.getId(), List.of());
            Map<String, Object> g = new java.util.LinkedHashMap<>();
            g.put("categoryId",   cat.getId());
            g.put("categoryName", cat.getName());
            g.put("color",        cat.getColor());
            g.put("sortOrder",    cat.getSortOrder());
            g.put("total",        items.size());
            g.put("customers",    items);
            g.put("virtual",      false);
            groups.add(g);
        }

        if (!uncategorized.isEmpty()) {
            Map<String, Object> g = new java.util.LinkedHashMap<>();
            g.put("categoryId",   null);
            g.put("categoryName", "Chưa phân loại");
            g.put("color",        null);
            g.put("sortOrder",    Integer.MAX_VALUE);
            g.put("total",        uncategorized.size());
            g.put("customers",    uncategorized);
            g.put("virtual",      false);
            groups.add(g);
        }

        return groups;
    }

    /**
     * Id của nhóm ảo "sắp tới dịp".
     *
     * <p>Dùng số âm thay vì {@code null}: {@code null} đã là id của nhóm "Chưa phân loại",
     * và FE cần phân biệt được hai nhóm này để không tự ý gán phân loại cho khách trong
     * nhóm ảo. Số âm cũng không bao giờ đụng id thật do DB sinh ra.
     */
    public static final long VIRTUAL_UPCOMING_CATEGORY_ID = -1L;

    /** Map customerId → tổng công nợ chưa thanh toán, tính theo lô (1 query đơn hàng). */
    private Map<Long, Long> _debtMap(List<Customer> customers) {
        if (customers == null || customers.isEmpty()) return Collections.emptyMap();
        List<Long> ids = customers.stream().map(Customer::getId).toList();
        return CustomerDebtUtil.unpaidByCustomer(orderRepository.findByCustomerIdIn(ids));
    }

    private CustomerDto _toDtoWithDebt(Customer c, Map<Long, Long> debtMap) {
        CustomerDto dto = toDto(c);
        dto.setUnpaidDebt(debtMap.getOrDefault(c.getId(), 0L));
        return dto;
    }

    @Transactional(readOnly = true)
    public CustomerDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional
    public CustomerDto updateDiscount(Long id, Integer discountRate) {
        Customer c = findOrThrow(id);
        c.setDiscountRate(discountRate);
        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    @Transactional
    public int bulkUpdateDiscount(List<Long> ids, Integer discountRate) {
        if (ids == null || ids.isEmpty()) return 0;
        return customerRepository.bulkUpdateDiscount(ids, discountRate, System.currentTimeMillis());
    }

    @Transactional
    public CustomerDto setActive(Long id, Boolean isActive) {
        Customer c = findOrThrow(id);
        c.setIsActive(isActive);
        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    @Transactional
    public int bulkSetActive(List<Long> ids, Boolean isActive) {
        if (ids == null || ids.isEmpty()) return 0;
        return customerRepository.bulkUpdateActive(ids, isActive, System.currentTimeMillis());
    }

    /**
     * OWNER/ADMIN bật/tắt "yêu cầu thanh toán trước khi giao hàng" cho 1 khách hàng.
     *
     * <p>Chỉ ảnh hưởng tới ĐƠN TẠO MỚI SAU KHI ĐỔI (mỗi đơn đã snapshot cấu hình này
     * tại thời điểm tạo) — cố ý như vậy để không làm kẹt các đơn đang chạy dở.
     */
    @Transactional
    public CustomerDto updateRequirePrepayment(Long id, Boolean require) {
        Customer c = findOrThrow(id);
        c.setRequirePrepayment(Boolean.TRUE.equals(require));
        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    public CustomerDto updateDebtDays(Long id, Integer days) {
        Customer c = findOrThrow(id);
        c.setDebtDays(days);
        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    /**
     * Admin/Owner tạo khách hàng mới.
     * createdBySeller = null → KPI tính chung toàn bộ SALE, ai cũng có thể tạo đơn.
     */
    @Transactional
    public CustomerDto createCustomer(AdminCreateCustomerRequest req) {
        long now = System.currentTimeMillis();

        if (req.getPhone() != null && !req.getPhone().isBlank()
                && customerRepository.existsByPhone(req.getPhone())) {
            throw new BusinessException("Số điện thoại đã tồn tại: " + req.getPhone());
        }

        // Tạo customer code tự động nếu không truyền vào
        String code = req.getCustomerCode();
        if (code == null || code.isBlank()) {
            code = generateCustomerCode();
        }

        Customer.CustomerType type = req.getCustomerType() != null
                ? Customer.CustomerType.valueOf(req.getCustomerType())
                : Customer.CustomerType.RETAIL;

        Customer.PricingType pricingType = req.getPricingType() != null
                ? Customer.PricingType.valueOf(req.getPricingType())
                : Customer.PricingType.RETAIL_PRICE;

        Customer c = Customer.builder()
                .phone(req.getPhone())
                .name(req.getName())
                .email(req.getEmail())         // nullable OK
                .customerCode(code)
                .customerType(type)
                .pricingType(pricingType)
                .discountRate(req.getDiscountRate() != null ? req.getDiscountRate() : 0)
                .debtDays(req.getDebtDays() != null ? req.getDebtDays() : 0)
                .isActive(true)
                .companyName(req.getCompanyName())
                .taxCode(req.getTaxCode())
                .companyPhone(req.getCompanyPhone())
                .companyAddress(req.getCompanyAddress())
                .contactName(req.getContactName())
                // Tên trên hợp đồng — để trống thì fallback sang tên công ty / tên khách
                .contractName(blankToNull(req.getContractName()))
                .birthday(type == Customer.CustomerType.RETAIL ? req.getBirthday() : null)
                .storeOpeningDate(type == Customer.CustomerType.COMPANY ? req.getStoreOpeningDate() : null)
                .createdBySeller(null)  // Admin tạo → null
                .build();

        _validateAnniversary(type, c.getBirthday());
        _applyCategory(c, req.getCategoryId());

        return toDto(customerRepository.save(c));
    }

    /**
     * Admin/Owner cập nhật thông tin khách hàng.
     */
    @Transactional
    public CustomerDto updateCustomer(Long id, AdminCreateCustomerRequest req) {
        Customer c = findOrThrow(id);

        if (req.getName() != null) c.setName(req.getName());
        if (req.getEmail() != null) c.setEmail(req.getEmail());  // nullable OK
        if (req.getPhone() != null && !req.getPhone().equals(c.getPhone())) {
            if (customerRepository.existsByPhone(req.getPhone()))
                throw new BusinessException("Số điện thoại đã tồn tại: " + req.getPhone());
            c.setPhone(req.getPhone());
        }
        if (req.getDiscountRate() != null) c.setDiscountRate(req.getDiscountRate());
        if (req.getDebtDays() != null) c.setDebtDays(req.getDebtDays());
        if (req.getCustomerType() != null) {
            Customer.CustomerType newType = Customer.CustomerType.valueOf(req.getCustomerType());
            if (newType != c.getCustomerType()) {
                if (newType == Customer.CustomerType.COMPANY) {
                    // Cá nhân → Công ty: clear tên cá nhân + sinh nhật (không còn ý nghĩa)
                    c.setName(null);
                    c.setBirthday(null);
                } else {
                    // Công ty → Cá nhân: clear thông tin công ty + ngày khai trương
                    c.setCompanyName(null);
                    c.setTaxCode(null);
                    c.setCompanyPhone(null);
                    c.setCompanyAddress(null);
                    c.setContactName(null);
                    c.setStoreOpeningDate(null);
                }
            }
            c.setCustomerType(newType);
        }

        // Ngày kỷ niệm — chỉ nhận giá trị hợp với loại khách SAU thay đổi.
        if (req.getBirthday() != null && c.getCustomerType() == Customer.CustomerType.RETAIL)
            c.setBirthday(req.getBirthday());
        if (req.getStoreOpeningDate() != null && c.getCustomerType() == Customer.CustomerType.COMPANY)
            c.setStoreOpeningDate(req.getStoreOpeningDate());

        _validateAnniversary(c.getCustomerType(), c.getBirthday());
        if (req.getCategoryId() != null) _applyCategory(c, req.getCategoryId());
        if (req.getPricingType() != null)
            c.setPricingType(Customer.PricingType.valueOf(req.getPricingType()));
        if (req.getCompanyName() != null) c.setCompanyName(req.getCompanyName());
        if (req.getTaxCode() != null) c.setTaxCode(req.getTaxCode());
        if (req.getCompanyPhone() != null) c.setCompanyPhone(req.getCompanyPhone());
        if (req.getCompanyAddress() != null) c.setCompanyAddress(req.getCompanyAddress());
        if (req.getContactName() != null) c.setContactName(req.getContactName());
        // Tên trên hợp đồng: gửi chuỗi rỗng = XOÁ (quay về mặc định), null = giữ nguyên
        if (req.getContractName() != null) c.setContractName(blankToNull(req.getContractName()));

        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    /**
     * Cập nhật riêng TÊN TRÊN HỢP ĐỒNG (dùng cho sửa nhanh ngay trên bảng danh sách).
     * Truyền chuỗi rỗng/null → xoá, quay về tên mặc định (tên công ty / tên khách).
     */
    @Transactional
    public CustomerDto updateContractName(Long id, String contractName) {
        Customer c = findOrThrow(id);
        c.setContractName(blankToNull(contractName));
        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    /** Gán / bỏ phân loại. Truyền 0 hoặc null = bỏ phân loại. */
    private void _applyCategory(Customer c, Long categoryId) {
        if (categoryId == null || categoryId == 0L) { c.setCustomerCategory(null); return; }
        customerCategoryRepository.findById(categoryId)
                .ifPresentOrElse(c::setCustomerCategory, () -> c.setCustomerCategory(null));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String generateCustomerCode() {
        String prefix = "KH";
        String ts = String.valueOf(System.currentTimeMillis()).substring(8);
        String rand = String.format("%03d", new java.util.Random().nextInt(1000));
        return prefix + ts + rand;
    }

    /** Gán hoặc bỏ seller cho khách hàng (sellerId = null = bỏ gán) */
    @Transactional
    public CustomerDto assignSeller(Long customerId, Long sellerId) {
        Customer c = findOrThrow(customerId);
        if (sellerId == null) {
            c.setCreatedBySeller(null);
        } else {
            User seller = userRepository.findById(sellerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Seller không tồn tại: " + sellerId));
            // Validate role
            String role = seller.getRole().name();
            if (!role.equals("SELLER") && !role.equals("SUPER_SELLER")) {
                throw new BusinessException("Người dùng không phải SELLER: " + seller.getUsername());
            }
            c.setCreatedBySeller(seller);
        }
        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Customer findOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer không tồn tại: " + id));
    }

    /**
     * DỊP KỶ NIỆM ĐANG ÁP DỤNG cho khách này.
     *
     * <p>Khách lẻ dùng sinh nhật, khách công ty dùng ngày khai trương. Chọn theo LOẠI KHÁCH
     * chứ không phải "cột nào có dữ liệu": khách đổi từ lẻ sang công ty vẫn còn cột birthday
     * cũ trong DB, nếu lấy bừa cột nào có giá trị thì màn hình sẽ tô màu nhắc sinh nhật cho
     * một pháp nhân.
     */
    private Long _anniversaryOf(Customer c) {
        if (c.getCustomerType() == Customer.CustomerType.COMPANY) return c.getStoreOpeningDate();
        return c.getBirthday();
    }

    /**
     * Ngày sinh nhật và ngày khai trương đều KHÔNG BẮT BUỘC.
     * Giữ method để không phải xoá các chỗ gọi; body rỗng = luôn pass.
     */
    private void _validateAnniversary(Customer.CustomerType resultingType, Long birthdayAfter) {
        // Không bắt buộc — bỏ trống nếu chưa có thông tin.
    }

    private boolean _hasContract(Customer c) {
        return c.getId() != null && contractRepo.existsByCustomer_IdAndActiveTrue(c.getId());
    }

    private CustomerDto toDto(Customer c) {
        User seller = c.getAssignedSeller();
        return CustomerDto.builder()
                .id(c.getId())
                .phone(c.getPhone())
                .name(c.getName())
                .email(c.getEmail())
                .customerCode(c.getCustomerCode())
                .customerType(c.getCustomerType() == null ? null : c.getCustomerType().name())
                .pricingType(c.getPricingType() == null ? "RETAIL_PRICE" : c.getPricingType().name())
                .discountRate(c.getDiscountRate())
                .isActive(c.getIsActive())
                .debtDays(c.getDebtDays())
                .requirePrepayment(Boolean.TRUE.equals(c.getRequirePrepayment()))
                .companyName(c.getCompanyName())
                .taxCode(c.getTaxCode())
                .companyPhone(c.getCompanyPhone())
                .companyAddress(c.getCompanyAddress())
                .contactName(c.getContactName())
                .contractName(c.getContractName())
                .contractNameResolved(c.resolvedContractName())
                .contractNameDefault(c.defaultContractName())
                .hasContract(_hasContract(c))
                .contractRequired(c.isContractRequiredForDebt())
                // Khách cũ (contractRequired = null) vẫn được bán chịu như trước.
                .debtAllowed(!c.isContractRequiredForDebt() || _hasContract(c))
                .sellerId(seller != null ? seller.getId() : null)
                .sellerName(seller != null ? (seller.getFullName() != null ? seller.getFullName() : seller.getUsername()) : null)
                .sellerUsername(seller != null ? seller.getUsername() : null)
                .createdByAdmin(c.getCreatedBySeller() == null)
                // ── Ngày kỷ niệm ─────────────────────────────────────────────
                .birthday(c.getBirthday())
                .storeOpeningDate(c.getStoreOpeningDate())
                .daysUntilAnniversary(AnniversaryUtil.daysUntilNext(_anniversaryOf(c)))
                .anniversaryUpcoming(AnniversaryUtil.isUpcomingThisMonth(_anniversaryOf(c)))
                // ── Phân loại ────────────────────────────────────────────────
                .categoryId(c.getCustomerCategory() != null ? c.getCustomerCategory().getId() : null)
                .categoryName(c.getCustomerCategory() != null ? c.getCustomerCategory().getName() : null)
                .categoryColor(c.getCustomerCategory() != null ? c.getCustomerCategory().getColor() : null)
                .categorySortOrder(c.getCustomerCategory() != null ? c.getCustomerCategory().getSortOrder() : null)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}