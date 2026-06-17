package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "overtime_request")
public class OvertimeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ngày OT (epoch ms) */
    private Long otDate;

    /** Giờ bắt đầu OT (VD: "18:00") */
    private String startTime;

    /** Giờ kết thúc OT (VD: "22:00") */
    private String endTime;

    /** Số giờ OT (tính tự động hoặc nhập tay) */
    private Double otHours;

    @Column(columnDefinition = "TEXT")
    private String reason;

    /** Các nhân viên trong đơn OT này */
    @OneToMany(mappedBy = "overtimeRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OvertimeEmployee> employees = new ArrayList<>();

    /** HR tạo đơn */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    private Long createdAt;
}
