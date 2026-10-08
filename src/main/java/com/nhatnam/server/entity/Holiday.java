package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * NGÀY NGHỈ LỄ CÔNG TY.
 *
 * <p>Phase 1 (10/2026): bỏ hardcode ngày lễ, cho phép OWNER/HR upload từ file
 * Excel. Mỗi ngày lễ là 1 bản ghi; đặt khoá UNIQUE theo {@link #date} để tránh
 * import trùng. Nếu bạn muốn ghi chú ("Quốc khánh", "Tết Dương lịch") thì điền
 * ở {@link #name} — mục đích chỉ để hiển thị, không ảnh hưởng tính lương.
 *
 * <h3>Ý nghĩa trong tính lương (Phase 1)</h3>
 * <ul>
 *   <li>Vẫn tính là NGÀY CÔNG CHUẨN của tháng (bạn bè đi làm ngày đó được nghỉ
 *       có lương) — xem {@code PayrollCalculationService.standardWorkdaysInMonth}.</li>
 *   <li>Nhân viên vào làm SAU ngày lễ (hợp đồng bắt đầu sau ngày lễ) thì KHÔNG
 *       được tính công ngày lễ đó — xem
 *       {@code PayrollCalculationService.standardWorkdaysForEmployee}.</li>
 *   <li>KHÔNG được phụ cấp cơm trong ngày lễ.</li>
 *   <li>Nếu nhân viên vẫn đi làm trong ngày lễ → thời gian làm × 3 (OT lễ).</li>
 * </ul>
 */
@Entity
@Table(
        name = "holiday",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_holiday_date",
                columnNames = {"holiday_date"}
        ),
        indexes = {
                @Index(name = "ix_holiday_year", columnList = "year")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ngày nghỉ (yyyy-MM-dd). Khoá unique — không được trùng. */
    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    /** Năm — redundant của {@link #date} nhưng giữ để index/truy vấn theo năm nhanh. */
    @Column(name = "year", nullable = false)
    private Integer year;

    /** Nhãn hiển thị, VD "Quốc khánh 2/9". Có thể null. */
    @Column(name = "name", length = 200)
    private String name;

    /** Metadata — ai import, lúc nào. */
    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;
}
