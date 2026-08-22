package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * File Excel gốc mà OWNER đã tải lên cho một khoản thưởng/phụ cấp.
 * Lưu để MODAL PREVIEW hiển thị đúng "file người dùng đã import" thay vì bảng
 * data đã xử lý (nhân viên cần xem lại file gốc trông thế nào, có dòng nào lỡ
 * thiếu tên/không match không, v.v.).
 *
 * <p>Khoá logic: (month, year, type, coalesce(label,''), coalesce(department,'')).
 * Với BONUS mỗi nhãn/bộ phận có 1 record; với ALLOWANCE mỗi bộ phận có 1 record
 * (một file phụ cấp gồm tất cả khoản). Trước khi lưu file mới, xoá record cũ
 * cùng khoá để không tồn tại 2 bản.
 */
@Entity
@Table(name = "adjustment_import_file")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AdjustmentImportFile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;

    /** BONUS | ALLOWANCE — khớp với MonthlyAdjustment.Type. */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** Chỉ BONUS mới có nhãn (Chuyên cần, Tháng 13…); ALLOWANCE để null. */
    @Column(name = "label", length = 120)
    private String label;

    /** FACTORY | DRIVER | SALES | WAREHOUSE | ACCOUNTING (null = dữ liệu cũ). */
    @Column(name = "department", length = 30)
    private String department;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    /** Bytes của file Excel gốc. */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] content;

    @Column(name = "uploaded_at")
    private Long uploadedAt;

    @PrePersist
    void prePersist() {
        if (uploadedAt == null) uploadedAt = System.currentTimeMillis();
    }
}
