package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "customer_receiver_info",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"customer_id", "receiver_phone"}),
                @UniqueConstraint(columnNames = {"customer_id", "receiver_address"})
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerReceiverInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "receiver_name", nullable = true, length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = true, length = 20)
    private String receiverPhone;

    @Column(name = "receiver_address", nullable = false, length = 300)
    private String receiverAddress;

    /** true = mặc định được chọn khi mở popup receiver */
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() { createdAt = System.currentTimeMillis(); }
}