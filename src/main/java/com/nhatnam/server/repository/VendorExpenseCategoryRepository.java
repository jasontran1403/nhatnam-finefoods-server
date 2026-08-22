package com.nhatnam.server.repository;

import com.nhatnam.server.entity.VendorExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Danh mục khoản chi — POOL DÙNG CHUNG cho mọi nhà cung cấp.
 *
 * <p>Các finder theo {@code vendorId} cũ đã bị loại bỏ: nhãn không còn thuộc về NCC nào.
 */
public interface VendorExpenseCategoryRepository extends JpaRepository<VendorExpenseCategory, Long> {

    List<VendorExpenseCategory> findAllByOrderByNameAsc();

    List<VendorExpenseCategory> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<VendorExpenseCategory> findByNameIgnoreCase(String name);
}
