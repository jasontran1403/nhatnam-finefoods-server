package com.nhatnam.server.service;

import com.nhatnam.server.entity.FactoryMaterialStock;
import com.nhatnam.server.entity.ProductionBatch;
import com.nhatnam.server.entity.WorkOrder;
import com.nhatnam.server.entity.WorkOrderPlan;
import com.nhatnam.server.entity.WorkOrderPlanBatchMaterial;
import com.nhatnam.server.entity.WorkOrderStockDeduction;
import com.nhatnam.server.repository.WorkOrderPlanBatchMaterialRepository;
import com.nhatnam.server.repository.WorkOrderStockDeductionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Giá vốn NGUYÊN LIỆU của sản xuất.
 *
 * <p><b>Nguồn dữ liệu — không cần cấu trúc mới:</b> khi bắt đầu lệnh, hệ thống đã
 * trừ kho theo FIFO và ghi lại chính xác <i>lô nào, bao nhiêu</i> vào
 * {@link WorkOrderStockDeduction}. Mỗi lô ({@link FactoryMaterialStock}) mang giá vốn
 * 1 đơn vị lưu kho ({@code unitCost}) do kế toán trưởng chốt ở bước cuối của phiếu
 * đặt hàng. Nhân hai thứ đó lại là ra giá vốn của lệnh.
 *
 * <p><b>Vì sao TÍNH LẠI mỗi lần thay vì snapshot lúc trừ kho:</b> xưởng thường sản
 * xuất ngay sau khi nhận hàng, trong khi kế toán trưởng chốt giá + phí/thuế sau vài
 * ngày. Nếu snapshot lúc trừ kho thì giá vốn sẽ vĩnh viễn = 0. Đọc {@code unitCost}
 * của lô tại thời điểm cần dùng sẽ luôn lấy được giá mới nhất. Giá vốn chỉ ĐÓNG BĂNG
 * ở một chỗ duy nhất: lô Kho thành phẩm (xem {@code FinishedGoodsStock.totalCost}).
 *
 * <p><b>Quy ước:</b> chỉ tính nguyên liệu (không nhân công/điện/khấu hao máy).
 * Sản lượng lỗi (scrap) KHÔNG gánh giá vốn — toàn bộ chi phí dồn vào sản lượng đạt.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionCostService {

    /** Số chữ số thập phân giữ lại cho đơn giá trung gian — chỉ làm tròn ở bước cuối. */
    private static final int INTERNAL_SCALE = 6;

    private final WorkOrderStockDeductionRepository deductionRepo;
    private final WorkOrderPlanBatchMaterialRepository batchMaterialRepo;
    private final ObjectMapper objectMapper;

    // ══════════════════════════════════════════════════════════════════════
    // Cấp LỆNH SẢN XUẤT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Tổng giá vốn nguyên liệu ĐÃ TIÊU HAO của cả lệnh (đồng).
     * Số lượng tiêu hao = {@code actualUsedQty} nếu đã hoàn kho phần dư (mẻ bị hủy),
     * ngược lại = {@code deductedQty}.
     */
    public BigDecimal workOrderMaterialCost(Long workOrderId) {
        BigDecimal total = BigDecimal.ZERO;
        for (WorkOrderStockDeduction d : deductionRepo.findByWorkOrder_IdOrderByCreatedAtAsc(workOrderId)) {
            total = total.add(consumedQty(d).multiply(lotUnitCost(d)));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Giá vốn trung bình 1 đơn vị của TỪNG loại nguyên liệu trong lệnh này
     * (bình quân gia quyền qua các lô đã trừ) — key = tên NVL đã chuẩn hoá.
     *
     * <p>Cần bước này vì 1 loại NVL có thể bị trừ từ nhiều lô giá khác nhau
     * (FIFO), VD 40kg thịt lô cũ 95k + 18kg lô mới 100k.
     */
    public Map<String, BigDecimal> unitCostByMaterial(Long workOrderId) {
        Map<String, BigDecimal> qtySum = new HashMap<>();
        Map<String, BigDecimal> costSum = new HashMap<>();

        for (WorkOrderStockDeduction d : deductionRepo.findByWorkOrder_IdOrderByCreatedAtAsc(workOrderId)) {
            String key = normalize(d.getMaterialName());
            BigDecimal qty = consumedQty(d);
            qtySum.merge(key, qty, BigDecimal::add);
            costSum.merge(key, qty.multiply(lotUnitCost(d)), BigDecimal::add);
        }

        Map<String, BigDecimal> out = new HashMap<>();
        for (Map.Entry<String, BigDecimal> e : qtySum.entrySet()) {
            BigDecimal qty = e.getValue();
            out.put(e.getKey(), qty.compareTo(BigDecimal.ZERO) > 0
                    ? costSum.get(e.getKey()).divide(qty, INTERNAL_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cấp MẺ
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Giá vốn nguyên liệu phân bổ cho 1 mẻ (đồng).
     *
     * <p>Ưu tiên định lượng THEO MẺ trong phương án ({@link WorkOrderPlanBatchMaterial})
     * — chính xác cả khi các mẻ có khối lượng khác nhau. Nếu phương án không có dòng
     * theo mẻ (dữ liệu cũ) → chia giá vốn cả lệnh theo tỉ lệ sản lượng KẾ HOẠCH của mẻ.
     */
    public BigDecimal batchMaterialCost(ProductionBatch batch) {
        WorkOrder wo = batch.getWorkOrder();
        if (wo == null) return BigDecimal.ZERO;

        WorkOrderPlan plan = wo.getWorkOrderPlan();
        if (plan != null && batch.getBatchNumber() != null) {
            List<WorkOrderPlanBatchMaterial> lines = batchMaterialRepo
                    .findByPlan_IdAndBatchNumberOrderBySortOrderAsc(plan.getId(), batch.getBatchNumber());
            if (!lines.isEmpty()) {
                Map<String, BigDecimal> costByMat = unitCostByMaterial(wo.getId());
                BigDecimal total = BigDecimal.ZERO;
                for (WorkOrderPlanBatchMaterial m : lines) {
                    BigDecimal unitCost = costByMat.getOrDefault(normalize(m.getMaterialName()), BigDecimal.ZERO);
                    total = total.add(nz(m.getQty()).multiply(unitCost));
                }
                return total.setScale(2, RoundingMode.HALF_UP);
            }
        }

        // Fallback: chia theo tỉ lệ sản lượng kế hoạch của mẻ trên tổng kế hoạch
        BigDecimal woCost = workOrderMaterialCost(wo.getId());
        BigDecimal share = plannedShare(plan, batch.getBatchNumber());
        return woCost.multiply(share).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Giá vốn 1 kg bán thành phẩm của mẻ = giá vốn mẻ / sản lượng ĐẠT.
     * Giữ 6 số lẻ, không làm tròn (làm tròn ở bước nhập Kho thành phẩm).
     * Trả 0 nếu mẻ chưa có sản lượng.
     */
    public BigDecimal batchUnitCostPerKg(ProductionBatch batch) {
        BigDecimal output = nz(batch.getActualOutputQty());
        if (output.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return batchMaterialCost(batch).divide(output, INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /** Số lượng thực tiêu hao của 1 bản ghi trừ kho. */
    private BigDecimal consumedQty(WorkOrderStockDeduction d) {
        return d.getActualUsedQty() != null ? d.getActualUsedQty() : nz(d.getDeductedQty());
    }

    /** Giá vốn 1 đơn vị của lô bị trừ — 0 nếu phiếu đặt hàng chưa được chốt giá. */
    private BigDecimal lotUnitCost(WorkOrderStockDeduction d) {
        FactoryMaterialStock lot = d.getStock();
        return lot != null ? nz(lot.getUnitCost()) : BigDecimal.ZERO;
    }

    /** Tỉ lệ sản lượng kế hoạch của mẻ n trên tổng kế hoạch của lệnh (0..1). */
    private BigDecimal plannedShare(WorkOrderPlan plan, Integer batchNumber) {
        if (plan == null || batchNumber == null) return BigDecimal.ZERO;

        List<BigDecimal> qtyList = readBatchQtyList(plan);
        if (qtyList != null && !qtyList.isEmpty()) {
            BigDecimal totalQty = qtyList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            int idx = batchNumber - 1;
            if (totalQty.compareTo(BigDecimal.ZERO) > 0 && idx >= 0 && idx < qtyList.size()) {
                return qtyList.get(idx).divide(totalQty, INTERNAL_SCALE, RoundingMode.HALF_UP);
            }
        }

        int totalBatches = plan.getTotalBatches() != null && plan.getTotalBatches() > 0
                ? plan.getTotalBatches() : 1;
        return BigDecimal.ONE.divide(BigDecimal.valueOf(totalBatches), INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    private List<BigDecimal> readBatchQtyList(WorkOrderPlan plan) {
        if (plan.getBatchQtyPerRunList() == null || plan.getBatchQtyPerRunList().isBlank()) return null;
        try {
            return objectMapper.readValue(plan.getBatchQtyPerRunList(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, BigDecimal.class));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(); }
}