package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.text.Normalizer;

/**
 * DANH MỤC VẬT DỤNG (văn phòng phẩm / đồ dùng tiêu hao).
 *
 * <p><b>Quy tắc gộp tồn kho — QUAN TRỌNG:</b> tồn kho gộp theo bộ ba
 * <b>(tên, quy cách, đơn vị tính)</b> và KHÔNG phụ thuộc nhà cung cấp.
 *
 * <pre>
 *   "Nước rửa chén / 4L/chai / Chai" mua từ 10 NCC, mỗi NCC 1 chai
 *   → kho có ĐÚNG 1 record, số lượng 10 Chai (không tách thành 10 record).
 * </pre>
 *
 * <p>Để đạt được điều đó, {@code nameNormalized} + {@code specNormalized} + {@code unit}
 * là UNIQUE. Chuẩn hoá bắt buộc dùng Unicode <b>NFC</b> vì tiếng Việt có 2 kiểu tổ hợp
 * dấu cho cùng một chữ ("ề" = 1 code point, hoặc "e" + U+0300 + U+0302).
 */
@Entity
@Table(
        name = "supply_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_supply_item_triple",
                columnNames = {"name_normalized", "spec_normalized", "unit_normalized"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tên hiển thị (giữ nguyên chữ hoa/thường người dùng nhập lần đầu). */
    @Column(nullable = false, length = 300)
    private String name;

    /** Quy cách — VD "4L/chai", "500 tờ/ram". Free text nên dễ phân mảnh → xem autocomplete. */
    @Column(length = 200)
    private String specification;

    /**
     * Đơn vị tính HIỂN THỊ — giữ đúng chữ người dùng nhập ("Chai", "Ram").
     * KHÔNG dùng cột này làm khoá: xem {@link #unitNormalized}.
     */
    @Column(nullable = false, length = 50)
    private String unit;

    @Column(name = "name_normalized", nullable = false, length = 300)
    private String nameNormalized;

    /** Chuỗi rỗng thay vì null để UNIQUE constraint hoạt động trên mọi DBMS. */
    @Column(name = "spec_normalized", nullable = false, length = 200)
    @Builder.Default
    private String specNormalized = "";

    /**
     * Đơn vị tính ĐÃ CHUẨN HOÁ — thành phần thứ ba của khoá UNIQUE.
     *
     * <p>Phải tách riêng khỏi {@link #unit}: nếu dùng chung một cột thì hoặc là
     * khoá bị phá (vì cột lưu chữ hiển thị "Chai" nhưng tra cứu bằng "chai"),
     * hoặc là giao diện phải hiện chữ thường xấu xí. Tách 2 cột giải quyết cả hai.
     */
    @Column(name = "unit_normalized", nullable = false, length = 50)
    private String unitNormalized;

    /** Soft delete — phục vụ merge (gộp 2 bản ghi bị nhập lệch). */
    @Column(name = "deleted_at")
    private Long deletedAt;

    /** Nếu đã bị merge, trỏ tới bản ghi đích để truy vết. */
    @Column(name = "merged_into_id")
    private Long mergedIntoId;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    // ── Chuẩn hoá ────────────────────────────────────────────────────────────

    /**
     * trim → gộp khoảng trắng thừa → lowercase → Unicode NFC.
     *
     * <p>NFC là bắt buộc: cùng một chữ tiếng Việt có thể được gõ ở dạng tổ hợp
     * (decomposed) hoặc dựng sẵn (precomposed); nếu không normalize thì
     * "Cà phê" gõ 2 kiểu sẽ tạo ra 2 SupplyItem khác nhau → tồn kho phân mảnh.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("\\s+", " ").toLowerCase();
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** Đơn vị tính cũng chuẩn hoá (nhưng lưu bản gốc để hiển thị). */
    public static String normalizeUnit(String raw) {
        return normalize(raw);
    }
}
