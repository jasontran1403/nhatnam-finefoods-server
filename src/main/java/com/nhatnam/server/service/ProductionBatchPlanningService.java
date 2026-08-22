package com.nhatnam.server.service;

import com.nhatnam.server.entity.ProductionRecipe;
import com.nhatnam.server.entity.ProductionRecipeItem;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tính toán chia mẻ + nguyên liệu cho lệnh sản xuất dựa trên 1 biến thể
 * (ProductionRecipe) đã chọn và sản lượng cần sản xuất (requestedQty).
 *
 * QUY TẮC (đã xác nhận với nghiệp vụ):
 * 1. Số mẻ = ceil(requestedQty / standardOutputQty), tối thiểu 1.
 * 2. Sản lượng từng mẻ: các mẻ đầu = standardOutputQty (100%), mẻ cuối =
 *    phần dư (requestedQty - (totalBatches-1) * standardOutputQty).
 *    Ví dụ: chuẩn 30kg, lệnh 45kg → 2 mẻ: mẻ1=30kg (100%), mẻ2=15kg (50%).
 * 3. Nguyên liệu mỗi mẻ = nguyên liệu chuẩn của biến thể × (sản lượng mẻ đó / standardOutputQty),
 *    làm tròn theo loại đơn vị:
 *      - Đơn vị đo lường liên tục (kg, g, lít, ml, m, cm...) → giữ 2 số lẻ thập phân (HALF_UP).
 *      - Đơn vị đếm được (túi, hộp, chai, lọ, can, cái...) → làm tròn LÊN (CEILING), tối thiểu 1
 *        nếu số lượng > 0.
 *    Mỗi mẻ được tính + làm tròn RIÊNG BIỆT (không gộp rồi làm tròn 1 lần) — vì mỗi mẻ
 *    là 1 thực thể sản xuất độc lập, cần số liệu chính xác cho riêng mẻ đó.
 * 4. Tổng nguyên liệu cho cả lệnh = cộng tổng nguyên liệu (đã làm tròn riêng) của tất cả các mẻ.
 * 5. Các bước (steps) giữ nguyên thời gian gốc của biến thể cho MỖI mẻ — không nhân giãn theo
 *    sản lượng; số mẻ tăng đồng nghĩa các bước được lặp lại nhiều lần (1 lần / mẻ).
 */
@Service
public class ProductionBatchPlanningService {

    /**
     * Đơn vị ĐẾM ĐƯỢC (rời, nguyên) — làm tròn LÊN. Mọi đơn vị khác coi là đo
     * lường liên tục → giữ số lẻ.
     *
     * <p>Liệt kê cả bản CÓ DẤU và KHÔNG DẤU vì đơn vị được lưu nguyên chuỗi
     * người dùng nhập, không qua danh mục chuẩn hoá nào.
     *
     * <p>"bó" nằm ở đây: không thể lấy 0,4 bó hành, cần 0,4 thì phải mở nguyên
     * một bó. "mét" thì KHÔNG — cắt được 2,5 mét màng bọc, làm tròn lên thành 3
     * sẽ tính dư nguyên liệu cho mọi mẻ.
     */
    private static final Set<String> COUNTABLE_UNITS = Set.of(
            "túi", "tui", "hộp", "hop", "chai", "lọ", "lo", "can", "cái", "cai",
            "bao", "gói", "goi", "thùng", "thung", "lon", "vỉ", "vi", "cuộn", "cuon",
            "bó", "bo"
    );

    /**
     * Tính toán đầy đủ kế hoạch các mẻ cho 1 biến thể + sản lượng yêu cầu.
     */
    public PlanCalculation calculate(ProductionRecipe recipe, BigDecimal requestedQty) {
        if (recipe == null) throw new IllegalArgumentException("Chưa chọn biến thể sản xuất");
        if (requestedQty == null || requestedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Sản lượng cần sản xuất phải lớn hơn 0");
        }
        BigDecimal standardQty = recipe.getStandardOutputQty();
        if (standardQty == null || standardQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Biến thể chưa có sản lượng chuẩn hợp lệ");
        }

        // ── Bước 1: số mẻ = ceil(requested / standard), tối thiểu 1 ──────────
        int totalBatches = requestedQty
                .divide(standardQty, 10, RoundingMode.CEILING)
                .setScale(0, RoundingMode.CEILING)
                .max(BigDecimal.ONE)
                .intValue();

        // ── Bước 2: phân bổ sản lượng từng mẻ (mẻ đầu = chuẩn, mẻ cuối = phần dư) ──
        List<BigDecimal> batchQtys = new ArrayList<>();
        BigDecimal remaining = requestedQty;
        for (int i = 0; i < totalBatches; i++) {
            boolean isLast = (i == totalBatches - 1);
            BigDecimal qty = isLast ? remaining : standardQty;
            // Phòng trường hợp số học dư/thiếu nhỏ do làm tròn — mẻ cuối luôn nhận đúng phần còn lại
            if (qty.compareTo(BigDecimal.ZERO) <= 0) qty = standardQty;
            batchQtys.add(qty.setScale(3, RoundingMode.HALF_UP));
            remaining = remaining.subtract(qty);
        }

        // ── Bước 3+4: nguyên liệu từng mẻ (làm tròn riêng) + tổng cộng ────────
        List<BatchPlan> batches = new ArrayList<>();
        for (int i = 0; i < totalBatches; i++) {
            BigDecimal batchQty = batchQtys.get(i);
            BigDecimal ratio = batchQty.divide(standardQty, 10, RoundingMode.HALF_UP);

            List<MaterialLine> lines = new ArrayList<>();
            int order = 0;
            for (ProductionRecipeItem item : recipe.getItems()) {
                BigDecimal rawQty = item.getStandardQty().multiply(ratio);
                BigDecimal roundedQty = roundByUnit(rawQty, item.getUnit());
                lines.add(MaterialLine.builder()
                        .factoryMaterialId(item.getFactoryMaterial().getId())
                        .materialName(item.getFactoryMaterial().getName())
                        .unit(item.getUnit())
                        .qty(roundedQty)
                        .sortOrder(order++)
                        .build());
            }
            batches.add(BatchPlan.builder()
                    .batchNumber(i + 1)
                    .outputQty(batchQty)
                    .materials(lines)
                    .build());
        }

        // Tổng nguyên liệu cho cả lệnh = cộng tổng các dòng theo factoryMaterialId
        List<MaterialLine> totals = new ArrayList<>();
        for (ProductionRecipeItem item : recipe.getItems()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BatchPlan bp : batches) {
                for (MaterialLine line : bp.getMaterials()) {
                    if (line.getFactoryMaterialId().equals(item.getFactoryMaterial().getId())) {
                        sum = sum.add(line.getQty());
                    }
                }
            }
            totals.add(MaterialLine.builder()
                    .factoryMaterialId(item.getFactoryMaterial().getId())
                    .materialName(item.getFactoryMaterial().getName())
                    .unit(item.getUnit())
                    .qty(sum)
                    .sortOrder(item.getSortOrder() != null ? item.getSortOrder() : 0)
                    .build());
        }

        return PlanCalculation.builder()
                .totalBatches(totalBatches)
                .standardOutputQty(standardQty)
                .requestedQty(requestedQty)
                .batches(batches)
                .totalMaterials(totals)
                .build();
    }

    /**
     * Làm tròn theo loại đơn vị:
     * - Đếm được (túi/hộp/chai/lọ/can...) → CEILING, số nguyên.
     * - Đo lường liên tục (kg/g/lít/m...) → giữ 2 số lẻ, HALF_UP.
     */
    private BigDecimal roundByUnit(BigDecimal rawQty, String unit) {
        if (rawQty == null) return BigDecimal.ZERO;
        if (isCountableUnit(unit)) {
            return rawQty.setScale(0, RoundingMode.CEILING);
        }
        return rawQty.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isCountableUnit(String unit) {
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase();
        return COUNTABLE_UNITS.contains(normalized);
    }

    // ── DTOs nội bộ kết quả tính toán ────────────────────────────────────────

    @Data @Builder
    public static class PlanCalculation {
        private int totalBatches;
        private BigDecimal standardOutputQty;
        private BigDecimal requestedQty;
        private List<BatchPlan> batches;
        private List<MaterialLine> totalMaterials;
    }

    @Data @Builder
    public static class BatchPlan {
        private int batchNumber;
        private BigDecimal outputQty;
        private List<MaterialLine> materials;
    }

    @Data @Builder
    public static class MaterialLine {
        private Long factoryMaterialId;
        private String materialName;
        private String unit;
        private BigDecimal qty;
        private int sortOrder;
    }
}
