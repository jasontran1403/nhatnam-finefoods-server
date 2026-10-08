package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FEEDBACK CỦA KHÁCH HÀNG VỀ MỘT SẢN PHẨM TRONG MỘT ĐƠN HÀNG.
 *
 * <p>SUPER_SELLER / SELLER / WAREHOUSE tạo (khi tiếp nhận phản hồi từ KH);
 * OWNER / ADMIN xem danh sách để nắm chất lượng SP.
 *
 * <p>Thiết kế:
 * <ul>
 *   <li>Một feedback = MỘT sản phẩm trong MỘT đơn. Muốn feedback nhiều SP thì
 *       tạo nhiều bản ghi — đơn giản, mỗi row hiện được 1 card như yêu cầu UI.</li>
 *   <li>Contact (tên/SĐT) tách khỏi customer của đơn — form mặc định điền từ
 *       customer nhưng người dùng có thể chỉnh sang liên hệ khác (như người
 *       gọi báo lỗi thay mặt KH), do đó lưu vào cột riêng để giữ lịch sử đúng.</li>
 *   <li>{@code createdAt} kiểu {@code Long} (epoch ms) — cùng convention với
 *       {@link GiftOrder}, {@link Order} để FE dùng {@code formatDateTime} nhất quán.</li>
 * </ul>
 */
@Entity
@Table(name = "feedback", indexes = {
        @Index(name = "idx_feedback_order",     columnList = "order_id"),
        @Index(name = "idx_feedback_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Đơn tham chiếu ────────────────────────────────────────────────────────
    // Giữ cả FK và snapshot orderCode: FK để join khi cần thông tin đơn (KH, SĐT),
    // snapshot để list hiển thị mã đơn cả khi đơn bị xoá về sau (không mất context).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "order_code", length = 32)
    private String orderCode;

    // ── Sản phẩm ─────────────────────────────────────────────────────────────
    // productId chỉ là snapshot tham chiếu, KHÔNG có FK constraint. Sản phẩm có
    // thể bị xoá về sau, hoặc feedback có thể là SP không có trong catalog
    // (như "hộp bao bì bị rách" — chọn 1 SP đại diện). Không FK giúp bền vững.
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    // ── Contact — mặc định từ customer của đơn nhưng người dùng có thể sửa ───
    @Column(name = "contact_name", length = 255)
    private String contactName;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    // ── Nội dung ─────────────────────────────────────────────────────────────
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Ảnh đính kèm — lưu dạng chuỗi các URL cách nhau bởi ký tự newline ({@code "\n"}).
     * VD: {@code "/images/feedback/abc.jpg\n/images/feedback/def.jpg"}.
     *
     * <p>Chọn TEXT + newline thay vì {@code @ElementCollection} vì (1) không cần
     * join phụ khi list feedback, (2) thứ tự ảnh giữ đúng thứ tự chèn, (3) 1 feedback
     * hiếm khi >10 ảnh nên không cần index. Service tự split/join khi vào/ra DTO.
     */
    @Lob
    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    // ── Meta ─────────────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** Snapshot tên người tạo — hiển thị được ngay cả khi user bị disable/xoá. */
    @Column(name = "created_by_name", length = 255)
    private String createdByName;

    /** Snapshot tên KH — hiển thị được ngay cả khi order/customer đã bị xoá. */
    @Column(name = "customer_name", length = 255)
    private String customerName;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}
