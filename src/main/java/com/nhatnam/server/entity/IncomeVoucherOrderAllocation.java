package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * SỐ TIỀN MỘT PHIẾU THU ĐÃ GHI CHO MỘT ĐƠN.
 *
 * <h3>Vì sao cần bảng này</h3>
 * Trước đây phiếu thu chỉ lưu DANH SÁCH MÃ ĐƠN + tổng tiền, không lưu "phiếu này
 * trả bao nhiêu cho đơn nào". Thiếu thông tin đó sinh ra hai lỗ khi SỬA phiếu:
 * <ul>
 *   <li>Một đơn được NHIỀU phiếu cùng trả → lúc sửa không biết phiếu đang sửa đã
 *       góp bao nhiêu, phải đoán ⇒ số "còn lại" gợi ý trên form sai.</li>
 *   <li>Muốn hoàn tác chính xác tác động của phiếu (khi sửa) cũng không có căn
 *       cứ — chỉ gỡ áng chừng theo paidAmount.</li>
 * </ul>
 *
 * <p>Lưu con số này rồi thì sửa phiếu = đọc đúng phần mình đã ghi, cộng ngược lại
 * để hiện "còn lại", và hoàn tác đúng bằng số đó — không đoán nữa.
 *
 * <p>Chỉ ghi cho đơn phiếu THỰC SỰ có phân bổ tiền (> 0). Đơn nằm trong danh sách
 * nhưng hết tiền không tới lượt thì không tạo dòng ở đây.
 */
@Entity
@Table(
        name = "income_voucher_order_alloc",
        indexes = {
                @Index(name = "idx_ivoa_voucher", columnList = "voucher_id"),
                @Index(name = "idx_ivoa_order_code", columnList = "order_code")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeVoucherOrderAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    private IncomeVoucher voucher;

    /** Mã đơn — khớp với cách phiếu vốn tham chiếu đơn (theo mã, không theo id). */
    @Column(name = "order_code", nullable = false, length = 100)
    private String orderCode;

    /** Số tiền phiếu này đã ghi cho đơn này. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;
}
