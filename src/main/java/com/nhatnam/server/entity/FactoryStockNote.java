package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * SỔ PHIẾU KHO NGUYÊN LIỆU XƯỞNG.
 *
 * <p>Trước đây kho nguyên liệu xưởng ({@link FactoryMaterialStock}) chỉ có lô tồn +
 * phiếu đặt hàng ({@link MaterialRequest}), KHÔNG có phiếu nhập/xuất/chuyển nào để
 * ghi nhận biến động. Entity này là "sổ cái" cho kho xưởng, phục vụ:
 *   - Mục 2: chuyển nguyên liệu xưởng ⇄ kho khác (bán/trung chuyển/xưởng khác).
 *   - Mục 4: mix gia vị (1 phiếu xuất nguyên liệu đầu vào + 1 phiếu nhập sản phẩm ra).
 *   - Cột nhập/bán/xuất của kho xưởng ở trang Kho hàng (OwnerInventoryPage).
 *
 * <p>Mỗi phiếu gắn với 1 xưởng nguồn ({@link #factory}) và có nhiều dòng
 * ({@link FactoryStockNoteLine}). Phiếu chuyển kho tạo cặp OUT ở nguồn + (nếu đích
 * cũng là kho xưởng) IN ở đích, liên kết qua {@link #linkedNoteId}.
 */
@Entity
@Table(name = "factory_stock_note")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryStockNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã phiếu tự sinh: FIM/FEX/FTO/FTI + yyyyMMdd + seq */
    @Column(name = "note_code", nullable = false, length = 40)
    private String noteCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoteType type;

    /** Xưởng sở hữu phiếu (kho nguyên liệu xưởng nguồn) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id", nullable = false)
    private ProductionFactory factory;

    @Column(name = "factory_name", length = 200)
    private String factoryName;

    /**
     * Loại kho ĐÍCH khi chuyển kho (null nếu IMPORT/EXPORT thuần):
     * WAREHOUSE (kho bán/trung chuyển) hoặc FACTORY_MATERIAL / FACTORY_FINISHED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", length = 30)
    private TargetKind targetKind;

    /** ID kho đích (Warehouse.id hoặc ProductionFactory.id tuỳ targetKind) */
    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    /** Lý do (EXPORT) hoặc nội dung phiếu (VD "Xuất kho sản xuất" khi mix) */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /** Ảnh chứng từ — JSON array URL (optional) */
    @Column(name = "document_images", columnDefinition = "TEXT")
    @Builder.Default
    private String documentImages = "[]";

    /** Phiếu đối ứng (OUT ↔ IN) khi chuyển giữa 2 kho xưởng */
    @Column(name = "linked_note_id")
    private Long linkedNoteId;

    /** Tổng giá trị vốn của phiếu (đồng) — để dựng báo cáo dòng chảy */
    @Column(name = "total_cost_value", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalCostValue = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @OneToMany(mappedBy = "note", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<FactoryStockNoteLine> lines = new ArrayList<>();

    public enum NoteType {
        IMPORT,        // nhập kho xưởng (mix ra / nhận từ kho khác)
        EXPORT,        // xuất kho có lý do
        TRANSFER_OUT,  // chuyển đi
        TRANSFER_IN    // chuyển đến (đích là kho xưởng khác)
    }

    public enum TargetKind {
        WAREHOUSE,          // kho bán / trung chuyển (Warehouse)
        FACTORY_MATERIAL,   // kho nguyên liệu xưởng khác (ProductionFactory)
        FACTORY_FINISHED    // kho thành phẩm xưởng (ProductionFactory)
    }
}
