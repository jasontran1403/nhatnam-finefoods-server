package com.nhatnam.server.service;

import com.nhatnam.server.dto.common.PageResponse;
import com.nhatnam.server.dto.income.CreateIncomeVoucherRequest;
import com.nhatnam.server.dto.income.IncomeVoucherDto;
import com.nhatnam.server.enumtype.Role;
import org.springframework.data.domain.Pageable;

public interface IncomeVoucherService {
    IncomeVoucherDto create(Long createdByUserId, Role creatorRole, CreateIncomeVoucherRequest req);
    PageResponse<IncomeVoucherDto> listForCreator(Long userId, Pageable pageable);
    PageResponse<IncomeVoucherDto> listAll(Pageable pageable);
    PageResponse<IncomeVoucherDto> listByDateRange(Long from, Long to, Pageable pageable);
    /** q + tuỳ chọn from/to. Nếu from/to null → search toàn bộ không lọc ngày */
    PageResponse<IncomeVoucherDto> search(String q, Long from, Long to, Pageable pageable);
    IncomeVoucherDto getById(Long id);
    byte[] exportReport(Long from, Long to, String exportedBy) throws Exception;
}