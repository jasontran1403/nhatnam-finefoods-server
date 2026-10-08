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

    // ── QUẢN LÝ QUÀ TẶNG (trang gift-management) ─────────────────────────────
    // Chỉ lấy phiếu ĐÃ ĐƯỢC DUYỆT — tức status ∈ {APPROVED, DELIVERING, COMPLETED}.
    // Bỏ PENDING (chưa duyệt = chưa thực sự tặng), REJECTED và CANCELLED (không phát sinh
    // quà). Cùng nguyên tắc lọc "đơn có KM" bên OrderRepository, để hai nguồn dữ liệu
    // trên trang chỉ hiển thị hàng đã "chốt".
    //
    // Không JOIN FETCH items ở đây vì @OneToMany fetch=LAZY + cùng transaction đọc:
    // service lặp items từng phiếu để flatten và các query nhỏ đủ rẻ. Nếu về sau dữ
    // liệu lớn cần tối ưu, thay bằng "LEFT JOIN FETCH g.items" và loại bỏ trùng ở
    // service.

    /**
     * Phiếu tặng quà đã duyệt, đã lọc thô ở tầng phiếu (khoảng ngày, người tạo,
     * search theo tên khách / mã phiếu / SĐT). Sort desc theo createdAt để hòa vào
     * timeline đơn hàng KM sau khi merge.
     */
    @Query("""
            SELECT g FROM GiftOrder g
             WHERE g.status IN (
                  com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.APPROVED,
                  com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.DELIVERING,
                  com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.COMPLETED)
               AND (:from IS NULL OR g.createdAt >= :from)
               AND (:to   IS NULL OR g.createdAt <= :to)
               AND (:createdById IS NULL OR g.createdBy.id = :createdById)
               AND (:q IS NULL
                    OR LOWER(g.code)         LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(g.customerName) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(g.customer.phone) LIKE LOWER(CONCAT('%', :q, '%')))
             ORDER BY g.createdAt DESC
            """)
    List<GiftOrder> findApprovedForGiftManagement(
            @Param("q") String q,
            @Param("from") Long from,
            @Param("to") Long to,
            @Param("createdById") Long createdById);

    /**
     * Danh sách người tạo cho dropdown filter — chỉ liệt kê những seller đã tạo
     * phiếu đã được duyệt, tương ứng với những phiếu trang gift-management sẽ trả.
     * Bỏ tài khoản đã xoá (createdBy null) vì filter không dùng được với option
     * "không có id".
     */
    @Query("""
            SELECT DISTINCT g.createdBy.id, COALESCE(g.createdBy.fullName, g.createdBy.username)
              FROM GiftOrder g
             WHERE g.status IN (
                  com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.APPROVED,
                  com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.DELIVERING,
                  com.nhatnam.server.entity.GiftOrder$GiftOrderStatus.COMPLETED)
               AND g.createdBy IS NOT NULL
            """)
    List<Object[]> findApprovedGiftHandlers();
}
