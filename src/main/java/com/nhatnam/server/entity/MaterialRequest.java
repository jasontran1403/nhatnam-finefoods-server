package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu đặt hàng nguyên liệu — Factory Worker tạo, Super Accountant xử lý.
 *
 * Flow: NEW → ORDERED → RECEIVED → COMPLETED
 */
@Entity
@Table(name = "material_request")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MaterialRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã phiếu: MR-YYYYMMDD-XXXX */
    @Column(name = "request_code", nullable = false, unique = true, length = 50)
    private String requestCode;

    /** Người tạo phiếu (Factory Worker) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", nullable = false, length = 200)
    private String createdByName;

    /**
     * Thời gian cần xử lý (deadline) — nullable.
     * Kế toán trưởng cần đặt hàng trước thời điểm này.
     */
    @Column(name = "required_by")
    private Long requiredBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RequestStatus status = RequestStatus.NEW;

    /**
     * Loại phiếu: FACTORY (nguyên vật liệu xưởng, luồng gốc) hoặc SELLER
     * (nguyên liệu Ingredient do SUPER_SELLER đặt — nhập kho khi hoàn thành).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RequestType type = RequestType.FACTORY;

    /**
     * PHÂN LOẠI PHIẾU — tách hoàn toàn 2 luồng dùng chung entity này:
     * <ul>
     *   <li>{@code MATERIAL} — phiếu đặt hàng nguyên liệu sản xuất (luồng gốc).</li>
     *   <li>{@code SUPPLY}   — phiếu đặt Văn phòng phẩm / Đồ dùng (luồng mới).</li>
     * </ul>
     * <p><b>CỐ Ý ĐỂ NULLABLE.</b> Với {@code ddl-auto: update}, Hibernate thêm cột
     * mới vào bảng đang có dữ liệu. Nếu khai báo {@code nullable = false}, MySQL sẽ
     * điền chuỗi RỖNG cho mọi dòng cũ — và {@code @Enumerated(STRING)} đọc phải
     * chuỗi rỗng sẽ ném {@code IllegalArgumentException}, làm hỏng toàn bộ tính năng
     * phiếu đặt hàng nguyên liệu. Để nullable thì dòng cũ nhận NULL, đọc ra là null,
     * và mọi truy vấn/nhánh code đều coi NULL ⇒ MATERIAL (xem
     * {@code MaterialRequestRepository} và {@link #isSupplyOrder()}).
     *
     * <p>{@code SupplyModuleInitializer} vẫn backfill về {@code 'MATERIAL'} cho sạch
     * dữ liệu, nhưng hệ thống KHÔNG phụ thuộc vào việc backfill có chạy hay không.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20)
    @Builder.Default
    private OrderType orderType = OrderType.MATERIAL;

    /** Dữ liệu cũ (orderType = null) luôn được hiểu là phiếu nguyên liệu. */
    public boolean isSupplyOrder() {
        return orderType == OrderType.SUPPLY;
    }

    /**
     * Kho VPP nhận hàng — người tạo chọn NGAY KHI TẠO PHIẾU, chỉ áp dụng cho
     * {@code orderType = SUPPLY}. Chỉ được chọn trong các kho đã gán cho user
     * (bảng {@code user_supply_warehouse}).
     */
    @Column(name = "supply_warehouse_id")
    private Long supplyWarehouseId;

    /**
     * Lý do TỪ CHỐI phiếu (chỉ luồng SUPPLY). {@code REJECTED} là trạng thái
     * TERMINAL — không thể duyệt lại.
     */
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /** Thời gian kế toán xác nhận đặt hàng */
    @Column(name = "ordered_at")
    private Long orderedAt;

    /** Thời gian giao hàng dự kiến (do kế toán nhập khi xác nhận) */
    @Column(name = "estimated_delivery")
    private Long estimatedDelivery;

    /**
     * Xưởng của phiếu — SUPER_FACTORY_WORKER chọn khi tạo phiếu.
     * Dùng để: gán kho xưởng khi nhận hàng, và WS noti đúng nhân viên xưởng đó.
     * Nullable để tương thích dữ liệu cũ (backfill về Xưởng Quận 9).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_factory_id")
    private ProductionFactory productionFactory;

    /** Lần gia hạn giao hàng gần nhất (SUPER_ACCOUNTANT) — ngày giao mới. Nullable. */
    @Column(name = "delivery_extended_to")
    private Long deliveryExtendedTo;

    /** Lý do gia hạn giao hàng (nullable). */
    @Column(name = "delivery_extend_reason", columnDefinition = "TEXT")
    private String deliveryExtendReason;

    /** Người xử lý phiếu (Super Accountant) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by_id")
    private User handledBy;

    @Column(name = "handled_by_name", length = 200)
    private String handledByName;

    /** Thời gian nhân viên xưởng xác nhận đã nhận hàng */
    @Column(name = "received_at")
    private Long receivedAt;

    /** Ghi chú khi nhận hàng */
    @Column(name = "receive_notes", columnDefinition = "TEXT")
    private String receiveNotes;

    /**
     * Lý do nhận THIẾU — bắt buộc khi bấm "Xác nhận đã giao xong" mà tổng thực nhận
     * của ít nhất 1 dòng vẫn nhỏ hơn số đặt (NCC không giao bù nữa).
     */
    @Column(name = "shortage_reason", columnDefinition = "TEXT")
    private String shortageReason;

    /** Thời gian kế toán bấm Hoàn thành */
    @Column(name = "completed_at")
    private Long completedAt;

    /**
     * Các ĐỢT nhận hàng của phiếu (NCC giao lẻ / giao bù).
     * Immutable — chỉ thêm đợt mới, không sửa/xoá đợt đã lưu.
     */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNo ASC")
    private List<MaterialRequestReceipt> receipts = new ArrayList<>();

    /** Danh sách nhà cung cấp cho phiếu này (kế toán thêm) */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestVendor> vendors = new ArrayList<>();

    /** Danh sách nguyên liệu trong phiếu */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestItem> items = new ArrayList<>();

    /**
     * Breakdown giá/phí nhập lúc Hoàn thành phiếu — lưu lại để hiển thị/kiểm tra
     * sau này (xem {@link MaterialRequestCostEntry}). Có thể rỗng nếu phiếu được
     * hoàn thành theo cách nhập đơn giá trực tiếp (luồng cũ).
     */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialRequestCostEntry> costEntries = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /**
     * Optimistic lock — 2 nhân viên xưởng cùng lưu đợt nhận trên 1 phiếu sẽ khiến
     * người lưu sau nhận OptimisticLockException thay vì ghi đè âm thầm.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    /**
     * Các nhóm theo NCC — CHỈ dùng cho {@code orderType = SUPPLY}. Sinh ra ở bước
     * SUPER_ACCOUNTANT xác nhận đặt hàng. Rỗng với phiếu nguyên liệu.
     */
    @Builder.Default
    @OneToMany(mappedBy = "materialRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplyOrderGroup> supplyGroups = new ArrayList<>();

    /**
     * Vòng đời:
     * <pre>
     *   NEW → ORDERED → PARTIALLY_RECEIVED → RECEIVED → COMPLETED
     *    └──────→ REJECTED (terminal — không thể duyệt lại)
     * </pre>
     * ({@code PARTIALLY_RECEIVED} hiển thị là "Đang nhận hàng" / RECEIVING ở FE.)
     */
    public enum RequestStatus {
        NEW,                 // Mới tạo
        ORDERED,             // Đã đặt hàng (kế toán xác nhận)
        PARTIALLY_RECEIVED,  // Đang nhận hàng — đã lưu >=1 đợt, chưa bấm "đã giao xong"
        RECEIVED,            // Đã nhận hàng xong (nv xưởng chốt) — khoá, không nhận thêm
        COMPLETED,           // Hoàn thành (kế toán đóng phiếu)
        REJECTED             // Kế toán từ chối — TERMINAL, không thể duyệt lại
    }

    /** Xem {@link #orderType}. */
    public enum OrderType {
        MATERIAL,  // Nguyên liệu sản xuất (luồng gốc)
        SUPPLY     // Văn phòng phẩm / Đồ dùng
    }

    public enum RequestType {
        FACTORY,   // Nguyên vật liệu xưởng (luồng gốc, tạo tồn ở bước nhận)
        SELLER     // Nguyên liệu Ingredient (SUPER_SELLER; nhập kho ở bước hoàn thành)
    }
}