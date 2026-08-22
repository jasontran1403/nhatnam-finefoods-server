package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    long countByCreatedAtBetween(Long from, Long to);
    List<Customer> findByCreatedBySeller_Id(Long sellerId);
    List<Customer> findAllByDeletedAtIsNullOrderByCustomerCodeAscNameAsc();
    boolean existsByCustomerCode(String customerCode);
    // ── Admin search — thêm filter deleted ───────────────────────────────────
    @Query("SELECT c FROM Customer c WHERE " +
            "c.deletedAt IS NULL " +
            "AND (:q IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.companyName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.contractName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:type IS NULL OR c.customerType = :type) " +
            "AND (:isActive IS NULL OR c.isActive = :isActive) " +
            "AND (:sellerId IS NULL OR " +
            "     (:sellerId = 0L AND c.createdBySeller IS NULL) OR " +
            "     (c.createdBySeller.id = :sellerId))")
    Page<Customer> searchAdmin(@Param("q") String q,
                               @Param("type") Customer.CustomerType type,
                               @Param("isActive") Boolean isActive,
                               @Param("sellerId") Long sellerId,
                               Pageable pageable);

    // ── Seller search — thêm filter deleted ──────────────────────────────────
    @Query("SELECT c FROM Customer c WHERE " +
            "c.deletedAt IS NULL " +
            "AND (:q IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.companyName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.contractName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:type IS NULL OR c.customerType = :type) " +
            "AND (:isActive IS NULL OR c.isActive = :isActive) " +
            "AND (c.createdBySeller.id = :sellerId)")
    Page<Customer> searchBySeller(@Param("q") String q,
                                  @Param("type") Customer.CustomerType type,
                                  @Param("isActive") Boolean isActive,
                                  @Param("sellerId") Long sellerId,
                                  Pageable pageable);

    @Query("SELECT c FROM Customer c WHERE " +
            "c.deletedAt IS NULL " +
            "AND (:q IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.email) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.companyName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.contractName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "    OR LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:type IS NULL OR c.customerType = :type) " +
            "AND (:isActive IS NULL OR c.isActive = :isActive)")
    Page<Customer> search(@Param("q") String q,
                          @Param("type") Customer.CustomerType type,
                          @Param("isActive") Boolean isActive,
                          Pageable pageable);

    @Modifying
    @Query("UPDATE Customer c SET c.discountRate = :rate, c.updatedAt = :updatedAt WHERE c.id IN :ids")
    int bulkUpdateDiscount(@Param("ids") List<Long> ids,
                           @Param("rate") Integer rate,
                           @Param("updatedAt") Long updatedAt);

    @Modifying
    @Query("UPDATE Customer c SET c.isActive = :isActive, c.updatedAt = :updatedAt WHERE c.id IN :ids")
    int bulkUpdateActive(@Param("ids") List<Long> ids,
                         @Param("isActive") Boolean isActive,
                         @Param("updatedAt") Long updatedAt);

    Optional<Customer> findByTaxCode(String taxCode);
    // Thêm variant check deleted cho validate trùng khi tạo/update
    Optional<Customer> findByTaxCodeAndDeletedAtIsNull(String taxCode);

    List<Customer> findAllByOrderByCustomerCodeAscNameAsc();
    List<Customer> findByIsActiveTrueOrderByCustomerCodeAscNameAsc();

    Optional<Customer> findByCustomerCode(String customerCode);
    // Thêm variant check deleted
    Optional<Customer> findByCustomerCodeAndDeletedAtIsNull(String customerCode);

    Optional<Customer> findByPhone(String phone);
    // Thêm variant check deleted
    Optional<Customer> findByPhoneAndDeletedAtIsNull(String phone);

    Optional<Customer> findByIdAndIsActiveTrue(Long id);
    List<Customer> findByNameContainingIgnoreCase(String name);
    List<Customer> findByPhoneContaining(String phone);
    boolean existsByPhone(String phone);
    List<Customer> findByIsActiveTrue();

    @Query("SELECT c FROM Customer c WHERE c.deletedAt IS NULL AND c.isActive = true AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "c.phone LIKE CONCAT('%', :keyword, '%'))")
    Page<Customer> searchCustomers(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT c FROM Customer c WHERE c.deletedAt IS NULL AND c.isActive = true " +
            "AND c.createdBySeller.id = :sellerId " +
            "AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "c.phone LIKE CONCAT('%', :keyword, '%') OR " +
            "LOWER(c.customerCode) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Customer> searchCustomersBySeller(@Param("keyword") String keyword,
                                           @Param("sellerId") Long sellerId,
                                           Pageable pageable);

    long count();
}