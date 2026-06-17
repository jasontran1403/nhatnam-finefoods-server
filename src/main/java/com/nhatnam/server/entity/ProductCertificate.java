package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product_certificate")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID sản phẩm liên quan — nullable để không bị FK lỗi khi product bị xóa */
    @Column(name = "product_id")
    private Long productId;

    /** Snapshot tên sản phẩm tại thời điểm tạo — dùng để hiển thị sau khi product bị xóa */
    @Column(name = "product_name_snapshot", length = 300)
    private String productNameSnapshot;

    /** Tên giấy chứng nhận, VD: "ISO 22000:2018", "HACCP" */
    @Column(name = "cert_name", nullable = false, length = 200)
    private String certName;

    /** Ngày cấp (epoch millis) */
    @Column(name = "issued_at", nullable = false)
    private Long issuedAt;

    /** Hạn sử dụng — có thể null (không có hạn) */
    @Column(name = "expired_at")
    private Long expiredAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "certificate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductCertificateFile> files = new ArrayList<>();
}
