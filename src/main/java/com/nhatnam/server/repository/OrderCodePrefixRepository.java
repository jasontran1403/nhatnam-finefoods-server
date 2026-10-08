package com.nhatnam.server.repository;

import com.nhatnam.server.entity.OrderCodePrefix;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderCodePrefixRepository extends JpaRepository<OrderCodePrefix, Long> {

    Optional<OrderCodePrefix> findByIsActiveTrue();

    List<OrderCodePrefix> findAll();

    /**
     * BUG FIX 1.1 (race order_code): Increment counter ATOMIC.
     *
     * <p>Câu UPDATE này được MySQL InnoDB serialize hoàn toàn — 2 request cùng
     * gọi phương thức này sẽ:
     * <ol>
     *   <li>Request A UPDATE, row bị lock</li>
     *   <li>Request B chờ lock của A release</li>
     *   <li>A commit, counter tăng 1</li>
     *   <li>B tiếp tục, UPDATE thấy giá trị mới của A → tăng tiếp 1</li>
     * </ol>
     *
     * <p>Không còn "2 request cùng đọc counter=N → cùng sinh order_code với N+1".
     *
     * <p>Sau khi gọi, caller đọc lại value qua {@link #findById(Object)} để lấy
     * số mới nhất. Vì transaction đang giữ lock trên row, số này an toàn tuyệt đối.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE OrderCodePrefix p SET p.counter = p.counter + 1 WHERE p.id = :id")
    int incrementCounter(@Param("id") Long id);
}