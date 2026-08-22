package com.nhatnam.server.dto.expense;

import lombok.Builder;
import lombok.Data;

/** Một dòng nhật ký của phiếu chi (xem {@code entity.ExpenseVoucherLog}). */
@Data
@Builder
public class ExpenseVoucherLogDto {
    private Long id;
    /** APPROVED | REJECTED | REOPENED | ITEMS_UPDATED | REASON_UPDATED */
    private String action;
    private String actorName;
    /** Vai trò đang active lúc thao tác — OWNER / ADMIN / SUPER_ACCOUNTANT… */
    private String actorRole;
    private String fromStatus;
    private String toStatus;
    private String note;
    private Long createdAt;
}