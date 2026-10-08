package com.nhatnam.server.service;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.expense.*;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface ExpenseVoucherService {
    ExpenseVoucherDto updateVoucher(Long id, Long editorUserId, UpdateExpenseVoucherRequest req);

    ExpenseVoucherDto create(Long createdByUserId, CreateExpenseVoucherRequest req);

    /**
     * TẠO PHIẾU CHI HOÀN PHẦN DƯ cho một đơn khách trả dư.
     *
     * <p>Lập phiếu chi với lý do "Thanh toán phần dư của đơn hàng {mã} do khách
     * thanh toán dư", số tiền = {@code overpaidAmount} của đơn, người nhận = tên
     * khách. Phiếu KHÔNG gắn NCC nên không cần chọn nhãn khoản chi. Đi qua duyệt
     * bình thường (owner/admin tạo → tự duyệt; kế toán tạo → chờ owner duyệt).
     *
     * <p>Sau khi tạo, ghi mã phiếu chi vào đơn để chặn lập trùng.
     *
     * @throws com.nhatnam.server.common.BusinessException nếu đơn không có phần dư
     *         hoặc đã lập phiếu hoàn trước đó.
     */
    ExpenseVoucherDto createOverpayRefund(Long createdByUserId, String orderCode);

    /**
     * TẠO PHIẾU CHI HOÀN PHẦN DƯ — có chỉ định phương thức thanh toán.
     *
     * <p>Nếu paymentType = BANK_TRANSFER, bắt buộc nhập thông tin tài khoản
     * khách hàng (ngân hàng, số tài khoản, tên chủ tài khoản) để OWNER/ADMIN
     * biết chuyển tiền hoàn cho ai.
     */
    ExpenseVoucherDto createOverpayRefund(Long createdByUserId, String orderCode,
                                          String paymentType, String customerBankName,
                                          String customerBankAccount, String customerBankHolder);

    ExpenseVoucherDto getById(Long id);

    /** File Excel mẫu để nhập phiếu chi hàng loạt (kèm dropdown NCC + khoản chi từ DB). */
    byte[] buildImportTemplate();

    /**
     * Nhập phiếu chi hàng loạt từ file Excel. Các dòng cùng "Mã phiếu (nhóm)" gộp thành
     * MỘT phiếu; mỗi nhóm gọi lại {@link #create} nên quy tắc duyệt giữ nguyên.
     * Lỗi ở một nhóm không làm hỏng các nhóm khác.
     */
    ExpenseImportResultDto importFromExcel(Long createdByUserId, MultipartFile file);

    /**
     * Xuất báo cáo phiếu chi (Excel) theo khoảng thời gian [from, to].
     * @param paymentType "CASH" | "BANK_TRANSFER" | null/""/"ALL"/"BOTH" (cả 2, thêm cột PTTT)
     */
    byte[] exportReport(Long from, Long to, String exportedBy, String paymentType) throws Exception;

    PageResponse<ExpenseVoucherDto> listAll(String callerRole, Long callerUserId, Pageable pageable);

    PageResponse<ExpenseVoucherDto> listByExpenseDateRange(String callerRole, Long callerUserId,
                                                           Long from, Long to, Pageable pageable);

    PageResponse<ExpenseVoucherDto> searchByExpenseDateRange(String callerRole, Long callerUserId,
                                                             String q, Long from, Long to, Pageable pageable);

    ExpenseVoucherDto approve(Long id, Long approverUserId, String note);
    ExpenseVoucherDto reject(Long id, Long approverUserId, String reason);

    /**
     * Duyệt NHIỀU phiếu chi một lần. Mỗi phiếu xử lý độc lập (giao dịch riêng) nên
     * một phiếu lỗi không làm hỏng các phiếu còn lại.
     */
    com.nhatnam.server.dto.expense.BulkExpenseActionResultDto bulkApprove(
            Long approverUserId, com.nhatnam.server.dto.expense.BulkExpenseActionRequest req);

    /** Từ chối NHIỀU phiếu chi một lần (cùng một lý do từ chối). */
    com.nhatnam.server.dto.expense.BulkExpenseActionResultDto bulkReject(
            Long approverUserId, com.nhatnam.server.dto.expense.BulkExpenseActionRequest req);

    /**
     * One-time service: duyệt TẤT CẢ phiếu chi đang {@code PENDING} bằng Owner có
     * {@code user_id = 2}. {@code approvedAt} = {@link System#currentTimeMillis()}.
     * Trả về số phiếu đã duyệt.
     */
    int approveAllPendingByOwner2();

    /**
     * Chuyển phiếu ĐÃ DUYỆT hoặc ĐÃ TỪ CHỐI về lại CHỜ DUYỆT — chỉ OWNER/ADMIN.
     *
     * <p>Xoá dấu vết duyệt/từ chối cũ, tính lại cấp duyệt theo tổng tiền hiện tại,
     * và ghi một dòng nhật ký {@code REOPENED} kèm vai trò đang active trong JWT.
     */
    ExpenseVoucherDto reopen(Long id, Long editorUserId, String note);

    /** Nhật ký thao tác của một phiếu chi, mới nhất trước. */
    java.util.List<com.nhatnam.server.dto.expense.ExpenseVoucherLogDto> getLogs(Long voucherId);

    /** Sửa lý do phiếu chi — chỉ PENDING hoặc APPROVED (REJECTED không cho sửa). */
    ExpenseVoucherDto updateReason(Long id, Long editorUserId, String newReason);

    /**
     * Sửa DANH SÁCH khoản chi của một phiếu chi (thay thế toàn bộ: sửa / thêm / xoá).
     * <ul>
     *   <li>Phiếu CHỜ DUYỆT — ACCOUNTANT, SUPER_ACCOUNTANT, OWNER, ADMIN: sửa nhãn,
     *       số tiền, thêm và xoá khoản chi.</li>
     *   <li>Phiếu ĐÃ DUYỆT — chỉ OWNER/ADMIN. Kế toán chỉ được sửa lý do chi.</li>
     *   <li>Phiếu ĐÃ TỪ CHỐI — không sửa được.</li>
     * </ul>
     * Sau khi sửa, cấp duyệt ({@code approverScope}) được tính lại theo tổng tiền mới.
     */
    ExpenseVoucherDto updateItems(Long id, Long editorUserId,
                                  com.nhatnam.server.dto.expense.UpdateExpenseItemsRequest req);

    /** Gợi ý số phiếu chi kế tiếp = số phiếu gần nhất + 1 (quay vòng về 1 khi đạt 15000). */
    String suggestNextPaymentNumber();

    // ── Cấu hình duyệt (OWNER quản lý) ────────────────────────────────────────
    ExpenseApprovalConfigDto getApprovalConfig();
    ExpenseApprovalConfigDto updateApprovalConfig(Long editorUserId, ExpenseApprovalConfigDto dto);
}