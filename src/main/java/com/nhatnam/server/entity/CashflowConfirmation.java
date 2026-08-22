package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Log xác nhận (chốt) dòng tiền tại một thời điểm.
 * <ul>
 *   <li>{@code matched = true}  — số kiểm đếm KHỚP với hệ thống: chỉ lưu log, KHÔNG
 *       đổi baseline.</li>
 *   <li>{@code matched = false} — có SAI LỆCH: bắt buộc nhập {@code reason}, và số
 *       kiểm đếm ({@code cashCounted} + {@code bankBalancesJson}) trở thành ĐẦU KỲ
 *       MỚI (baseline) tính từ {@code confirmedAt} trở đi.</li>
 * </ul>
 */
@Entity
@Table(name = "cashflow_confirmation")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CashflowConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "confirmed_at", nullable = false)
    private Long confirmedAt;

    @Column(name = "confirmed_by_name", length = 200)
    private String confirmedByName;

    @Column(name = "confirmed_by_role", length = 40)
    private String confirmedByRole;

    /** true = khớp (chỉ log); false = lệch (đặt baseline mới). */
    @Column(nullable = false)
    private Boolean matched;

    /** Lý do lệch — bắt buộc khi matched = false. */
    @Column(columnDefinition = "TEXT")
    private String reason;

    // ── Số KIỂM ĐẾM (người xác nhận nhập) ────────────────────────────────────
    @Column(name = "cash_counted", precision = 15, scale = 0)
    private BigDecimal cashCounted;

    /**
     * Chi tiết kiểm đếm tiền mặt theo MỆNH GIÁ — JSON map {"500000":3,"200000":1,"1000":5}
     * (key = mệnh giá, value = số tờ/số lượng). Nullable với dữ liệu cũ (nhập tổng trực tiếp).
     *
     * <p>{@link #cashCounted} luôn = Σ(mệnh giá × số lượng) khi map này khác null —
     * server tự tính lại, KHÔNG tin số tổng do client gửi.
     */
    @Column(name = "cash_denominations_json", columnDefinition = "TEXT")
    private String cashDenominationsJson;

    /** JSON map {"Vietcombank":1000000,"VietinBank":500000} — số dư kiểm đếm mỗi TK. */
    @Column(name = "bank_balances_json", columnDefinition = "TEXT")
    private String bankBalancesJson;

    // ── Số HỆ THỐNG tính được tại thời điểm xác nhận (để đối chiếu/hiển thị) ──
    @Column(name = "expected_cash", precision = 15, scale = 0)
    private BigDecimal expectedCash;

    @Column(name = "expected_bank_json", columnDefinition = "TEXT")
    private String expectedBankJson;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = System.currentTimeMillis(); }
}
