package com.nhatnam.server.entity.tools;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "tool_sales_record")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ToolSalesRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 30) private String ngayHachToan;
    @Column(length = 30) private String ngayChungTu;
    @Column(length = 30) private String soChungTu;
    @Column(length = 30) private String soHoaDon;
    @Column(length = 300) private String khachHang;
    @Column(length = 500) private String dienGiai;
    @Column(length = 30) private String tongTienHang;
    @Column(length = 30) private String tienChietKhau;
    @Column(length = 30) private String tienThueGtgt;
    @Column(length = 30) private String tongTienThanhToan;
    @Column(length = 20) private String daLapHoaDon;
    @Column(length = 20) private String daXuatHang;
    @Column(length = 200) private String loaiChungTu;
}
