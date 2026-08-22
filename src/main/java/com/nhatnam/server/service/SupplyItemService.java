package com.nhatnam.server.service;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.common.ResourceNotFoundException;
import com.nhatnam.server.dto.supply.SupplyDtos.*;
import com.nhatnam.server.entity.SupplyItem;
import com.nhatnam.server.entity.SupplyStock;
import com.nhatnam.server.entity.SupplyStockTransaction;
import com.nhatnam.server.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * DANH MỤC VẬT DỤNG + QUY TẮC GỘP TỒN KHO.
 *
 * <p>Toàn bộ giá trị của tính năng này nằm ở chỗ: cùng một mặt hàng mua từ
 * nhiều NCC khác nhau vẫn phải cho ra ĐÚNG MỘT dòng tồn kho. Hai cơ chế bảo vệ:
 * <ol>
 *   <li><b>Chuẩn hoá + UNIQUE bộ ba</b> — {@link #getOrCreate} (phòng ngừa).</li>
 *   <li><b>Merge thủ công</b> — {@link #merge} (chữa cháy khi đã lỡ phân mảnh).</li>
 * </ol>
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class SupplyItemService {

    private final SupplyItemRepository itemRepo;
    private final SupplyStockRepository stockRepo;
    private final SupplyStockTransactionRepository txRepo;
    private final EntityManager em;

    // ══════════════════════════════════════════════════════════════════════════
    //  getOrCreate — TRÁI TIM của quy tắc gộp
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tìm hoặc tạo {@link SupplyItem} theo bộ ba (tên, quy cách, ĐVT).
     *
     * <p>Được gọi khi Owner lưu một {@code VendorExpenseCategory} loại
     * {@code CONSUMABLE}. Vì khoá là bộ ba ĐÃ CHUẨN HOÁ và KHÔNG chứa NCC nên
     * "Nước rửa chén / 4L/chai / Chai" của 10 NCC khác nhau đều trỏ về cùng 1 id.
     *
     * <p>Chuẩn hoá: {@code trim} → gộp khoảng trắng thừa → {@code lowercase} →
     * Unicode <b>NFC</b>. NFC là bắt buộc chứ không phải tuỳ chọn: tiếng Việt có
     * 2 kiểu tổ hợp dấu cho cùng một chữ, không normalize thì 2 chuỗi trông y hệt
     * nhau trên màn hình lại là 2 khoá khác nhau trong DB.
     */
    @Transactional
    public SupplyItem getOrCreate(String name, String specification, String unit) {
        if (name == null || name.isBlank())
            throw new BusinessException("Tên vật dụng là bắt buộc");
        if (unit == null || unit.isBlank())
            throw new BusinessException("Đơn vị tính là bắt buộc cho đồ dùng tiêu hao");

        String nameNorm = SupplyItem.normalize(name);
        String specNorm = SupplyItem.normalize(specification);
        String unitNorm = SupplyItem.normalizeUnit(unit);

        Optional<SupplyItem> existing = itemRepo
                .findByNameNormalizedAndSpecNormalizedAndUnitNormalizedAndDeletedAtIsNull(
                        nameNorm, specNorm, unitNorm);
        if (existing.isPresent()) return existing.get();

        SupplyItem created = SupplyItem.builder()
                // 3 cột hiển thị giữ nguyên chữ người dùng nhập (chỉ gọn khoảng trắng)
                .name(squash(name))
                .specification(specification == null || specification.isBlank() ? null : squash(specification))
                .unit(squash(unit))
                // 3 cột khoá dùng bản đã chuẩn hoá
                .nameNormalized(nameNorm)
                .specNormalized(specNorm)
                .unitNormalized(unitNorm)
                .build();
        try {
            return itemRepo.saveAndFlush(created);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // 2 request cùng lúc cùng bộ ba → một cái thua UNIQUE.
            // Đây là kết quả ĐÚNG (chỉ được tồn tại 1 bản ghi), nên đọc lại bản
            // của người thắng thay vì ném lỗi ra ngoài.
            return itemRepo
                    .findByNameNormalizedAndSpecNormalizedAndUnitNormalizedAndDeletedAtIsNull(
                            nameNorm, specNorm, unitNorm)
                    .orElseThrow(() -> race);
        }
    }

    /** trim + gộp khoảng trắng thừa, GIỮ NGUYÊN chữ hoa/thường để hiển thị. */
    private static String squash(String s) {
        return s == null ? null : s.trim().replaceAll("\\s+", " ");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Autocomplete — biện pháp chính chống phân mảnh
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@code GET /supply-items/suggest?q=}. Owner gõ tên → gợi ý các vật dụng đã
     * có. FE khi chọn một gợi ý phải TỰ ĐIỀN tên + quy cách + ĐVT rồi
     * <b>KHOÁ 3 ô đó</b>; chỉ khi bấm "Tạo mới" mới cho gõ tay.
     *
     * <p>Bảo vệ quan trọng nhất là cho {@code specification} — đây là free text
     * nên là nguồn phân mảnh lớn nhất ("4L/chai" vs "4 lít/chai" vs "4l/ chai").
     */
    @Transactional(readOnly = true)
    public List<SupplyItemSuggestDto> suggest(String q) {
        return itemRepo.suggest(q == null ? "" : q.trim()).stream()
                .limit(30)
                .map(s -> SupplyItemSuggestDto.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .specification(s.getSpecification())
                        .unit(s.getUnit())
                        .label(buildLabel(s))
                        .build())
                .toList();
    }

    private String buildLabel(SupplyItem s) {
        StringBuilder sb = new StringBuilder(s.getName());
        if (s.getSpecification() != null && !s.getSpecification().isBlank())
            sb.append(" — ").append(s.getSpecification());
        sb.append(" (").append(s.getUnit()).append(")");
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public List<SupplyItemDto> listAll() {
        List<SupplyItem> items = itemRepo.findByDeletedAtIsNullOrderByNameAsc();
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (SupplyItem it : items) {
            BigDecimal sum = stockRepo.findBySupplyItemId(it.getId()).stream()
                    .map(SupplyStock::getQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            totals.put(it.getId(), sum);
        }
        return items.stream()
                .map(s -> SupplyItemDto.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .specification(s.getSpecification())
                        .unit(s.getUnit())
                        .totalQuantity(totals.getOrDefault(s.getId(), BigDecimal.ZERO))
                        .build())
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MERGE THỦ CÔNG (Owner) — chữa cháy khi đã lỡ phân mảnh
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@code POST /supply-items/{sourceId}/merge-into/{targetId}}.
     *
     * <p>Trong MỘT transaction:
     * <ol>
     *   <li>Cộng dồn {@code SupplyStock} theo TỪNG KHO (kho A của source vào kho A
     *       của target — không được gộp chéo kho, vì số liệu 2 kho là độc lập).</li>
     *   <li>Chuyển toàn bộ {@code SupplyStockTransaction} sang target.</li>
     *   <li>Cập nhật {@code supplyItemId} của các {@code VendorExpenseCategory}
     *       và các dòng phiếu đã phát sinh.</li>
     *   <li>Soft-delete source.</li>
     * </ol>
     */
    @Transactional
    public void merge(Long sourceId, Long targetId, String performedByName) {
        if (Objects.equals(sourceId, targetId))
            throw new BusinessException("Không thể gộp một vật dụng vào chính nó");

        SupplyItem source = itemRepo.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vật dụng nguồn"));
        SupplyItem target = itemRepo.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vật dụng đích"));
        if (source.getDeletedAt() != null)
            throw new BusinessException("Vật dụng nguồn đã bị gộp/xoá trước đó");
        if (target.getDeletedAt() != null)
            throw new BusinessException("Không thể gộp vào một vật dụng đã bị xoá");

        long now = System.currentTimeMillis();

        // 1) Cộng dồn tồn theo TỪNG KHO
        for (SupplyStock src : stockRepo.findBySupplyItemId(sourceId)) {
            BigDecimal qty = src.getQuantity() == null ? BigDecimal.ZERO : src.getQuantity();
            SupplyStock dst = stockRepo
                    .findByWarehouseIdAndSupplyItemId(src.getWarehouseId(), targetId)
                    .orElseGet(() -> SupplyStock.builder()
                            .warehouseId(src.getWarehouseId())
                            .supplyItemId(targetId)
                            .quantity(BigDecimal.ZERO)
                            .build());
            dst.setQuantity(dst.getQuantity().add(qty));
            stockRepo.save(dst);

            // Dấu vết kiểm toán: OUT khỏi source, IN vào target — để lịch sử kho
            // vẫn cân bằng sau khi merge.
            if (qty.compareTo(BigDecimal.ZERO) != 0) {
                txRepo.save(SupplyStockTransaction.builder()
                        .warehouseId(src.getWarehouseId()).supplyItemId(sourceId)
                        .type(SupplyStockTransaction.TxType.OUT)
                        .quantity(qty).balanceAfter(BigDecimal.ZERO)
                        .refType("MERGE").refId(targetId)
                        .note("Gộp vào vật dụng #" + targetId + " (" + target.getName() + ")")
                        .performedByName(performedByName).createdAt(now).build());
                txRepo.save(SupplyStockTransaction.builder()
                        .warehouseId(src.getWarehouseId()).supplyItemId(targetId)
                        .type(SupplyStockTransaction.TxType.IN)
                        .quantity(qty).balanceAfter(dst.getQuantity())
                        .refType("MERGE").refId(sourceId)
                        .note("Nhận gộp từ vật dụng #" + sourceId + " (" + source.getName() + ")")
                        .performedByName(performedByName).createdAt(now).build());
            }
            src.setQuantity(BigDecimal.ZERO);
            stockRepo.save(src);
        }

        // 2) Chuyển lịch sử giao dịch (trừ 2 dòng MERGE vừa tạo)
        em.createQuery("""
                UPDATE SupplyStockTransaction t SET t.supplyItemId = :target
                WHERE t.supplyItemId = :source AND t.refType <> 'MERGE'
                """)
                .setParameter("target", targetId)
                .setParameter("source", sourceId)
                .executeUpdate();

        // 3) Trỏ lại danh mục khoản chi + các dòng phiếu đã phát sinh
        em.createQuery("UPDATE VendorExpenseCategory c SET c.supplyItemId = :target WHERE c.supplyItemId = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
        em.createQuery("UPDATE MaterialRequestItem i SET i.supplyItemId = :target WHERE i.supplyItemId = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();

        // 4) Soft delete source
        source.setDeletedAt(now);
        source.setMergedIntoId(targetId);
        itemRepo.save(source);

        log.info("Merged SupplyItem {} -> {} by {}", sourceId, targetId, performedByName);
    }
}
