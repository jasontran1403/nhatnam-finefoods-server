package com.nhatnam.server.repository;

import com.nhatnam.server.entity.GiftOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GiftOrderRepository extends JpaRepository<GiftOrder, Long> {

    boolean existsByCode(String code);

    /**
     * Tìm kiếm cho màn hình duyệt của OWNER/ADMIN và màn hình theo dõi của seller.
     *
     * @param createdById lọc phiếu do một seller tạo; null = tất cả
     * @param warehouseId lọc theo kho; null = tất cả
     */
    @Query("SELECT g FROM GiftOrder g WHERE " +
            "(:q IS NULL OR LOWER(g.code) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(g.customerName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(g.customer.phone) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "  OR LOWER(g.customer.companyPhone) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:status IS NULL OR g.status = :status) " +
            "AND (:createdById IS NULL OR g.createdBy.id = :createdById) " +
            "AND (:warehouseId IS NULL OR g.warehouse.id = :warehouseId)")
    Page<GiftOrder> search(@Param("q") String q,
                           @Param("status") GiftOrder.GiftOrderStatus status,
                           @Param("createdById") Long createdById,
                           @Param("warehouseId") Long warehouseId,
                           Pageable pageable);

    /**
     * Phiếu đã duyệt / đang giao của các kho được phân công — hàng chờ nhân viên kho xử lý.
     *
     * <p>Trả cả DELIVERING chứ không chỉ APPROVED: nhân viên kho cần thấy lại phiếu mình
     * đã nhận để đánh dấu giao xong, nếu lọc mất thì phiếu biến khỏi màn hình ngay sau
     * khi bấm xác nhận và không ai đóng được nó.
     */
    @Query("SELECT g FROM GiftOrder g WHERE g.warehouse.id IN :warehouseIds " +
            "AND g.status IN (com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.APPROVED, " +
            "                 com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.DELIVERING) " +
            "ORDER BY g.approvedAt ASC")
    List<GiftOrder> findPendingForWarehouses(@Param("warehouseIds") List<Long> warehouseIds);

    long countByStatus(GiftOrder.GiftOrderStatus status);
}
