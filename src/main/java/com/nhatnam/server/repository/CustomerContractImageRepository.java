package com.nhatnam.server.repository;

import com.nhatnam.server.entity.CustomerContractImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerContractImageRepository extends JpaRepository<CustomerContractImage, Long> {

    /** Bộ hợp đồng ĐANG CÓ HIỆU LỰC của một khách, theo đúng thứ tự trang. */
    List<CustomerContractImage> findByCustomer_IdAndActiveTrueOrderBySortOrderAscIdAsc(Long customerId);

    /** Toàn bộ lịch sử (kể cả bản đã bị thay) — dùng khi cần truy vết. */
    List<CustomerContractImage> findByCustomer_IdOrderByUploadedAtDescIdAsc(Long customerId);

    boolean existsByCustomer_IdAndActiveTrue(Long customerId);

    /**
     * ID các khách ĐANG CÓ hợp đồng, lọc trong một tập khách cho trước.
     *
     * <p>Dùng khi trả danh sách khách: hỏi một lần cho cả trang thay vì gọi
     * {@code existsBy...} trên từng dòng (N+1 query trên bảng có thể rất dài).
     */
    @Query("""
           SELECT DISTINCT i.customer.id FROM CustomerContractImage i
            WHERE i.active = true
              AND i.customer.id IN :customerIds
           """)
    List<Long> findCustomerIdsWithContract(@Param("customerIds") List<Long> customerIds);
}
