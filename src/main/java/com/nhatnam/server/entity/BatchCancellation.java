package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "batch_cancellation")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"batch", "cancelledBy"})
@EqualsAndHashCode(of = "id")
public class BatchCancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false, unique = true)
    private ProductionBatch batch;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "attachments", columnDefinition = "TEXT")
    @Builder.Default
    private String attachments = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Resolution resolution;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id", nullable = false)
    private User cancelledBy;

    @Column(name = "cancelled_by_name", length = 200)
    private String cancelledByName;

    @Column(name = "cancelled_at", nullable = false)
    private Long cancelledAt;

    public enum Resolution {
        REDO,
        REPLACE,
        ABORT
    }
}