package com.nhatnam.server.dto.income;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Một dòng nhật ký phiếu thu cho FE. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeVoucherLogDto {
    private Long id;
    /** CREATE | UPDATE. */
    private String action;
    /** Nhãn tiếng Việt: "Tạo mới" | "Chỉnh sửa". */
    private String actionLabel;
    private String actorName;
    private String actorRole;
    private String note;
    private Long createdAt;
}
