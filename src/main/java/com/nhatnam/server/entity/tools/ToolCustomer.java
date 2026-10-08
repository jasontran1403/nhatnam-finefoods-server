package com.nhatnam.server.entity.tools;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "tool_customer")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolCustomer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 100) private String maKhachHang;
    @Column(length = 300) private String tenKhachHang;
    @Column(length = 500) private String diaChi;
    @Column(length = 100) private String nhomKhNcc;
    @Column(length = 50) private String maSoThue;
    @Column(length = 50) private String dienThoai;
    @Column(length = 10) private String ngungTheoDoi;
}
