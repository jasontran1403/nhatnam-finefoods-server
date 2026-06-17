package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "driver")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Driver {

    public enum VehicleType { TRUCK, MOTORBIKE, BOTH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type")
    @Builder.Default
    private VehicleType vehicleType = VehicleType.BOTH;

    @Column(name = "created_at")
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
        if (vehicleType == null) vehicleType = VehicleType.BOTH;
    }
}
