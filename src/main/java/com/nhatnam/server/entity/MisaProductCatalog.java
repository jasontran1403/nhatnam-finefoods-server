// PATH: src/main/java/com/nhatnam/server/entity/MisaProductCatalog.java
package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "misa_product_catalog",
        indexes = @Index(name = "idx_misa_cat_code", columnList = "product_code"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MisaProductCatalog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã hàng gốc (cột B) — dùng để match với mã hàng trong file FPT. */
    @Column(name = "product_code", nullable = false, length = 200)
    private String productCode;

    /** Tên hàng hóa gốc (cột C) — tên đầy đủ, dùng parse quy cách kg. */
    @Column(name = "product_name", nullable = false, length = 500)
    private String productName;

    /** ĐVT gốc (cột D) — Hộp, Thùng, Kg, Chai... */
    @Column(name = "original_unit", length = 50)
    private String originalUnit;

    /**
     * KG tương ứng 1 đơn vị NHỎ (hộp / túi / chai / gói / cái / kg...) parse từ tên.
     * NULL = chưa parse được. VD: "Bánh ... 8 hộp (1kg/hộp)" → kgPerUnit = 1.0 (1 hộp = 1 kg).
     * VD: "Đế bánh tart 525g x 12 túi/Thùng" → kgPerUnit = 0.525 (1 túi = 0.525 kg).
     */
    @Column(name = "kg_per_unit")
    private Double kgPerUnit;

    /**
     * Quy cách: số đơn vị nhỏ (hộp / túi / chai / gói...) trong 1 THÙNG.
     * NULL = không parse được / không có khái niệm thùng cho sản phẩm này.
     * VD: "Bánh ... 8 hộp (1kg/hộp)" → quyCach = 8 (8 hộp/thùng).
     * VD: "Đế bánh tart 525g x 12 túi/Thùng" → quyCach = 12 (12 túi/thùng).
     */
    @Column(name = "quy_cach")
    private Double quyCach;

    /** % VAT (cột E). */
    @Column(name = "vat_percent", length = 20)
    private String vatPercent;

    /** Mã thuế sản phẩm (cột F). */
    @Column(name = "tax_code", length = 20)
    private String taxCode;

    /** Mã hàng trong MISA (cột G) → export ra cột Z "Mã hàng". VD: KEM, BANH, GIAVI */
    @Column(name = "misa_category", length = 200)
    private String misaCategory;

    /** Tên hàng trong MISA (cột H) → export ra cột AA "Tên hàng". VD: Kem Sữa làm bánh các lọai */
    @Column(name = "misa_product_name", length = 500)
    private String misaProductName;

    /** Kho (cột I) → export ra cột AY. VD: 156, 152, 155 */
    @Column(name = "kho", length = 50)
    private String kho;

    /** TK Kho (cột J) → export ra cột BA. VD: 1561, 1551, 152 */
    @Column(name = "tk_kho", length = 50)
    private String tkKho;

    /** TK Giá vốn (cột K) → export ra cột AZ. VD: 632 */
    @Column(name = "tk_gia_von", length = 50)
    private String tkGiaVon;

    /** TK Chiết khấu (cột L) → export ra cột AN. VD: 5211 */
    @Column(name = "tk_chiet_khau", length = 50)
    private String tkChietKhau;

    /** TK Doanh thu (cột M) → export ra cột AD. VD: 5111, 5112 */
    @Column(name = "tk_doanh_thu", length = 50)
    private String tkDoanhThu;

    /** TK Thuế GTGT (cột N) → export ra cột AW. VD: 33311 */
    @Column(name = "tk_thue_gtgt", length = 50)
    private String tkThueGtgt;

    /** Ghi chú nếu không parse được quy cách. */
    @Column(name = "parse_note", length = 500)
    private String parseNote;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}