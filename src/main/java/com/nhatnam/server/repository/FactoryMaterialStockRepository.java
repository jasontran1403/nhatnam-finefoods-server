package com.nhatnam.server.repository;

import com.nhatnam.server.entity.FactoryMaterialStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FactoryMaterialStockRepository extends JpaRepository<FactoryMaterialStock, Long> {

    List<FactoryMaterialStock> findByIsActiveTrueOrderByCreatedAtAsc();

    List<FactoryMaterialStock> findByMaterialNameAndUnitAndIsActiveTrueOrderByCreatedAtAsc(
            String materialName, String unit);

    /** Mọi lô (bất kể trạng thái) khớp tên+đơn vị — dùng cập nhật snapshot khi sửa nguyên liệu (Mục 6). */
    List<FactoryMaterialStock> findByMaterialNameAndUnit(String materialName, String unit);

    /** Tồn kho đang hoạt động của 1 xưởng (kho xưởng riêng) — Mục 4.2 / tồn kho theo xưởng. */
    List<FactoryMaterialStock> findByProductionFactory_IdAndIsActiveTrueOrderByCreatedAtAsc(Long factoryId);

    /**
     * Các lô sinh ra từ 1 dòng phiếu đặt hàng — dùng để ghi giá vốn xuống lô khi kế
     * toán trưởng Hoàn thành phiếu (kể cả lô đã dùng hết, isActive = false, vì giá
     * vốn của lô đã tiêu hao vẫn cần để tính giá vốn lệnh sản xuất).
     */
    List<FactoryMaterialStock> findByMaterialRequestItem_Id(Long materialRequestItemId);

    /** Các lô CHƯA có giá vốn nhưng ĐÃ gắn dòng phiếu đặt hàng (dùng cho backfill dữ liệu cũ). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT s FROM FactoryMaterialStock s
        WHERE s.materialRequestItem IS NOT NULL
          AND (s.unitCost IS NULL OR s.unitCost = 0)
    """)
    List<FactoryMaterialStock> findLotsMissingUnitCost();
}