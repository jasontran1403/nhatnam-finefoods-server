package com.nhatnam.server.service;

import com.nhatnam.server.dto.gift.GiftRecordDtos.GiftRecordDto;
import com.nhatnam.server.dto.gift.GiftRecordDtos.GiftRecordItemDto;
import com.nhatnam.server.dto.gift.GiftRecordDtos.GiftRecordPage;
import com.nhatnam.server.dto.gift.GiftRecordDtos.HandlerOption;
import com.nhatnam.server.dto.gift.GiftRecordDtos.Source;
import com.nhatnam.server.entity.GiftOrder;
import com.nhatnam.server.entity.GiftOrderItem;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.repository.GiftOrderRepository;
import com.nhatnam.server.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * QUẢN LÝ QUÀ TẶNG — gộp Đơn KM + Phiếu tặng quà đã duyệt, group theo đơn/phiếu.
 *
 * <p>Mỗi {@link GiftRecordDto} = 1 đơn hoặc 1 phiếu, danh sách sản phẩm KM nằm trong
 * {@code items}. Yêu cầu UI: card hiển thị mã đơn (badge), thời gian, KH, người xử lý,
 * số lượng sản phẩm KM; chi tiết items hiện trong modal khi click.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class GiftManagementService {

    private final OrderRepository orderRepository;
    private final GiftOrderRepository giftOrderRepository;

    /** Tiền tố ký hiệu OrderItem là quà tặng — cùng convention {@code DraftOrderService}. */
    private static final String PROMO_NOTE_PREFIX = "[KM]";

    @Transactional(readOnly = true)
    public GiftRecordPage list(String q, Long from, Long to, Long handlerId, int page, int size) {
        String qNorm = (q == null || q.isBlank()) ? null : q.trim();
        String qLower = qNorm == null ? null : qNorm.toLowerCase(Locale.ROOT);

        // Nguồn 1: đơn có SP KM. Query lọc theo mã đơn/tên KH/SĐT; tên SP lọc Java-side.
        List<GiftRecordDto> orderRows = collectPromoOrderRows(qNorm, from, to, handlerId);
        if (qLower != null) {
            List<GiftRecordDto> extra = collectPromoOrderRows(null, from, to, handlerId).stream()
                    .filter(r -> anyItemMatches(r, qLower)).toList();
            orderRows = mergeById(orderRows, extra);
        }

        // Nguồn 2: phiếu tặng quà đã duyệt.
        List<GiftRecordDto> giftRows = collectApprovedGiftRows(qNorm, from, to, handlerId);
        if (qLower != null) {
            List<GiftRecordDto> extra = collectApprovedGiftRows(null, from, to, handlerId).stream()
                    .filter(r -> anyItemMatches(r, qLower)).toList();
            giftRows = mergeById(giftRows, extra);
        }

        List<GiftRecordDto> all = new ArrayList<>(orderRows.size() + giftRows.size());
        all.addAll(orderRows);
        all.addAll(giftRows);
        all.sort(Comparator.comparing(
                (GiftRecordDto r) -> r.getCreatedAt() == null ? 0L : r.getCreatedAt()).reversed());

        int total = all.size();
        int fromIdx = Math.max(0, page * size);
        int toIdx = Math.min(total, fromIdx + size);
        List<GiftRecordDto> pageContent = fromIdx >= total ? List.of() : all.subList(fromIdx, toIdx);

        return GiftRecordPage.builder()
                .content(pageContent).totalElements(total).page(page).size(size).build();
    }

    @Transactional(readOnly = true)
    public List<HandlerOption> listHandlers() {
        Map<Long, String> merged = new LinkedHashMap<>();
        for (Object[] row : orderRepository.findPromoOrderHandlersNative(PROMO_NOTE_PREFIX)) {
            Long id = row[0] == null ? null : ((Number) row[0]).longValue();
            String name = row[1] == null ? null : row[1].toString();
            if (id != null && name != null) merged.putIfAbsent(id, name);
        }
        for (Object[] row : giftOrderRepository.findApprovedGiftHandlers()) {
            Long id = row[0] == null ? null : ((Number) row[0]).longValue();
            String name = row[1] == null ? null : row[1].toString();
            if (id != null && name != null) merged.putIfAbsent(id, name);
        }
        return merged.entrySet().stream()
                .map(e -> HandlerOption.builder().id(e.getKey()).name(e.getValue()).build())
                .sorted(Comparator.comparing(HandlerOption::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ── GROUP theo đơn ────────────────────────────────────────────────────────

    private List<GiftRecordDto> collectPromoOrderRows(String q, Long from, Long to, Long userId) {
        List<Object[]> rawIds = orderRepository.findOrderIdsWithPromoItems(
                PROMO_NOTE_PREFIX, q, from, to, userId);
        if (rawIds.isEmpty()) return List.of();
        List<Long> orderIds = new ArrayList<>(rawIds.size());
        for (Object[] row : rawIds) if (row[0] instanceof Number n) orderIds.add(n.longValue());
        if (orderIds.isEmpty()) return List.of();

        List<Order> orders = orderRepository.findAllByIdInWithItems(orderIds);
        Map<Long, Order> byId = new LinkedHashMap<>();
        for (Order o : orders) byId.put(o.getId(), o);

        List<GiftRecordDto> rows = new ArrayList<>();
        for (Long id : orderIds) {
            Order o = byId.get(id);
            if (o == null || o.getOrderItems() == null) continue;
            List<GiftRecordItemDto> giftItems = new ArrayList<>();
            for (OrderItem item : o.getOrderItems()) {
                if (item.getNotes() == null || !item.getNotes().startsWith(PROMO_NOTE_PREFIX)) continue;
                giftItems.add(GiftRecordItemDto.builder()
                        .productName(item.getProductName())
                        .unit(item.getUnit())
                        .quantity(item.getQuantity())
                        .note(extractPromoNote(item.getNotes()))
                        .build());
            }
            if (giftItems.isEmpty()) continue;

            rows.add(GiftRecordDto.builder()
                    .source(Source.ORDER)
                    .sourceId(o.getId())
                    .code(o.getOrderCode())
                    .createdAt(o.getCreatedAt())
                    .customerId(o.getCustomer() != null ? o.getCustomer().getId() : null)
                    .customerName(displayCustomerName(o))
                    // NGƯỜI XỬ LÝ = user đăng nhập đã tạo đơn, KHÔNG dùng orderedByName
                    // (đó là "tên người đặt" seller ghi trên form, ví dụ "Tam" trong NĐ-05364).
                    .handlerName(o.getUser() != null ? o.getUser().getFullName() : null)
                    .handlerId(o.getUser() != null ? o.getUser().getId() : null)
                    .warehouseName(o.getWarehouseName())
                    .note(null)
                    .giftOrderStatus(null)
                    .items(giftItems)
                    .itemCount(giftItems.size())
                    .build());
        }
        return rows;
    }

    private List<GiftRecordDto> collectApprovedGiftRows(String q, Long from, Long to, Long createdById) {
        List<GiftOrder> gifts = giftOrderRepository.findApprovedForGiftManagement(q, from, to, createdById);
        List<GiftRecordDto> rows = new ArrayList<>();
        for (GiftOrder g : gifts) {
            List<GiftOrderItem> items = g.getItems() == null ? List.of() : g.getItems();
            List<GiftRecordItemDto> giftItems = new ArrayList<>(items.size());
            for (GiftOrderItem it : items) {
                giftItems.add(GiftRecordItemDto.builder()
                        .productName(it.getProductName())
                        .unit(it.getUnit())
                        .quantity(it.getQuantity())
                        .note(null)
                        .build());
            }
            if (giftItems.isEmpty()) continue;

            rows.add(GiftRecordDto.builder()
                    .source(Source.GIFT_ORDER)
                    .sourceId(g.getId())
                    .code(g.getCode())
                    .createdAt(g.getCreatedAt())
                    .customerId(g.getCustomer() != null ? g.getCustomer().getId() : null)
                    .customerName(g.getCustomerName())
                    .handlerName(g.getCreatedBy() != null
                            ? firstNonBlank(g.getCreatedBy().getFullName(), g.getCreatedByName())
                            : g.getCreatedByName())
                    .handlerId(g.getCreatedBy() != null ? g.getCreatedBy().getId() : null)
                    .warehouseName(g.getWarehouse() != null ? g.getWarehouse().getName() : null)
                    .note(g.getNote())
                    .giftOrderStatus(g.getStatus() != null ? g.getStatus().name() : null)
                    .items(giftItems)
                    .itemCount(giftItems.size())
                    .build());
        }
        return rows;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<GiftRecordDto> mergeById(List<GiftRecordDto> primary, List<GiftRecordDto> extra) {
        if (extra.isEmpty()) return primary;
        Map<String, GiftRecordDto> seen = new LinkedHashMap<>();
        for (GiftRecordDto r : primary) seen.put(rowKey(r), r);
        for (GiftRecordDto r : extra)   seen.putIfAbsent(rowKey(r), r);
        return new ArrayList<>(seen.values());
    }

    private String rowKey(GiftRecordDto r) { return r.getSource() + "|" + r.getSourceId(); }

    private boolean anyItemMatches(GiftRecordDto r, String qLower) {
        if (r.getItems() == null) return false;
        for (GiftRecordItemDto it : r.getItems()) {
            String name = it.getProductName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(qLower)) return true;
        }
        return false;
    }

    private String displayCustomerName(Order o) {
        if (o.getCustomerName() != null && !o.getCustomerName().isBlank()) return o.getCustomerName();
        if (o.getCompanyName()  != null && !o.getCompanyName().isBlank())  return o.getCompanyName();
        if (o.getCustomer() != null) {
            if (o.getCustomer().getCompanyName() != null && !o.getCustomer().getCompanyName().isBlank())
                return o.getCustomer().getCompanyName();
            return o.getCustomer().getName();
        }
        return "Khách lẻ";
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private String extractPromoNote(String notes) {
        if (notes == null) return "";
        String withSpace = PROMO_NOTE_PREFIX + " ";
        if (notes.startsWith(withSpace))         return notes.substring(withSpace.length()).trim();
        if (notes.startsWith(PROMO_NOTE_PREFIX)) return notes.substring(PROMO_NOTE_PREFIX.length()).trim();
        return "";
    }
}
