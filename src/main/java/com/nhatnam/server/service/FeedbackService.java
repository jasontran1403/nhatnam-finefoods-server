package com.nhatnam.server.service;

import com.nhatnam.server.dto.feedback.FeedbackDtos.*;
import com.nhatnam.server.entity.Feedback;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.FeedbackRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * FEEDBACK SERVICE — nghiệp vụ tạo và đọc feedback KH về SP trong đơn.
 *
 * <p>Tạo: SUPER_SELLER / SELLER / WAREHOUSE — form tra mã đơn, chọn SP, ghi note,
 * đính kèm ảnh. Đọc list: OWNER / ADMIN — sắp xếp mới nhất trước, search theo tên SP.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final OrderRepository orderRepository;

    /** Delimiter giữa các URL ảnh trong cột TEXT — newline dễ đọc khi debug DB. */
    private static final String IMAGE_URL_DELIMITER = "\n";

    // ── Tra cứu đơn để dựng form ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrderLookupDto lookupOrder(String orderCode) {
        if (orderCode == null || orderCode.isBlank()) {
            throw new RuntimeException("Vui lòng nhập mã đơn hàng");
        }
        Order o = orderRepository.findByOrderCode(orderCode.trim())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderCode));

        List<OrderProductDto> products = (o.getOrderItems() == null ? List.<OrderItem>of() : o.getOrderItems())
                .stream()
                // Bỏ SP KM ([KM]) — feedback về SP KH thật sự mua, không phải quà tặng.
                .filter(it -> it.getNotes() == null || !it.getNotes().startsWith("[KM]"))
                .map(it -> OrderProductDto.builder()
                        .productId(it.getProductId())   // OrderItem lưu productId trực tiếp
                        .productName(it.getProductName())
                        .unit(it.getUnit())
                        .quantity(it.getQuantity())
                        .build())
                .toList();

        return OrderLookupDto.builder()
                .orderId(o.getId())
                .orderCode(o.getOrderCode())
                .customerName(o.getCustomerName())
                .customerPhone(o.getCustomerPhone())
                .products(products)
                .build();
    }

    // ── Tạo feedback ─────────────────────────────────────────────────────────

    @Transactional
    public FeedbackDto create(CreateFeedbackRequest req, User actor) {
        Order o = orderRepository.findByOrderCode(req.getOrderCode().trim())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + req.getOrderCode()));

        String contactName  = firstNonBlank(req.getContactName(),  o.getCustomerName());
        String contactPhone = firstNonBlank(req.getContactPhone(), o.getCustomerPhone());

        Feedback fb = Feedback.builder()
                .order(o)
                .orderCode(o.getOrderCode())
                .productId(req.getProductId())
                .productName(req.getProductName().trim())
                .contactName(contactName)
                .contactPhone(contactPhone)
                .content(req.getContent().trim())
                .imageUrls(joinImageUrls(req.getImageUrls()))
                .createdBy(actor)
                .createdByName(actor != null ? firstNonBlank(actor.getFullName(), actor.getUsername()) : null)
                .customerName(o.getCustomerName())
                .build();

        fb = feedbackRepository.save(fb);
        return toDto(fb);
    }

    // ── List cho OWNER / ADMIN ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FeedbackPage list(String q, int page, int size) {
        // Pre-compute qLike ở service (đã hạ chữ thường + wrap %...%) — xem javadoc
        // của FeedbackRepository.search vì sao phải làm vậy (Hibernate 6 type bug).
        String qLike = null;
        if (q != null && !q.isBlank()) {
            qLike = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        }
        Page<Feedback> pg = feedbackRepository.search(qLike, PageRequest.of(page, size));
        return FeedbackPage.builder()
                .content(pg.getContent().stream().map(this::toDto).toList())
                .totalElements(pg.getTotalElements())
                .page(page)
                .size(size)
                .build();
    }

    // ── List theo mã đơn (seller/warehouse xem feedback cũ của đơn) ─────────

    @Transactional(readOnly = true)
    public List<FeedbackDto> listByOrderCode(String orderCode) {
        if (orderCode == null || orderCode.isBlank()) return List.of();
        return feedbackRepository.findByOrderCodeOrderByCreatedAtDesc(orderCode.trim())
                .stream().map(this::toDto).toList();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Join list URL thành TEXT với newline delimiter. Null/rỗng → null (không lưu). */
    private String joinImageUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        return urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(String::trim)
                .reduce((a, b) -> a + IMAGE_URL_DELIMITER + b)
                .orElse(null);
    }

    /** Split TEXT về list URL. Trả list rỗng thay vì null để FE dễ dùng. */
    private List<String> splitImageUrls(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return java.util.Arrays.stream(joined.split("\\R"))  // \R match any newline
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private FeedbackDto toDto(Feedback f) {
        return FeedbackDto.builder()
                .id(f.getId())
                .createdAt(f.getCreatedAt())
                .orderCode(f.getOrderCode())
                .orderId(f.getOrder() != null ? f.getOrder().getId() : null)
                .customerName(f.getCustomerName())
                .productName(f.getProductName())
                .content(f.getContent())
                .contactName(f.getContactName())
                .contactPhone(f.getContactPhone())
                .createdByName(f.getCreatedByName())
                .imageUrls(splitImageUrls(f.getImageUrls()))
                .build();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
