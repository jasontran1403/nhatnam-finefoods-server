package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequestItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MaterialRequestItemRepository extends JpaRepository<MaterialRequestItem, Long> {
    List<MaterialRequestItem> findByMaterialRequest_Id(Long requestId);

    /**
     * Toàn bộ dòng nguyên liệu do 1 NCC (MaterialVendor) cung cấp — dùng cho
     * trang Quản lý nhà cung cấp: lịch sử đặt hàng + phân tích giá.
     * Join fetch để tránh N+1 khi đọc request/vendor.
     */
    @Query("""
        SELECT i FROM MaterialRequestItem i
        JOIN FETCH i.suppliedByVendor rv
        JOIN FETCH rv.materialRequest mr
        WHERE rv.vendor.id = :vendorId
        ORDER BY mr.completedAt DESC, mr.createdAt DESC, i.sortOrder ASC
    """)
    List<MaterialRequestItem> findAllSuppliedByVendor(@Param("vendorId") Long vendorId);

    /**
     * Tất cả dòng nguyên liệu (đã có đơn giá) trùng TÊN nguyên liệu — trên MỌI nhà
     * cung cấp. Dùng cho trang Phân tích giá (gộp giá đa-NCC theo tên nguyên liệu).
     * So khớp tên không phân biệt hoa/thường + trim khoảng trắng.
     */
    @Query("""
        SELECT i FROM MaterialRequestItem i
        JOIN FETCH i.suppliedByVendor rv
        JOIN FETCH rv.materialRequest mr
        LEFT JOIN FETCH rv.vendor v
        WHERE i.unitPrice IS NOT NULL
          AND LOWER(TRIM(i.materialName)) = :name
        ORDER BY mr.completedAt DESC, mr.createdAt DESC, i.sortOrder ASC
    """)
    List<MaterialRequestItem> findAllPricedByMaterialName(@Param("name") String name);

    /**
     * TẤT CẢ dòng nguyên liệu đã có đơn giá (mọi NCC, mọi phiếu) — dùng cho trang
     * "Phân tích danh mục chi" để gom nhóm theo tên nguyên liệu và tính
     * giá thấp/cao/gần nhất + tổng tiền đã mua.
     */
    @Query("""
        SELECT i FROM MaterialRequestItem i
        JOIN FETCH i.suppliedByVendor rv
        JOIN FETCH rv.materialRequest mr
        LEFT JOIN FETCH rv.vendor v
        WHERE i.unitPrice IS NOT NULL
    """)
    List<MaterialRequestItem> findAllPriced();
}