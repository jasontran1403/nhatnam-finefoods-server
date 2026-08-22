package com.nhatnam.server.service;

import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.entity.Customer;
import com.nhatnam.server.entity.CustomerContractImage;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.CustomerContractImageRepository;
import com.nhatnam.server.repository.CustomerRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HỢP ĐỒNG KHÁCH HÀNG — tải lên, xem, thay thế.
 *
 * <p>Hợp đồng quyết định khách có được mua CÔNG NỢ hay không, nên trạng thái
 * "có hợp đồng" phải là một nguồn duy nhất: {@link #hasContract(Long)}. Mọi nơi
 * chặn công nợ đều hỏi qua đây thay vì tự truy vấn, để sau này đổi quy tắc
 * (VD: thêm hạn hợp đồng) chỉ phải sửa một chỗ.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class CustomerContractService {

    private final CustomerContractImageRepository contractRepo;
    private final CustomerRepository customerRepository;
    private final FileStorageService fileStorageService;

    /** Một trang hợp đồng trả về cho FE. */
    @Data
    @Builder
    public static class ContractImageDto {
        private Long id;
        private String imageUrl;
        private Integer sortOrder;
        private String uploadedByName;
        private Long uploadedAt;
    }

    /** Toàn bộ thông tin hợp đồng của một khách. */
    @Data
    @Builder
    public static class ContractDto {
        private Long customerId;
        private boolean hasContract;
        /** true = khách mới, bắt buộc có hợp đồng mới được công nợ. */
        private boolean contractRequired;
        /**
         * Khách này CÓ ĐƯỢC thanh toán công nợ không — khách cũ được miễn dù
         * chưa có hợp đồng. Màn đổi phương thức thanh toán dựa vào cờ này.
         */
        private boolean debtAllowed;
        private List<ContractImageDto> images;
        /** Người tải bộ hợp đồng hiện hành + thời điểm — hiện ở đầu modal xem. */
        private String uploadedByName;
        private Long uploadedAt;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ĐỌC
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public boolean hasContract(Long customerId) {
        if (customerId == null) return false;
        return contractRepo.existsByCustomer_IdAndActiveTrue(customerId);
    }

    /**
     * KHÁCH NÀY CÓ ĐƯỢC THANH TOÁN CÔNG NỢ KHÔNG.
     *
     * <p>Đây là câu hỏi mà mọi luồng tạo đơn / đổi phương thức phải hỏi — KHÔNG
     * phải {@link #hasContract}. Hai câu trả lời khác nhau ở nhóm khách cũ:
     *
     * <ul>
     *   <li><b>Khách cũ</b> (contractRequired = NULL, có từ trước khi tính năng
     *       hợp đồng ra đời): được bán chịu như trước, dù chưa có hợp đồng.</li>
     *   <li><b>Khách mới</b> (contractRequired = TRUE): phải có hợp đồng.</li>
     * </ul>
     *
     * <p>Khách lẻ không có hồ sơ ({@code customer == null}) thì luôn không được.
     */
    @Transactional(readOnly = true)
    public boolean isDebtAllowed(Customer customer) {
        if (customer == null || customer.getId() == null) return false;
        if (!customer.isContractRequiredForDebt()) return true;   // khách cũ — miễn
        return hasContract(customer.getId());
    }

    /** Bản tra theo id, dùng khi nơi gọi chỉ có customerId. */
    @Transactional(readOnly = true)
    public boolean isDebtAllowed(Long customerId) {
        if (customerId == null) return false;
        return customerRepository.findById(customerId)
                .map(this::isDebtAllowed)
                .orElse(false);
    }

    /**
     * Tra "được bán công nợ chưa" cho NHIỀU khách một lần — dùng khi trả danh
     * sách khách, tránh N+1 query.
     */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> debtAllowedMap(List<Customer> customers) {
        if (customers == null || customers.isEmpty()) return Collections.emptyMap();

        List<Long> needContract = customers.stream()
                .filter(c -> c != null && c.getId() != null && c.isContractRequiredForDebt())
                .map(Customer::getId)
                .toList();

        var withContract = needContract.isEmpty()
                ? List.<Long>of()
                : contractRepo.findCustomerIdsWithContract(needContract);

        Map<Long, Boolean> out = new java.util.LinkedHashMap<>();
        for (Customer c : customers) {
            if (c == null || c.getId() == null) continue;
            out.put(c.getId(), !c.isContractRequiredForDebt() || withContract.contains(c.getId()));
        }
        return out;
    }

    /**
     * Tra "có hợp đồng chưa" cho NHIỀU khách một lần.
     *
     * <p>Danh sách khách có thể vài trăm dòng; hỏi từng dòng sẽ thành N+1 query.
     */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> hasContractMap(List<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) return Collections.emptyMap();
        var withContract = contractRepo.findCustomerIdsWithContract(customerIds);
        return customerIds.stream()
                .distinct()
                .collect(Collectors.toMap(id -> id, withContract::contains));
    }

    @Transactional(readOnly = true)
    public ContractDto getContract(Long customerId) {
        List<CustomerContractImage> images =
                contractRepo.findByCustomer_IdAndActiveTrueOrderBySortOrderAscIdAsc(customerId);

        CustomerContractImage first = images.isEmpty() ? null : images.get(0);

        boolean required = customerRepository.findById(customerId)
                .map(Customer::isContractRequiredForDebt)
                .orElse(true);

        return ContractDto.builder()
                .customerId(customerId)
                .hasContract(!images.isEmpty())
                .contractRequired(required)
                .debtAllowed(!required || !images.isEmpty())
                .uploadedByName(first != null ? first.getUploadedByName() : null)
                .uploadedAt(first != null ? first.getUploadedAt() : null)
                .images(images.stream().map(i -> ContractImageDto.builder()
                        .id(i.getId())
                        .imageUrl(i.getImagePath())
                        .sortOrder(i.getSortOrder())
                        .uploadedByName(i.getUploadedByName())
                        .uploadedAt(i.getUploadedAt())
                        .build()).toList())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GHI
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * THAY TOÀN BỘ bộ hợp đồng của khách bằng các file vừa tải lên.
     *
     * <p>Không có chế độ "thêm vào bộ cũ": một khách chỉ có một hợp đồng đang
     * hiệu lực, trộn trang của hai lần ký khác nhau sẽ tạo ra bộ chứng từ không
     * ai đọc được. FE phải hỏi xác nhận trước khi gọi khi khách đã có hợp đồng.
     *
     * <p>Bản ghi cũ chỉ bị đánh dấu {@code active = false}, KHÔNG xoá dòng và
     * hiện KHÔNG xoá file vật lý (xem {@link #deleteOrphanContractFiles}).
     */
    @Transactional
    public ContractDto replaceContract(Long customerId, List<MultipartFile> files, User actor) {
        if (files == null || files.isEmpty())
            throw new IllegalArgumentException("Chưa chọn file hợp đồng nào");

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Khách hàng không tồn tại: " + customerId));

        String actorName = actor == null ? "—"
                : (actor.getFullName() != null && !actor.getFullName().isBlank()
                        ? actor.getFullName() : actor.getUsername());
        long now = System.currentTimeMillis();

        // 1. Lưu file MỚI TRƯỚC. Nếu một file lỗi thì toàn bộ giao dịch rollback
        //    và hợp đồng cũ vẫn còn nguyên — không bao giờ để khách rơi vào
        //    trạng thái "vừa mất hợp đồng cũ vừa chưa có hợp đồng mới".
        List<String> newPaths = new ArrayList<>();
        for (MultipartFile f : files) {
            try {
                newPaths.add(fileStorageService.saveCustomerContractFile(f));
            } catch (Exception e) {
                log.error("Lưu file hợp đồng thất bại (khách {}): {}", customerId, e.getMessage());
                throw new IllegalArgumentException(
                        "Không lưu được file \"" + f.getOriginalFilename() + "\": " + e.getMessage());
            }
        }

        // 2. Vô hiệu hoá bộ cũ, ghi rõ ai thay và lúc nào.
        List<CustomerContractImage> old =
                contractRepo.findByCustomer_IdAndActiveTrueOrderBySortOrderAscIdAsc(customerId);
        for (CustomerContractImage o : old) {
            o.setActive(false);
            o.setReplacedAt(now);
            o.setReplacedByName(actorName);
        }
        if (!old.isEmpty()) contractRepo.saveAll(old);

        // 3. Ghi bộ mới.
        List<CustomerContractImage> fresh = new ArrayList<>();
        for (int i = 0; i < newPaths.size(); i++) {
            fresh.add(CustomerContractImage.builder()
                    .customer(customer)
                    .imagePath(newPaths.get(i))
                    .sortOrder(i)
                    .active(true)
                    .uploadedBy(actor)
                    .uploadedByName(actorName)
                    .uploadedAt(now)
                    .build());
        }
        contractRepo.saveAll(fresh);

        log.info("Hợp đồng khách {}: {} trang mới do {} tải lên (thay {} trang cũ)",
                customerId, fresh.size(), actorName, old.size());

        // Dọn file cũ — CHƯA BẬT, xem ghi chú ở phương thức bên dưới.
        // deleteOrphanContractFiles(old);

        return getContract(customerId);
    }

    /**
     * XOÁ FILE VẬT LÝ của các bản hợp đồng đã bị thay.
     *
     * <p><b>CHƯA ĐƯỢC GỌI Ở ĐÂU.</b> Theo yêu cầu hiện tại, thay hợp đồng chỉ gỡ
     * ảnh cũ khỏi dữ liệu đang dùng, còn file trên đĩa giữ nguyên để còn đối
     * chiếu khi có tranh chấp. Khi nào chốt được chính sách lưu trữ (giữ bao lâu,
     * ai được xoá) thì bỏ chú thích ở lời gọi trong {@link #replaceContract}
     * hoặc gọi từ một job dọn dẹp định kỳ.
     *
     * <p>Lưu ý khi bật: chỉ xoá file, KHÔNG xoá dòng trong bảng — dòng còn lại
     * là nhật ký cho biết đã từng có hợp đồng nào, ai thay, lúc nào.
     */
    @SuppressWarnings("unused")
    private void deleteOrphanContractFiles(List<CustomerContractImage> replaced) {
        if (replaced == null || replaced.isEmpty()) return;
        for (CustomerContractImage img : replaced) {
            try {
                fileStorageService.deleteFile(img.getImagePath());
                log.info("Đã xoá file hợp đồng cũ: {}", img.getImagePath());
            } catch (Exception e) {
                // Xoá hụt không được làm hỏng luồng chính — file thừa vô hại,
                // mất giao dịch thì không.
                log.warn("Không xoá được file hợp đồng cũ {}: {}", img.getImagePath(), e.getMessage());
            }
        }
    }
}
