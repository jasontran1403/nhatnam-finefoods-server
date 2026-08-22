package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Phiếu chuyển kho: Kho bán thành phẩm (kg, chưa đóng gói) → Kho thành phẩm
 * (đơn vị đóng gói: túi/hộp). Bước 2 trong quy trình 4 bước.
 *
 * - Do Trưởng xưởng/Nhân viên xưởng (FACTORY_WORKER/SUPER_FACTORY_WORKER) lập.
 * - 1 phiếu có thể gồm nhiều dòng (mỗi dòng 1 sản phẩm), mỗi dòng trừ FIFO từ
 *   1 hoặc nhiều batch nguồn ở Kho bán thành phẩm (xem SemiFinishedTransferSourceBatch).
 * - Kế toán kho xưởng (FACTORY_ACCOUNTANT) xác nhận NHẬN 1 lần duy nhất cho cả
 *   phiếu (RECEIVED) → ghi vào FinishedGoodsStock theo số lượng đóng gói thực tế
 *   + tổng trọng lượng thực cân của TỪNG DÒNG, từ đó tự tính hao hụt và lập biên
 *   bản hao hụt đóng gói (PackagingLossReport) cho phiếu này nếu có chênh lệch.
 */
@Entity
@Table(name = "semi_finished_transfer_note")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SemiFinishedTransferNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_code", nullable = false, unique = true, length = 100)
    private String noteCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── Xác nhận nhận (Bước 3) ───────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by_id")
    private User receivedBy;

    @Column(name = "received_by_name", length = 200)
    private String receivedByName;

    @Column(name = "received_at")
    private Long receivedAt;

    @Column(name = "receive_notes", columnDefinition = "TEXT")
    private String receiveNotes;

    @Builder.Default
    @OneToMany(mappedBy = "transferNote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SemiFinishedTransferNoteLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = System.currentTimeMillis(); }
    @PreUpdate  void onUpdate() { updatedAt = System.currentTimeMillis(); }

    public enum Status {
        /** Đã lập phiếu, đã trừ kho bán thành phẩm, chờ kế toán kho xác nhận nhận */
        PENDING,
        /** Kế toán kho đã xác nhận nhận — đã ghi vào kho thành phẩm + (nếu có) lập biên bản hao hụt */
        RECEIVED
    }
}
