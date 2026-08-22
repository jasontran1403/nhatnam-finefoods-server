package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * DANH MỤC KHOẢN CHI — <b>POOL DÙNG CHUNG</b> cho TẤT CẢ nhà cung cấp.
 *
 * <h3>Thay đổi so với trước</h3>
 * Trước đây mỗi {@link MaterialVendor} có một danh sách nhãn RIÊNG (cột {@code vendor_id}).
 * Cách đó buộc phải tạo lại cùng một nhãn cho từng NCC: 10 nhãn × 200 NCC = 2.000 thao tác,
 * và cùng một khoản chi (VD "Tiền điện") lại nằm ở 200 bản ghi khác nhau nên rất khó
 * tổng hợp chi phí theo mục.
 *
 * <p>Giờ danh mục là một POOL CHUNG: Owner tạo nhãn <b>một lần</b>, mọi NCC đều chọn được.
 * Cột {@code vendor_id} đã bị loại bỏ; tên nhãn là DUY NHẤT trên toàn hệ thống.
 *
 * <p>Khi kế toán lập phiếu chi, mỗi khoản chi ({@link ExpenseItem}) vẫn phải CHỌN một nhãn
 * từ danh mục (không gõ tự do) để tránh sai lệch do typo và để tổng hợp chi phí chính xác.
 * {@code ExpenseItem} vẫn snapshot lại {@code itemName} nên đổi tên nhãn về sau
 * không làm sai lệch các phiếu chi cũ.
 *
 * <p>Tên class/bảng giữ nguyên ({@code vendor_expense_category}) để không phải đổi
 * hàng loạt tham chiếu; ý nghĩa "vendor" trong tên chỉ còn mang tính lịch sử.
 */
@Entity
@Table(
    name = "vendor_expense_category",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_expense_category_name",
        columnNames = {"name"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class VendorExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nhãn khoản chi (VD "Tiền điện", "Sửa máy trộn") — DUY NHẤT toàn hệ thống */
    @Column(nullable = false, length = 300)
    private String name;

    /** Ghi chú/mô tả tuỳ chọn cho nhãn */
    @Column(length = 500)
    private String description;

    /**
     * false = ẩn khỏi form lập phiếu chi (không xoá cứng để giữ toàn vẹn phiếu chi cũ
     * đang tham chiếu tới nhãn này).
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // ══════════════════════════════════════════════════════════════════════════
    //  PHÂN LOẠI DỊCH VỤ / ĐỒ DÙNG TIÊU HAO  (phiếu đặt Văn phòng phẩm)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * <ul>
     *   <li>{@code SERVICE}    — dịch vụ: {@code unit}/{@code specification} để trống được,
     *       {@code supplyItemId} phải null, <b>KHÔNG nhập kho</b>.</li>
     *   <li>{@code CONSUMABLE} — đồ dùng tiêu hao: BẮT BUỘC có {@code unit},
     *       {@code specification} và {@code supplyItemId}.</li>
     * </ul>
     * <p><b>CỐ Ý ĐỂ NULLABLE</b> — cùng lý do với {@code MaterialRequest.orderType}:
     * với {@code ddl-auto: update}, cột NOT NULL thêm vào bảng đang có dữ liệu sẽ
     * được MySQL điền chuỗi RỖNG, và enum đọc chuỗi rỗng thì ném exception, làm
     * hỏng cả luồng phiếu chi hiện có. Nullable ⇒ dòng cũ nhận NULL, và mọi nhánh
     * code đều coi NULL ⇒ {@code SERVICE} (giữ nguyên hành vi cũ: chỉ là nhãn
     * khoản chi, không nhập kho).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category_kind", length = 20)
    @Builder.Default
    private CategoryKind categoryKind = CategoryKind.SERVICE;

    /** Dữ liệu cũ (categoryKind = null) luôn được hiểu là dịch vụ — không nhập kho. */
    public boolean isConsumable() {
        return categoryKind == CategoryKind.CONSUMABLE;
    }

    /** Đơn vị tính — VD "Chai", "Hộp", "Ram". Nullable với SERVICE. */
    @Column(length = 50)
    private String unit;

    /** Quy cách — VD "4L/chai", "500 tờ/ram". Nullable với SERVICE. */
    @Column(length = 200)
    private String specification;

    /**
     * Link tới danh mục vật dụng tồn kho ({@link SupplyItem}). BE tự
     * {@code getOrCreate(name, spec, unit)} khi Owner lưu category CONSUMABLE —
     * nhờ vậy 10 NCC bán cùng một mặt hàng vẫn chỉ có 1 dòng tồn kho.
     */
    @Column(name = "supply_item_id")
    private Long supplyItemId;

    public enum CategoryKind { SERVICE, CONSUMABLE }

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    @PrePersist  void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate   void onUpdate() { updatedAt = System.currentTimeMillis(); }
}
