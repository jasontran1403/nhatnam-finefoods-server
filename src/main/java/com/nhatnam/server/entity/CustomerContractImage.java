package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * ẢNH HỢP ĐỒNG CỦA KHÁCH HÀNG.
 *
 * <p>Một khách có thể có nhiều trang hợp đồng — mỗi trang là một bản ghi.
 * Hợp đồng là căn cứ để khách được mua CÔNG NỢ: khách chưa có hợp đồng thì
 * mọi luồng tạo đơn / đổi phương thức thanh toán đều chặn lựa chọn công nợ.
 *
 * <p><b>Cơ chế thay hợp đồng:</b> upload bộ ảnh mới sẽ đánh dấu toàn bộ bản ghi
 * cũ {@code active = false} chứ KHÔNG xoá dòng. Hợp đồng là chứng từ — giữ lại
 * dòng cũ để còn truy được ai thay, thay lúc nào, và path file cũ là gì. Việc
 * xoá file vật lý tách riêng, chưa bật (xem CustomerContractService).
 */
@Entity
@Table(name = "customer_contract_images",
       indexes = {
           @Index(name = "idx_cci_customer", columnList = "customer_id"),
           @Index(name = "idx_cci_customer_active", columnList = "customer_id, active")
       })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CustomerContractImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Đường dẫn public dạng "/images/customer-contract/xxx.png". */
    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    /** Thứ tự trang trong bộ hợp đồng, bắt đầu từ 0. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * false = đã bị thay bởi bộ hợp đồng mới.
     * Chỉ bản ghi active mới được hiển thị và mới tính là "khách có hợp đồng".
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    // ── Nhật ký: ai tải lên, lúc nào ─────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_id")
    private User uploadedBy;

    /**
     * Tên người tải lên tại THỜI ĐIỂM tải.
     *
     * <p>Lưu tách khỏi quan hệ {@link #uploadedBy} vì nhân viên có thể nghỉ việc
     * và bị xoá mềm — khi đó tên trong bản ghi user không còn tra ngược được,
     * mà nhật ký chứng từ thì phải đọc được mãi mãi.
     */
    @Column(name = "uploaded_by_name", length = 120)
    private String uploadedByName;

    @Column(name = "uploaded_at", nullable = false)
    private Long uploadedAt;

    /** Thời điểm bị bộ hợp đồng mới thay thế (null nếu đang còn hiệu lực). */
    @Column(name = "replaced_at")
    private Long replacedAt;

    @Column(name = "replaced_by_name", length = 120)
    private String replacedByName;

    @PrePersist
    void onCreate() {
        if (uploadedAt == null) uploadedAt = System.currentTimeMillis();
        if (active == null) active = true;
        if (sortOrder == null) sortOrder = 0;
    }
}
