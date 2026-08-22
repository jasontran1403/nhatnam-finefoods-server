package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <b>TÁCH LUỒNG:</b> 2 query gốc ({@code findByCreatedBy_IdAndFilters},
 * {@code findByFilters}) đã được bổ sung điều kiện
 * {@code orderType IS NULL OR orderType = 'MATERIAL'} — nhờ vậy các page phiếu
 * đặt hàng NGUYÊN LIỆU hiện có KHÔNG BAO GIỜ nhìn thấy phiếu Văn phòng phẩm,
 * không phải sửa gì trong MaterialRequestService / FE cũ.
 *
 * <p><b>Vì sao phải có {@code IS NULL}?</b> Với {@code ddl-auto: update}, cột
 * {@code order_type} vừa được thêm vào bảng đang có sẵn hàng nghìn dòng — tất cả
 * đều NULL cho tới khi backfill chạy. Nếu query chỉ so sánh {@code = 'MATERIAL'}
 * thì ngay lần deploy đầu tiên TOÀN BỘ phiếu đặt hàng cũ sẽ biến mất khỏi màn
 * hình. Điều kiện {@code IS NULL} khiến hệ thống đúng ngay cả khi backfill chưa
 * chạy hoặc chạy lỗi — an toàn hơn việc phụ thuộc vào thứ tự khởi động.
 *
 * <p>Luồng VPP dùng 2 query riêng phía dưới ({@code findSupply*}).
 */
public interface MaterialRequestRepository extends JpaRepository<MaterialRequest, Long> {

    long countByRequestCodeStartingWith(String prefix);

    // ══════════════════════════════════════════════════════════════════════════
    //  PHIẾU NGUYÊN LIỆU SẢN XUẤT (orderType = MATERIAL) — luồng gốc
    // ══════════════════════════════════════════════════════════════════════════

    @Query("""
        SELECT r FROM MaterialRequest r
        WHERE r.createdBy.id = :userId
          AND (r.orderType IS NULL OR CAST(r.orderType AS string) = 'MATERIAL')
          AND (:type IS NULL OR CAST(r.type AS string) = :type)
          AND (:status IS NULL OR CAST(r.status AS string) = :status)
          AND (:dateFrom IS NULL OR r.createdAt >= :dateFrom)
          AND (:dateTo IS NULL OR r.createdAt <= :dateTo)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.requestCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(r.createdByName) LIKE LOWER(CONCAT('%',:search,'%'))
               OR EXISTS (SELECT 1 FROM MaterialRequestVendor mv WHERE mv.materialRequest = r
                          AND (LOWER(mv.importReceiptInfo) LIKE LOWER(CONCAT('%',:search,'%'))
                            OR LOWER(mv.serialImei) LIKE LOWER(CONCAT('%',:search,'%'))
                            OR LOWER(mv.vendorName) LIKE LOWER(CONCAT('%',:search,'%')))))
        ORDER BY r.createdAt DESC
    """)
    Page<MaterialRequest> findByCreatedBy_IdAndFilters(
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("status") String status,
            @Param("dateFrom") Long dateFrom,
            @Param("dateTo") Long dateTo,
            @Param("search") String search,
            Pageable pageable);

    @Query("""
        SELECT r FROM MaterialRequest r
        WHERE (r.orderType IS NULL OR CAST(r.orderType AS string) = 'MATERIAL')
          AND (:type IS NULL OR CAST(r.type AS string) = :type)
          AND (:status IS NULL OR CAST(r.status AS string) = :status)
          AND (:dateFrom IS NULL OR r.createdAt >= :dateFrom)
          AND (:dateTo IS NULL OR r.createdAt <= :dateTo)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.requestCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(r.createdByName) LIKE LOWER(CONCAT('%',:search,'%'))
               OR EXISTS (SELECT 1 FROM MaterialRequestVendor mv WHERE mv.materialRequest = r
                          AND (LOWER(mv.importReceiptInfo) LIKE LOWER(CONCAT('%',:search,'%'))
                            OR LOWER(mv.serialImei) LIKE LOWER(CONCAT('%',:search,'%'))
                            OR LOWER(mv.vendorName) LIKE LOWER(CONCAT('%',:search,'%')))))
        ORDER BY r.createdAt DESC
    """)
    Page<MaterialRequest> findByFilters(
            @Param("type") String type,
            @Param("status") String status,
            @Param("dateFrom") Long dateFrom,
            @Param("dateTo") Long dateTo,
            @Param("search") String search,
            Pageable pageable);

    // ══════════════════════════════════════════════════════════════════════════
    //  PHIẾU VĂN PHÒNG PHẨM / ĐỒ DÙNG (orderType = SUPPLY)
    // ══════════════════════════════════════════════════════════════════════════

    /** Phiếu VPP do CHÍNH user tạo — page của người tạo phiếu. */
    @Query("""
        SELECT r FROM MaterialRequest r
        WHERE r.createdBy.id = :userId
          AND CAST(r.orderType AS string) = 'SUPPLY'
          AND (:status IS NULL OR CAST(r.status AS string) = :status)
          AND (:warehouseId IS NULL OR r.supplyWarehouseId = :warehouseId)
          AND (:dateFrom IS NULL OR r.createdAt >= :dateFrom)
          AND (:dateTo IS NULL OR r.createdAt <= :dateTo)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.requestCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR EXISTS (SELECT 1 FROM MaterialRequestItem mi WHERE mi.materialRequest = r
                          AND LOWER(mi.materialName) LIKE LOWER(CONCAT('%',:search,'%'))))
        ORDER BY r.createdAt DESC
    """)
    Page<MaterialRequest> findSupplyByCreator(
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("warehouseId") Long warehouseId,
            @Param("dateFrom") Long dateFrom,
            @Param("dateTo") Long dateTo,
            @Param("search") String search,
            Pageable pageable);

    /** Phiếu VPP cho SUPER_ACCOUNTANT / OWNER — tab "Đồ dùng". */
    @Query("""
        SELECT r FROM MaterialRequest r
        WHERE CAST(r.orderType AS string) = 'SUPPLY'
          AND (:status IS NULL OR CAST(r.status AS string) = :status)
          AND (:warehouseId IS NULL OR r.supplyWarehouseId = :warehouseId)
          AND (:dateFrom IS NULL OR r.createdAt >= :dateFrom)
          AND (:dateTo IS NULL OR r.createdAt <= :dateTo)
          AND (:search IS NULL OR :search = ''
               OR LOWER(r.requestCode) LIKE LOWER(CONCAT('%',:search,'%'))
               OR LOWER(r.createdByName) LIKE LOWER(CONCAT('%',:search,'%'))
               OR EXISTS (SELECT 1 FROM MaterialRequestItem mi WHERE mi.materialRequest = r
                          AND LOWER(mi.materialName) LIKE LOWER(CONCAT('%',:search,'%')))
               OR EXISTS (SELECT 1 FROM SupplyOrderGroup g WHERE g.materialRequest = r
                          AND LOWER(g.supplierName) LIKE LOWER(CONCAT('%',:search,'%'))))
        ORDER BY r.createdAt DESC
    """)
    Page<MaterialRequest> findSupplyByFilters(
            @Param("status") String status,
            @Param("warehouseId") Long warehouseId,
            @Param("dateFrom") Long dateFrom,
            @Param("dateTo") Long dateTo,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Backfill 1 lần: gán {@code MATERIAL} cho toàn bộ dữ liệu cũ.
     *
     * <p>Bắt cả {@code ''} và giá trị rác chứ không chỉ NULL — phòng trường hợp cột
     * từng được tạo dưới dạng NOT NULL, khi đó MySQL điền chuỗi rỗng cho dòng cũ
     * thay vì NULL và mệnh đề {@code IS NULL} sẽ không khớp gì cả.
     *
     * <p>Dùng native query (không qua entity) để {@code @Enumerated} không phải
     * parse giá trị rác trước khi kịp sửa.
     */
    @Modifying
    @Query(value = "UPDATE material_request SET order_type = 'MATERIAL' "
                 + "WHERE order_type IS NULL OR order_type = '' "
                 + "OR order_type NOT IN ('MATERIAL','SUPPLY')",
           nativeQuery = true)
    int backfillOrderType();

    /** Tương tự cho danh mục khoản chi — mọi nhãn cũ đều là dịch vụ, không nhập kho. */
    @Modifying
    @Query(value = "UPDATE vendor_expense_category SET category_kind = 'SERVICE' "
                 + "WHERE category_kind IS NULL OR category_kind = '' "
                 + "OR category_kind NOT IN ('SERVICE','CONSUMABLE')",
           nativeQuery = true)
    int backfillCategoryKind();
}
