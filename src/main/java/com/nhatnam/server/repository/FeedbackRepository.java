package com.nhatnam.server.repository;

import com.nhatnam.server.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    /**
     * Danh sách feedback đã lọc theo search, phân trang sort desc theo createdAt —
     * dùng cho trang list ở owner/admin.
     *
     * <p><b>Vì sao nhận {@code qLike} thay vì {@code q} rồi CONCAT trong JPQL?</b>
     * Hibernate 6.2 với {@code LOWER(CONCAT('%', :q, '%'))} khi {@code :q = null}
     * ném lỗi type-check. Fix bằng cách pre-compute {@code qLike} ở service
     * ({@code null} khi không search, {@code "%abc%"} khi search "abc"), rồi JPQL chỉ
     * so sánh cột đã LOWER với param — không có CONCAT, không có LOWER trên param.
     *
     * <p><b>Vì sao KHÔNG có {@code LOWER(f.content)} trong search?</b> Cột {@code content}
     * annotated {@code @Lob TEXT}. Hibernate 6 map {@code @Lob String} thành kiểu
     * CLOB nội bộ, và {@code LOWER()} không apply được lên CLOB — ném
     * {@code "Parameter 1 of function lower() has type STRING, but argument is of type
     * java.lang.String"} (message hơi nhầm về type). Search theo nội dung bỏ đi cho gọn;
     * 4 field còn lại (productName, customerName, orderCode, contactName) đều là
     * VARCHAR đủ tìm ra 99% case, và tên SP là chính theo yêu cầu ban đầu.
     *
     * @param qLike  pattern LIKE đã hạ chữ thường + wrap %…%, hoặc null nếu không lọc
     */
    @Query("""
            SELECT f FROM Feedback f
             WHERE (:qLike IS NULL
                    OR LOWER(f.productName)  LIKE :qLike
                    OR LOWER(f.customerName) LIKE :qLike
                    OR LOWER(f.orderCode)    LIKE :qLike
                    OR LOWER(f.contactName)  LIKE :qLike)
             ORDER BY f.createdAt DESC
            """)
    Page<Feedback> search(@Param("qLike") String qLike, Pageable pageable);

    /**
     * Danh sách feedback của MỘT đơn — dùng cho seller/warehouse: khi tra mã đơn,
     * nếu đã có feedback thì hiện lịch sử để không tạo trùng.
     *
     * <p>Sort DESC theo createdAt để feedback mới nhất lên đầu.
     */
    List<Feedback> findByOrderCodeOrderByCreatedAtDesc(String orderCode);
}