// FILE 3: IngredientExpiryRepository.java
// THAY ĐỔI: sửa các method nhận Ingredient object → Long ingredientId,
//           vì IngredientExpiry không còn @ManyToOne ingredient
package com.nhatnam.server.repository;

import com.nhatnam.server.entity.IngredientExpiry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IngredientExpiryRepository extends JpaRepository<IngredientExpiry, Long> {

    // Sửa: nhận Long warehouseId + Long ingredientId thay vì object
    List<IngredientExpiry> findByWarehouseIdAndIngredientIdOrderByExpiryDateAsc(
            Long warehouseId, Long ingredientId);

    /** Chỉ lấy lô còn hàng (quantity > 0) — dùng khi cần trừ/chuyển kho để tránh xử lý dư lô đã hết */
    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId AND e.ingredientId = :ingredientId AND e.quantity > 0 " +
            "ORDER BY CASE WHEN e.expiryDate IS NULL THEN 1 ELSE 0 END ASC, e.expiryDate ASC, e.id ASC")
    List<IngredientExpiry> findAvailableLotsOrderByExpiryAsc(
            @Param("warehouseId") Long warehouseId, @Param("ingredientId") Long ingredientId);

    /**
     * CÁC LÔ TRÙNG KHỚP (kho + nguyên liệu + HSD + giá vốn), CŨ NHẤT TRƯỚC.
     *
     * <p>Bảng {@code ingredient_expiry} KHÔNG có ràng buộc duy nhất trên bộ 4 cột
     * này, và trong thực tế vẫn sinh ra trùng: hai lần nhập kho khác nhau cùng
     * HSD và cùng giá vốn sẽ tạo hai dòng riêng. Đó là dữ liệu HỢP LỆ — tổng tồn
     * vẫn đúng, chỉ là tách lô.
     *
     * <p>Vì vậy phải trả về {@code List}. Bản cũ khai báo {@code Optional} nên khi
     * gặp 2 dòng trùng, Spring Data ném
     * {@code IncorrectResultSizeDataAccessException: query did not return a unique
     * result: 2} và toàn bộ giao dịch chuyển kho bị huỷ.
     *
     * <p>Viết JPQL tường minh thay vì derived query để kiểm soát so sánh với
     * {@code null}: HSD và giá vốn đều cho phép null, mà {@code = NULL} trong SQL
     * không bao giờ khớp.
     */
    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId AND e.ingredientId = :ingredientId " +
            "AND ((:expiryDate IS NULL AND e.expiryDate IS NULL) OR e.expiryDate = :expiryDate) " +
            "AND ((:costPrice IS NULL AND e.costPrice IS NULL) OR e.costPrice = :costPrice) " +
            "ORDER BY e.id ASC")
    List<IngredientExpiry> findMatchingLots(
            @Param("warehouseId") Long warehouseId,
            @Param("ingredientId") Long ingredientId,
            @Param("expiryDate") LocalDate expiryDate,
            @Param("costPrice") BigDecimal costPrice);

    /**
     * Lô để CỘNG THÊM hàng vào khi nhập/chuyển kho — lô CŨ NHẤT trong nhóm trùng.
     *
     * <p>Giữ nguyên tên và chữ ký cũ nên 3 nơi đang gọi (chuyển kho xưởng, nhập
     * kho, thành phẩm) không phải sửa gì.
     *
     * <p>Chọn lô cũ nhất (id nhỏ nhất) chứ không phải lô bất kỳ: nhất quán với
     * FIFO — hàng cộng vào lô cũ sẽ được xuất trước, tiền vốn không bị treo lại ở
     * dòng cũ trong khi dòng mới cứ phình ra.
     *
     * <p>Cố ý KHÔNG tự gộp các dòng trùng ở đây: gộp là thao tác xoá dữ liệu, phải
     * do người vận hành quyết định sau khi đối chiếu, không nên xảy ra ngầm giữa
     * một lần bấm chuyển kho.
     */
    default Optional<IngredientExpiry> findByWarehouseIdAndIngredientIdAndExpiryDateAndCostPrice(
            Long warehouseId, Long ingredientId, LocalDate expiryDate, BigDecimal costPrice) {
        List<IngredientExpiry> lots = findMatchingLots(warehouseId, ingredientId, expiryDate, costPrice);
        return lots.isEmpty() ? Optional.empty() : Optional.of(lots.get(0));
    }

    List<IngredientExpiry> findByWarehouseIdAndIngredientIdOrderByCreatedAtAsc(
            Long warehouseId, Long ingredientId);

    @Query(value = """
            SELECT e.ingredient_id AS ingredientId,
                   MIN(CASE WHEN e.expiry_date IS NOT NULL AND e.quantity > 0
                            THEN e.expiry_date END) AS earliestExpiry,
                   COALESCE(SUM(CASE WHEN e.expiry_date IS NOT NULL
                                      AND e.expiry_date <= :threshold
                                      AND e.quantity > 0
                                     THEN e.quantity ELSE 0 END), 0) AS nearExpiryQty
            FROM ingredient_expiry e
            WHERE e.warehouse_id = :warehouseId
            GROUP BY e.ingredient_id
            """, nativeQuery = true)
    List<Object[]> summarizeByWarehouse(@Param("warehouseId") Long warehouseId,
                                        @Param("threshold") LocalDate threshold);

    List<IngredientExpiry> findByWarehouseIdAndIngredientIdOrderByExpiryDate(
            Long warehouseId, Long ingredientId);

    /** FIFO: lô cũ nhất, sửa dùng plain column ingredientId */
    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId AND e.ingredientId = :ingredientId " +
            "AND e.quantity > 0 " +
            "ORDER BY CASE WHEN e.expiryDate IS NULL THEN 1 ELSE 0 END ASC, e.expiryDate ASC, e.id ASC")
    List<IngredientExpiry> findFifoLots(@Param("warehouseId") Long warehouseId,
                                        @Param("ingredientId") Long ingredientId);

    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId " +
            "ORDER BY e.expiryDate ASC")
    List<IngredientExpiry> findByWarehouseId(@Param("warehouseId") Long warehouseId);

    @Query("SELECT e FROM IngredientExpiry e " +
            "WHERE e.warehouse.id = :warehouseId AND e.ingredientId = :ingredientId " +
            "ORDER BY e.expiryDate ASC")
    List<IngredientExpiry> findByWarehouseIdAndIngredientId(
            @Param("warehouseId") Long warehouseId,
            @Param("ingredientId") Long ingredientId);

    /** Tất cả lô còn hàng (quantity > 0) của 1 danh sách nguyên liệu, không phân biệt kho — dùng cho dashboard tổng tồn kho */
    @Query("SELECT e FROM IngredientExpiry e WHERE e.ingredientId IN :ingredientIds AND e.quantity > 0")
    List<IngredientExpiry> findAllByIngredientIdInAndQuantityPositive(@Param("ingredientIds") List<Long> ingredientIds);
}
