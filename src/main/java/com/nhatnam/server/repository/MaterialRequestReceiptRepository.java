package com.nhatnam.server.repository;

import com.nhatnam.server.entity.MaterialRequestReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialRequestReceiptRepository extends JpaRepository<MaterialRequestReceipt, Long> {

    /** Lịch sử các đợt nhận của 1 phiếu, cũ → mới. */
    List<MaterialRequestReceipt> findByMaterialRequest_IdOrderBySequenceNoAsc(Long materialRequestId);

    /** Số đợt đã lưu — dùng để cấp sequenceNo cho đợt tiếp theo. */
    int countByMaterialRequest_Id(Long materialRequestId);
}
