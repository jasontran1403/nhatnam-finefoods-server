package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Một ĐỢT NHẬN HÀNG của phiếu đặt hàng nguyên liệu.
 *
 * <p>NCC có thể giao lẻ (mỗi NCC giao một lúc khác nhau) và giao bù (đợt trước giao
 * thiếu, đợt sau giao thêm). Mỗi lần nhân viên xưởng bấm <b>Lưu đợt nhận</b> sinh ra
 * đúng 1 bản ghi này.
 *
 * <p><b>BẤT BIẾN (immutable)</b> — đợt đã lưu KHÔNG được sửa/xoá. Nhập sai thì chỉ có
 * cách thêm đợt bù. Nhờ vậy không cần rollback lô tồn kho đã tạo (lô có thể đã bị
 * lệnh sản xuất trừ FIFO mất rồi).
 *
 * <p>Đợt KHÔNG gắn với nhà cung cấp: NCC được xác định ở cấp từng nguyên liệu
 * ({@link MaterialRequestItem#getSuppliedByVendor()}), nên 1 đợt có thể chứa các dòng
 * của nhiều NCC khác nhau hoặc chỉ của 1 NCC — tuỳ thực tế giao hàng.
 */
@Entity
@Table(
        name = "material_request_receipt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mr_receipt_seq",
                columnNames = {"material_request_id", "sequence_no"})
)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequestReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_request_id", nullable = false)
    private MaterialRequest materialRequest;

    /** Số thứ tự đợt trong phiếu: 1, 2, 3… (hiển thị "Đợt 1", "Đợt 2"). */
    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    /** Thời điểm lưu đợt nhận này. */
    @Column(name = "received_at", nullable = false)
    private Long receivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by_id")
    private User receivedBy;

    @Column(name = "received_by_name", length = 200)
    private String receivedByName;

    /** Ghi chú riêng của đợt này (VD: "NCC Minh Phát giao trước phần ba rọi"). */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Các dòng nguyên liệu thực nhận trong đợt này. */
    @Builder.Default
    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestReceiptItem> items = new ArrayList<>();

    /**
     * ĐỢT NHÁP — người tạo bấm "Lưu" thay vì "Xác nhận nhận hàng".
     *
     * <p>Đợt nháp KHÔNG cộng tồn kho và KHÔNG tính vào {@code qtyReceived};
     * mỗi phiếu có tối đa 1 đợt nháp (lần lưu sau ghi đè lần trước). Khi bấm
     * "Xác nhận nhận hàng", đợt nháp được chuyển thành đợt thật ({@code draft = false})
     * và lúc đó mới sinh transaction nhập kho.
     *
     * <p>Chỉ dùng cho phiếu {@code orderType = SUPPLY}; phiếu nguyên liệu luôn false
     * nên tính bất biến của luồng gốc được giữ nguyên.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean draft = false;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
