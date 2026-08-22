package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Một dòng giá/phí trong bước "Hoàn thành phiếu" (thanh toán) của SUPER_ACCOUNTANT.
 *
 * Thay vì nhập trực tiếp đơn giá cho từng nguyên liệu, kế toán nhập:
 *   - "Giá nguyên liệu" — tổng tiền nguyên liệu, RIÊNG cho từng dòng nguyên liệu
 *     (mỗi MaterialRequestItem có đúng 1 dòng loại MATERIAL, scope = item đó).
 *   - Các loại phí/thuế tuỳ chỉnh (CUSTOM) — có thể áp dụng CHUNG cho nhiều
 *     nguyên liệu cùng lúc (ví dụ thuế hải quan, phí lưu kho áp dụng chung cho
 *     cả lô hàng nhập khẩu gồm nhiều sản phẩm) — không cần nhập lại cho từng
 *     dòng. Số tiền phí/thuế chung sẽ được phân bổ ngược lại theo tỷ trọng giá
 *     nguyên liệu của từng dòng nằm trong scope để tính ra đơn giá/đơn vị.
 *
 * appliesToItemIds: JSON array các MaterialRequestItem.id mà dòng giá/phí này
 * áp dụng. Đối với loại MATERIAL luôn chỉ có 1 item. Đối với CUSTOM có thể có
 * nhiều item (chia sẻ) hoặc 1 item (riêng cho 1 nguyên liệu).
 */
@Entity
@Table(name = "material_request_cost_entry")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequestCostEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id", nullable = false)
    private MaterialRequest materialRequest;

    /** MATERIAL: giá nguyên liệu riêng cho 1 item. CUSTOM: phí/thuế tuỳ chỉnh do kế toán đặt tên. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntryType type;

    /** Tên hiển thị — "Giá nguyên liệu" cho MATERIAL, hoặc tên do kế toán nhập cho CUSTOM (VD: "Thuế hải quan") */
    @Column(nullable = false, length = 200)
    private String label;

    /** Tổng số tiền của dòng giá/phí này (VNĐ) */
    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal amount;

    /** JSON array các MaterialRequestItem.id mà dòng này áp dụng — VD: "[12,13,14]" */
    @Column(name = "applies_to_item_ids", nullable = false, columnDefinition = "TEXT")
    private String appliesToItemIds;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    public enum EntryType { MATERIAL, CUSTOM }
}
