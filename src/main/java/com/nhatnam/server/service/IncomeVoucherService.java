package com.nhatnam.server.service;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.income.CreateIncomeVoucherRequest;
import com.nhatnam.server.dto.income.IncomeVoucherDto;
import com.nhatnam.server.enumtype.Role;
import org.springframework.data.domain.Pageable;

public interface IncomeVoucherService {
    IncomeVoucherDto create(Long createdByUserId, Role creatorRole, CreateIncomeVoucherRequest req);

    /**
     * SỬA phiếu thu — đổi số tiền và/hoặc danh sách đơn cần thu.
     *
     * <p><b>Quyền:</b> chỉ NGƯỜI TẠO phiếu, hoặc SUPER_ACCOUNTANT (sửa mọi phiếu).
     * ADMIN/OWNER cũng được, vì họ đứng trên kế toán trưởng. Kế toán viên khác
     * KHÔNG sửa được phiếu không phải của mình.
     *
     * <p>Cách làm: GỠ toàn bộ tác động của phiếu cũ lên các đơn liên kết (trả
     * chúng về "chờ thanh toán"), rồi áp lại từ đầu theo dữ liệu mới — hệt như
     * tạo mới. Đảo-rồi-áp-lại đơn giản và ít sai hơn tính delta từng đơn, nhất là
     * khi phiếu cũ không lưu số tiền đã ghi cho riêng từng đơn.
     *
     * <p>Bỏ một đơn khỏi phiếu ⇒ đơn đó về UNPAID / chờ thanh toán, paidAmount = 0.
     * Giảm tổng tiền ⇒ đơn cuối trong danh sách thành PARTIAL.
     */
    IncomeVoucherDto update(Long voucherId, Long editorUserId, Role editorRole,
                            CreateIncomeVoucherRequest req);

    PageResponse<IncomeVoucherDto> listForCreator(Long userId, Pageable pageable);
    PageResponse<IncomeVoucherDto> listAll(Pageable pageable);
    PageResponse<IncomeVoucherDto> listByDateRange(Long from, Long to, Pageable pageable);
    /** q + tuỳ chọn from/to. Nếu from/to null → search toàn bộ không lọc ngày */
    PageResponse<IncomeVoucherDto> search(String q, Long from, Long to, Pageable pageable);

    /**
     * TỔNG HỢP theo đúng bộ lọc đang áp dụng (không phụ thuộc phân trang).
     *
     * @param q    từ khoá tìm kiếm (null/rỗng = không lọc)
     * @param from mốc đầu (epoch millis, null = không lọc ngày)
     * @param to   mốc cuối (epoch millis, null = không lọc ngày)
     */
    com.nhatnam.server.dto.income.IncomeVoucherSummaryDto summary(String q, Long from, Long to);
    IncomeVoucherDto getById(Long id);

    /** Nhật ký tạo/sửa của một phiếu thu, mới nhất trước. */
    java.util.List<com.nhatnam.server.dto.income.IncomeVoucherLogDto> getLogs(Long voucherId);
    byte[] exportReport(Long from, Long to, String exportedBy, String paymentType) throws Exception;
    /** Gợi ý số phiếu thu kế tiếp = số lớn nhất hiện có (phần số, bỏ ký tự chữ) + 1 */
    String suggestNextReceiptNumber();
}