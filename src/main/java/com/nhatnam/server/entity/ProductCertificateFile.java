package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "product_certificate_file")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCertificateFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certificate_id", nullable = false)
    private ProductCertificate certificate;

    /** Đường dẫn file — VD: /images/certificate/cert_xxx.pdf */
    @Column(name = "file_url", nullable = false, length = 500)
    private String fileUrl;

    /** Tên file gốc khi upload */
    @Column(name = "original_name", length = 300)
    private String originalName;

    /** "image" hoặc "pdf" */
    @Column(name = "file_type", length = 20)
    private String fileType;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
