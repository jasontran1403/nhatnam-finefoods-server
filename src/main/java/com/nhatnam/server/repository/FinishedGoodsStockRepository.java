package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FinishedGoodsStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FinishedGoodsStockRepository extends JpaRepository<FinishedGoodsStock, Long> {

    /** Tất cả lô còn hàng (quantity > 0, isActive) của 1 thành phẩm — FIFO theo hạn sử dụng (gần nhất trước) */
    @Query("SELECT f FROM FinishedGoodsStock f " +
            "WHERE f.productName = :productName AND f.isActive = true AND f.quantity > 0 " +
            "ORDER BY CASE WHEN f.expiryDate IS NULL THEN 1 ELSE 0 END ASC, f.expiryDate ASC, f.id ASC")
    List<FinishedGoodsStock> findAvailableLotsByProductOrderByExpiryAsc(@Param("productName") String productName);

    /**
     * Như trên nhưng GIỚI HẠN trong 1 xưởng — dùng khi xuất/chuyển kho thành phẩm,
     * để không trừ nhầm tồn của xưởng khác.
     */
    @Query("SELECT f FROM FinishedGoodsStock f " +
            "WHERE f.productName = :productName AND f.factoryId = :factoryId " +
            "AND f.isActive = true AND f.quantity > 0 " +
            "ORDER BY CASE WHEN f.expiryDate IS NULL THEN 1 ELSE 0 END ASC, f.expiryDate ASC, f.id ASC")
    List<FinishedGoodsStock> findAvailableLotsByProductAndFactoryOrderByExpiryAsc(
            @Param("productName") String productName, @Param("factoryId") Long factoryId);

    /** Các lô còn thiếu factoryId (dữ liệu cũ trước bugfix) — dùng để backfill 1 lần lúc khởi động */
    List<FinishedGoodsStock> findByFactoryIdIsNull();

    /** Danh sách tên thành phẩm distinct hiện có trong kho (còn hàng) — cho dropdown filter */
    @Query("SELECT DISTINCT f.productName FROM FinishedGoodsStock f WHERE f.isActive = true AND f.quantity > 0 ORDER BY f.productName ASC")
    List<String> findDistinctProductNames();

    /** Tất cả lô (kể cả hết hàng) của 1 thành phẩm — cho trang chi tiết */
    List<FinishedGoodsStock> findByProductNameOrderByExpiryDateAsc(String productName);

    /** Tìm theo tên (search) — không phân biệt hoa thường */
    @Query("SELECT f FROM FinishedGoodsStock f " +
            "WHERE f.isActive = true AND f.quantity > 0 " +
            "AND (:q IS NULL OR LOWER(f.productName) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY f.productName ASC, f.expiryDate ASC")
    List<FinishedGoodsStock> searchActiveLots(@Param("q") String q);

    /**
     * Tìm theo tên + GIỚI HẠN theo xưởng. factoryId = null → không lọc xưởng
     * (dùng cho OWNER/ADMIN xem toàn bộ).
     */
    @Query("SELECT f FROM FinishedGoodsStock f " +
            "WHERE f.isActive = true AND f.quantity > 0 " +
            "AND (:q IS NULL OR LOWER(f.productName) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:factoryId IS NULL OR f.factoryId = :factoryId) " +
            "ORDER BY f.productName ASC, f.expiryDate ASC")
    List<FinishedGoodsStock> searchActiveLotsByFactory(@Param("q") String q,
                                                       @Param("factoryId") Long factoryId);
}