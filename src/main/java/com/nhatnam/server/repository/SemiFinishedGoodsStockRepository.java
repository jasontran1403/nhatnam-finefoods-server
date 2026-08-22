package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SemiFinishedGoodsStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SemiFinishedGoodsStockRepository extends JpaRepository<SemiFinishedGoodsStock, Long> {

    /** Tất cả lô còn hàng (quantity > 0, isActive) của 1 thành phẩm — FIFO theo lô cũ nhất trước (id ASC) */
    @Query("SELECT s FROM SemiFinishedGoodsStock s " +
            "WHERE s.productName = :productName AND s.isActive = true AND s.quantity > 0 " +
            "ORDER BY s.id ASC")
    List<SemiFinishedGoodsStock> findAvailableLotsByProductOrderByIdAsc(@Param("productName") String productName);

    /** Danh sách tên thành phẩm distinct hiện có trong kho bán thành phẩm (còn hàng) — cho dropdown chọn */
    @Query("SELECT DISTINCT s.productName FROM SemiFinishedGoodsStock s WHERE s.isActive = true AND s.quantity > 0 ORDER BY s.productName ASC")
    List<String> findDistinctProductNames();

    /** Tìm theo tên (search) — không phân biệt hoa thường, dùng cho danh sách tổng hợp */
    @Query("SELECT s FROM SemiFinishedGoodsStock s " +
            "WHERE s.isActive = true AND s.quantity > 0 " +
            "AND (:q IS NULL OR LOWER(s.productName) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY s.productName ASC, s.id ASC")
    List<SemiFinishedGoodsStock> searchActiveLots(@Param("q") String q);

    /** Lô theo batch (để hiển thị "kho bán thành phẩm" trong chi tiết lệnh/mẻ sản xuất) */
    List<SemiFinishedGoodsStock> findByBatch_Id(Long batchId);
}
