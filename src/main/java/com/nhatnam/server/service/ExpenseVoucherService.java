package com.nhatnam.server.service;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.expense.CreateExpenseVoucherRequest;
import com.nhatnam.server.dto.expense.ExpenseVoucherDto;
import org.springframework.data.domain.Pageable;

public interface ExpenseVoucherService {
    ExpenseVoucherDto create(Long createdByUserId, CreateExpenseVoucherRequest req);
    ExpenseVoucherDto getById(Long id);
    PageResponse<ExpenseVoucherDto> listForCreator(Long userId, Pageable pageable);
    PageResponse<ExpenseVoucherDto> listAll(Pageable pageable);
    PageResponse<ExpenseVoucherDto> listByDateRange(Long from, Long to, Pageable pageable);
    /** q + tuỳ chọn from/to. Nếu from/to null → search toàn bộ không lọc ngày */
    PageResponse<ExpenseVoucherDto> search(String q, Long from, Long to, Pageable pageable);
    ExpenseVoucherDto approve(Long id, Long approverUserId, String note);
    ExpenseVoucherDto reject(Long id, Long approverUserId, String reason);
}