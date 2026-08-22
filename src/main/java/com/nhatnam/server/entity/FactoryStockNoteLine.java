package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 1 dòng nguyên liệu trong {@link FactoryStockNote}.
 * Snapshot tên/đơn vị/giá vốn tại thời điểm lập phiếu để không phụ thuộc lô nguồn.
 */
@Entity
@Table(name = "factory_stock_note_line")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FactoryStockNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    private FactoryStockNote note;

    @Column(name = "material_name", nullable = false, length = 200)
    private String materialName;

    @Column(nullable = false, length = 50)
    private String unit;

    /** Số lượng (dương; ý nghĩa +/- suy từ note.type) */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    /** Giá vốn / đơn vị (đồng) — bình quân của các lô đã trừ (OUT) hoặc của lô mới (IN) */
    @Column(name = "unit_cost", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal unitCost = BigDecimal.ZERO;

    /** HSD của lô liên quan (ms) — dùng khi IN để tạo/hiển thị lô mới */
    @Column(name = "expiry_date")
    private Long expiryDate;
}
