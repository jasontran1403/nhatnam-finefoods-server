package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * QUỸ DƯ KPI KHAI BÁO THỦ CÔNG ("quỹ dư đầu kỳ").
 *
 * <h3>Giải quyết vấn đề gì</h3>
 * Quỹ thưởng KPI chia không hết thì phần lẻ được giữ lại cho tháng sau
 * ({@code FactoryKpiBonus.carryOverDetail}). Cơ chế đó chỉ chạy khi CẢ HAI tháng
 * đều được tính trong app.
 *
 * <p>Thực tế lúc mới đưa app vào dùng: tháng trước phòng sản xuất đã chia tay và
 * còn dư, nhưng tháng đó chưa hề có trong hệ thống ⇒ không có bản ghi
 * {@code FactoryKpiBonus} nào để lấy sổ dư ⇒ tiền dư bị mất trắng khi tính tháng
 * đầu tiên trên app. Bảng này là chỗ khai báo tay khoản dư đó.
 *
 * <h3>Vì sao là bảng riêng chứ không sửa thẳng FactoryKpiBonus của tháng trước</h3>
 * {@code recompute()} XOÁ rồi tạo lại bản ghi {@code FactoryKpiBonus} của tháng
 * (mỗi lần import bảng chấm công đều gọi tự động). Nếu nhét số liệu khai báo tay
 * vào đó thì chỉ cần tải lại bảng chấm công một lần là bay mất. Tách bảng riêng
 * thì số khai báo tay tồn tại độc lập với mọi lần tính lại.
 *
 * <h3>Ngữ nghĩa</h3>
 * Một dòng = "cộng thêm {@code amount} đồng vào quỹ chia của tháng
 * {@code applyMonth}/{@code applyYear}, khoản này phát sinh từ tháng
 * {@code sourceMonth}/{@code sourceYear}".
 *
 * <p>{@code sourceMonth/Year} chỉ để hiển thị nguồn gốc ("400.000đ của T7/2026")
 * và để xếp thứ tự tiêu FIFO — tiêu khoản cũ nhất trước, tiền không treo mãi.
 *
 * <p><b>Chỉ cần khai báo cho ĐÚNG MỘT tháng.</b> Sau khi tháng đó được tính, phần
 * chưa tiêu hết đã nằm trong sổ dư đầu ra của chính nó, các tháng sau tự kế thừa
 * theo cơ chế bình thường. Khai báo lặp lại cho nhiều tháng sẽ cộng trùng tiền.
 */
@Entity
@Table(
        name = "factory_kpi_carry_over_seed",
        indexes = @Index(name = "idx_kpi_seed_apply", columnList = "apply_month,apply_year")
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactoryKpiCarryOverSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** THÁNG ĐƯỢC CỘNG THÊM khoản dư này (tháng đầu tiên tính KPI trên app). */
    @Column(name = "apply_month", nullable = false)
    private Integer applyMonth;

    @Column(name = "apply_year", nullable = false)
    private Integer applyYear;

    /** Tháng PHÁT SINH khoản dư — dùng để hiển thị nguồn gốc và xếp FIFO. */
    @Column(name = "source_month", nullable = false)
    private Integer sourceMonth;

    @Column(name = "source_year", nullable = false)
    private Integer sourceYear;

    /** Số tiền dư (VNĐ). Luôn ≥ 0. */
    @Column(name = "amount", nullable = false)
    @Builder.Default
    private Long amount = 0L;

    /** Ghi chú của người nhập — VD "chốt tay theo phiếu thưởng T7/2026". */
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at")
    private Long createdAt;

    /** Tên người khai báo (snapshot) — để truy vết ai đã thêm tiền vào quỹ. */
    @Column(name = "created_by", length = 150)
    private String createdBy;
}
