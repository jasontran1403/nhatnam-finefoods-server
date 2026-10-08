package com.nhatnam.server.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_code_prefix")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderCodePrefix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2, unique = true)
    private String prefix;        // "AB", "XK", ...

    @Column(nullable = false)
    private Integer year;         // năm đang dùng prefix này

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;   // prefix đang dùng cho năm hiện tại

    /**
     * BUG FIX 1.1 (race order_code): Counter atomic thay cho count(*) query.
     * Repository dùng UPDATE ... SET counter = counter + 1 để đảm bảo mỗi
     * request lấy được 1 số duy nhất, không race lost update.
     * Mặc định 0 cho row cũ, migration SQL sẽ set giá trị đúng cho dữ liệu cũ.
     */
    @Column(name = "counter", nullable = false)
    @Builder.Default
    private Long counter = 0L;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}