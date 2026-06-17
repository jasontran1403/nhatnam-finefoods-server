package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nhà cung cấp — dùng trong phiếu chi, phương án sản xuất, v.v.
 */
@Entity
@Table(name = "material_vendor")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialVendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 300)
    private String contactPerson;

    @Column(length = 30)
    private String contactPhone;

    /** Loại nhà cung cấp */
    @Enumerated(EnumType.STRING)
    @Column(name = "vendor_type", length = 30)
    @Builder.Default
    private VendorType vendorType = VendorType.MATERIAL;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public enum VendorType {
        MATERIAL,    // NCC nguyên liệu
        MACHINE,     // NCC máy móc
        REPAIR,      // NCC sửa chữa
        ELECTRICITY, // Điện
        WATER,       // Nước
        GAS,         // Gas
        LOGISTICS,   // Vận chuyển / logistics
        SERVICE,     // Dịch vụ khác
        OTHER        // Khác
    }

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
