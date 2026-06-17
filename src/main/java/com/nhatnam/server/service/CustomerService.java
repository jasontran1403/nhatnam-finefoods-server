package com.nhatnam.server.service;

import com.nhatnam.server.dto.request.CreateCustomerRequest;
import com.nhatnam.server.dto.request.UpdateCustomerRequest;
import com.nhatnam.server.dto.response.CustomerResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface CustomerService {

    // Tạo khách hàng mới
    CustomerResponse createCustomer(CreateCustomerRequest request, Long createdByUserId, Long assignedSellerId);

    // Cập nhật thông tin khách hàng
    CustomerResponse updateCustomer(Long id, UpdateCustomerRequest request);

    // Xóa khách hàng (soft delete)
    void deleteCustomer(Long id);

    // Lấy thông tin khách hàng theo ID
    CustomerResponse getCustomerById(Long id);

    // Lấy thông tin khách hàng theo số điện thoại
    CustomerResponse getCustomerByPhone(String phone);

    // Lấy danh sách tất cả khách hàng đang active
    List<CustomerResponse> getAllActiveCustomers();

    // Tìm kiếm khách hàng (phân trang)
    Page<CustomerResponse> searchCustomers(String keyword, Pageable pageable);

    // Tìm kiếm khách hàng theo tên
    List<CustomerResponse> findCustomersByName(String name);

    // Tìm kiếm khách hàng theo số điện thoại
    List<CustomerResponse> findCustomersByPhone(String phone);
}