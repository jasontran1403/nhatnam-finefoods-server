package com.nhatnam.server.repository.tools;

import com.nhatnam.server.entity.tools.ToolCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolCustomerRepository extends JpaRepository<ToolCustomer, Long> {
    List<ToolCustomer> findAllByOrderByIdAsc();
    Optional<ToolCustomer> findFirstByTenKhachHangIgnoreCase(String tenKhachHang);
    Optional<ToolCustomer> findFirstByMaKhachHangIgnoreCase(String maKhachHang);
    Optional<ToolCustomer> findFirstByMaSoThue(String maSoThue);
}