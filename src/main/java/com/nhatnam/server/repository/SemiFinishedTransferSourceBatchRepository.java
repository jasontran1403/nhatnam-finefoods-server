package com.nhatnam.server.repository;

import com.nhatnam.server.entity.SemiFinishedTransferSourceBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SemiFinishedTransferSourceBatchRepository extends JpaRepository<SemiFinishedTransferSourceBatch, Long> {

    /**
     * Tất cả dòng nguồn (mỗi dòng = 1 phần kg lấy từ 1 batch trong 1 phiếu chuyển)
     * cho 1 danh sách batch — dùng để tính hao hụt phân bổ theo WorkOrder.
     * Chỉ lấy các dòng thuộc phiếu chuyển đã RECEIVED (đã có actualReceivedWeight).
     */
    @Query("SELECT sb FROM SemiFinishedTransferSourceBatch sb " +
            "JOIN FETCH sb.transferLine tl " +
            "JOIN FETCH tl.transferNote tn " +
            "WHERE sb.batch.id IN :batchIds AND tn.status = 'RECEIVED'")
    List<SemiFinishedTransferSourceBatch> findReceivedByBatchIds(@Param("batchIds") List<Long> batchIds);
}
