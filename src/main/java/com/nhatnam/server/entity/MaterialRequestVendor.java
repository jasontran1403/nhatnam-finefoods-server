package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Nhà cung cấp được gán cho một phiếu đặt hàng nguyên liệu.
 * Kế toán trưởng thêm khi xử lý phiếu.
 * Một phiếu có thể có nhiều NCC (đặt từ nhiều nơi).
 */
@Entity
@Table(name = "material_request_vendor")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequestVendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id", nullable = false)
    private MaterialRequest materialRequest;

    /**
     * FK tới MaterialVendor (entity đã có sẵn).
     * Nullable vì kế toán có thể chọn NCC từ danh sách hoặc nhập mới.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private MaterialVendor vendor;

    /** Tên NCC (snapshot tại thời điểm thêm, để không phụ thuộc vendor bị xoá) */
    @Column(name = "vendor_name", nullable = false, length = 200)
    private String vendorName;

    @Column(name = "contact_person", length = 200)
    private String contactPerson;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;
}
