package com.nhatnam.server.service.admin;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.request.AdminCreateCustomerRequest;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.customer.CustomerDto;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.CustomerRepository;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerAdminService {

    private final CustomerRepository customerRepository;
    private final UserRepository     userRepository;

    @Transactional(readOnly = true)
    public PageResponse<CustomerDto> list(String q,
                                          Customer.CustomerType type,
                                          Boolean isActive,
                                          Long sellerId,
                                          Pageable pageable) {
        Page<Customer> page = customerRepository.searchAdmin(q, type, isActive, sellerId, pageable);
        List<CustomerDto> content = page.getContent().stream().map(this::toDto).toList();
        return PageResponse.from(page, content);
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

    @Transactional
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
                .createdBySeller(null)  // Admin tạo → null
                .build();

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
                    // Cá nhân → Công ty: clear tên cá nhân
                    c.setName(null);
                } else {
                    // Công ty → Cá nhân: clear thông tin công ty
                    c.setCompanyName(null);
                    c.setTaxCode(null);
                    c.setCompanyPhone(null);
                    c.setCompanyAddress(null);
                    c.setContactName(null);
                }
            }
            c.setCustomerType(newType);
        }
        if (req.getPricingType() != null)
            c.setPricingType(Customer.PricingType.valueOf(req.getPricingType()));
        if (req.getCompanyName() != null) c.setCompanyName(req.getCompanyName());
        if (req.getTaxCode() != null) c.setTaxCode(req.getTaxCode());
        if (req.getCompanyPhone() != null) c.setCompanyPhone(req.getCompanyPhone());
        if (req.getCompanyAddress() != null) c.setCompanyAddress(req.getCompanyAddress());
        if (req.getContactName() != null) c.setContactName(req.getContactName());

        c.setUpdatedAt(System.currentTimeMillis());
        return toDto(customerRepository.save(c));
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
                .companyName(c.getCompanyName())
                .taxCode(c.getTaxCode())
                .companyPhone(c.getCompanyPhone())
                .companyAddress(c.getCompanyAddress())
                .contactName(c.getContactName())
                .sellerId(seller != null ? seller.getId() : null)
                .sellerName(seller != null ? (seller.getFullName() != null ? seller.getFullName() : seller.getUsername()) : null)
                .sellerUsername(seller != null ? seller.getUsername() : null)
                .createdByAdmin(c.getCreatedBySeller() == null)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}