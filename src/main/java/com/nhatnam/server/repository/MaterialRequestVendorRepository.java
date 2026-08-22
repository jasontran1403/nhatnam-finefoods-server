package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequestVendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MaterialRequestVendorRepository extends JpaRepository<MaterialRequestVendor, Long> {
    List<MaterialRequestVendor> findByMaterialRequest_Id(Long requestId);

    /** Tất cả dòng NCC có công nợ (DEBT) của 1 MaterialVendor — cho trang công nợ + trừ FIFO */
    @Query("""
        SELECT v FROM MaterialRequestVendor v
        WHERE v.vendor.id = :vendorId
          AND CAST(v.paymentStatus AS string) = 'DEBT'
          AND CAST(v.debtSettlementStatus AS string) <> 'SETTLED'
        ORDER BY v.debtSince ASC
    """)
    List<MaterialRequestVendor> findUnsettledDebtsByVendorOrderByDebtSinceAsc(@Param("vendorId") Long vendorId);

    /** Toàn bộ lịch sử công nợ (DEBT) của 1 NCC — kể cả đã trả hết, để xem lịch sử */
    @Query("""
        SELECT v FROM MaterialRequestVendor v
        WHERE v.vendor.id = :vendorId
          AND CAST(v.paymentStatus AS string) = 'DEBT'
        ORDER BY v.debtSince DESC
    """)
    List<MaterialRequestVendor> findAllDebtHistoryByVendor(@Param("vendorId") Long vendorId);

    /** Danh sách distinct vendorId đang có công nợ chưa trả hết — dùng để build trang công nợ */
    @Query("""
        SELECT DISTINCT v.vendor.id FROM MaterialRequestVendor v
        WHERE v.vendor IS NOT NULL
          AND CAST(v.paymentStatus AS string) = 'DEBT'
          AND CAST(v.debtSettlementStatus AS string) <> 'SETTLED'
    """)
    List<Long> findVendorIdsWithOutstandingDebt();

    /** Tất cả lô (mọi trạng thái) của 1 NCC — dùng đếm số lần đặt hàng */
    List<MaterialRequestVendor> findByVendor_Id(Long vendorId);

    long countByVendor_Id(Long vendorId);
}
