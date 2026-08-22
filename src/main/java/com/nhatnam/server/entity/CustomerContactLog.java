package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * NHẬT KÝ SELLER ĐÃ GỌI CHÀO HÀNG (màn hình "Dự báo đặt hàng").
 *
 * <p>Mỗi lần seller bấm "Đánh dấu đã gọi" ghi 1 dòng ở đây. Bản ghi KHÔNG loại khách
 * khỏi danh sách dự báo — nó chỉ đổi style hàng đó thành "đã liên hệ" kèm ngày giờ,
 * để seller khỏi gọi trùng mà vẫn nhìn thấy khách chưa chốt đơn.
 *
 * <p><b>Vì sao có cột {@link #contactDate} dạng chuỗi thay vì chỉ dùng {@link #contactedAt}?</b>
 * Ràng buộc nghiệp vụ là "một khách chỉ đánh dấu một lần MỖI NGÀY", mà "ngày" ở đây là
 * ngày theo giờ VN chứ không phải theo UTC hay theo múi giờ của server. Lưu sẵn chuỗi
 * {@code yyyy-MM-dd} đã quy đổi cho phép đặt UNIQUE index thật ở DB; nếu chỉ có epoch
 * millis thì mọi truy vấn "hôm nay đã gọi chưa" đều phải quét khoảng và không chống
 * được double-click tạo 2 bản ghi.
 */
@Entity
@Table(name = "customer_contact_logs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_contact_customer_seller_date",
                columnNames = {"customer_id", "seller_id", "contact_date"}),
        indexes = @Index(name = "idx_contact_date", columnList = "contact_date"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerContactLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    /** Ngày liên hệ theo giờ VN, định dạng {@code yyyy-MM-dd}. */
    @Column(name = "contact_date", nullable = false, length = 10)
    private String contactDate;

    /** Thời điểm chính xác (epoch millis) — dùng để hiển thị "đã gọi lúc 14:32". */
    @Column(name = "contacted_at", nullable = false)
    private Long contactedAt;

    /** Snapshot tên seller, phòng khi tài khoản bị xoá mềm. */
    @Column(name = "seller_name", length = 150)
    private String sellerName;

    @Column(name = "note", length = 300)
    private String note;
}
