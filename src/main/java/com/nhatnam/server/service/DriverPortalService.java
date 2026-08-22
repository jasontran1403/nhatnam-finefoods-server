package com.nhatnam.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.entity.Driver;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.OrderLog;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.DriverRepository;
import com.nhatnam.server.repository.OrderLogRepository;
import com.nhatnam.server.repository.OrderRepository;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * CỔNG TÀI XẾ — dashboard các đơn ĐANG GIAO của chính tài xế đang đăng nhập.
 *
 * <h3>Cách khớp đơn với tài xế</h3>
 * Đơn hàng lưu danh sách tài xế ở {@code Order.deliveryInfoJson}:
 * <pre>
 *   [{"driverId":3,"name":"Nguyễn Văn A","type":"TRUCK","trips":1}, ...]
 * </pre>
 * Khớp ưu tiên theo {@code driverId}; fallback theo {@code name} để tương thích
 * dữ liệu cũ (trước đây picker chỉ lưu tên).
 *
 * <h3>Liên kết User ↔ Driver</h3>
 * 1 user (role DRIVER) ↔ 1 bản ghi {@link Driver} qua {@code Driver.user}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverPortalService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Trạng thái đơn được coi là "đang giao" — hiển thị trên dashboard tài xế. */
    private static final List<OrderStatus> ACTIVE_STATUSES =
            List.of(OrderStatus.DELIVERING, OrderStatus.READY, OrderStatus.PREPARING);

    private final OrderRepository orderRepo;
    private final OrderLogRepository orderLogRepo;
    private final DriverRepository driverRepo;
    private final UserRepository userRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // LIÊN KẾT USER ↔ TÀI XẾ
    // ══════════════════════════════════════════════════════════════════════════

    /** Bản ghi tài xế gắn với user đang đăng nhập (null nếu chưa được match). */
    public Driver driverOf(User user) {
        return driverRepo.findByUser_Id(user.getId()).orElse(null);
    }

    /**
     * Gắn 1 user (role DRIVER) với 1 bản ghi tài xế. Ràng buộc 1–1:
     * user đã gắn tài xế khác, hoặc tài xế đã gắn user khác → báo lỗi.
     */
    @Transactional
    public Driver linkUser(Long driverId, Long userId) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài xế: " + driverId));

        if (userId == null) {                 // bỏ liên kết
            driver.setUser(null);
            return driverRepo.save(driver);
        }

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên: " + userId));

        driverRepo.findByUser_Id(userId).ifPresent(existing -> {
            if (!Objects.equals(existing.getId(), driverId))
                throw new IllegalStateException(
                        "Nhân viên này đã được gắn với tài xế \"" + existing.getName() + "\"");
        });

        if (driver.getUser() != null && driver.getUser().getId() != userId)
            throw new IllegalStateException(
                    "Tài xế này đã được gắn với tài khoản khác");

        // Gắn tài khoản vào tài xế + BỔ SUNG role DRIVER cho tài khoản (nếu chưa có)
        // để tài khoản đó truy cập được phần Tài xế (đơn giao, lương theo km…).
        java.util.Set<Role> roles = user.getRoles() != null
                ? new java.util.HashSet<>(user.getRoles()) : new java.util.HashSet<>();
        if (user.getRole() != null) roles.add(user.getRole());
        if (!roles.contains(Role.DRIVER)) {
            roles.add(Role.DRIVER);
            user.setRoles(roles);
            userRepo.save(user);
        }

        driver.setUser(user);
        return driverRepo.save(driver);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD
    // ══════════════════════════════════════════════════════════════════════════

    /** Các đơn đang giao được gán cho tài xế này, mới nhất trước. */
    public List<Map<String, Object>> myActiveOrders(User user) {
        Driver driver = driverOf(user);
        if (driver == null) return List.of();

        return orderRepo.findAll().stream()
                .filter(o -> o.getStatus() != null && ACTIVE_STATUSES.contains(o.getStatus()))
                .filter(o -> isAssignedTo(o, driver))
                .sorted(Comparator.comparing(
                                (Order o) -> o.getDeliveryDatetime() != null
                                        ? o.getDeliveryDatetime() : nz(o.getCreatedAt()))
                        .reversed())
                .map(o -> toSummary(o, driver))
                .toList();
    }

    /** Chi tiết 1 đơn — chỉ trả về nếu đơn đó thuộc tài xế đang đăng nhập. */
    public Map<String, Object> myOrderDetail(User user, Long orderId) {
        Driver driver = driverOf(user);
        if (driver == null) throw new IllegalStateException("Tài khoản chưa được gắn với tài xế nào");

        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng: " + orderId));

        if (!isAssignedTo(order, driver))
            throw new IllegalStateException("Đơn hàng này không được giao cho bạn");

        return toDetail(order, driver);
    }

    /**
     * Tài xế bấm "Hoàn thành" — xác nhận đã giao xong.
     * Đơn chuyển sang {@link OrderStatus#PENDING_PAYMENT} (chờ thanh toán),
     * đồng bộ với luồng xác nhận giao hàng bên kho.
     */
    @Transactional
    public Map<String, Object> completeDelivery(User user, Long orderId, String receiverName, String note) {
        Driver driver = driverOf(user);
        if (driver == null) throw new IllegalStateException("Tài khoản chưa được gắn với tài xế nào");

        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng: " + orderId));

        if (!isAssignedTo(order, driver))
            throw new IllegalStateException("Đơn hàng này không được giao cho bạn");

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.COMPLETED)
            throw new IllegalStateException("Đơn hàng đã được xác nhận giao trước đó");

        if (order.getStatus() == OrderStatus.CANCELLED)
            throw new IllegalStateException("Đơn hàng đã bị huỷ");

        long now = System.currentTimeMillis();
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setPendingPaymentAt(now);
        order.setUpdatedAt(now);
        if (receiverName != null && !receiverName.isBlank()) order.setReceiverName(receiverName.trim());
        if (note != null && !note.isBlank()) {
            String prev = order.getNotes() != null ? order.getNotes() + "\n" : "";
            order.setNotes(prev + "[Tài xế " + driver.getName() + "] " + note.trim());
        }
        orderRepo.save(order);

        // GHI NHẬT KÝ ĐƠN HÀNG — trước đây bước này bị thiếu nên lịch sử đơn
        // không thể hiện ai là người xác nhận đã giao.
        orderLogRepo.save(OrderLog.builder()
                .order(order)
                .action(OrderStatus.PENDING_PAYMENT.name())
                .actorName(driver.getName())
                .actorRole("DRIVER")
                .note(buildDeliveryLogNote(receiverName, note))
                .createdAt(now)
                .build());

        log.info("[Driver] {} xác nhận giao xong đơn {}", driver.getName(), order.getOrderCode());
        return Map.of(
                "id", order.getId(),
                "orderCode", order.getOrderCode(),
                "status", order.getStatus().name()
        );
    }

    /**
     * Nội dung ghi chú cho dòng nhật ký lúc tài xế xác nhận đã giao.
     * Gộp người nhận + ghi chú của tài xế; trả {@code null} nếu không có gì.
     */
    private static String buildDeliveryLogNote(String receiverName, String note) {
        List<String> parts = new ArrayList<>();
        if (receiverName != null && !receiverName.isBlank())
            parts.add("Người nhận: " + receiverName.trim());
        if (note != null && !note.isBlank())
            parts.add(note.trim());
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /** Đơn có gán tài xế này không (theo driverId, fallback theo tên). */
    private boolean isAssignedTo(Order order, Driver driver) {
        for (Map<String, Object> d : parseDeliveryInfo(order)) {
            Object id = d.get("driverId");
            if (id instanceof Number n && n.longValue() == driver.getId()) return true;

            Object name = d.get("name");
            if (name != null && driver.getName() != null
                    && normalize(String.valueOf(name)).equals(normalize(driver.getName()))) return true;
        }
        return false;
    }

    private List<Map<String, Object>> parseDeliveryInfo(Order order) {
        String json = order.getDeliveryInfoJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> toSummary(Order o, Driver driver) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("orderCode", o.getOrderCode());
        m.put("status", o.getStatus() != null ? o.getStatus().name() : null);
        m.put("customerName", firstNonBlank(o.getCompanyName(), o.getCustomerName()));
        m.put("customerPhone", firstNonBlank(o.getCompanyPhone(), o.getCustomerPhone()));
        m.put("deliveryAddress", buildFullAddress(
                firstNonBlank(o.getDeliveryAddress(), o.getShippingAddress(), o.getCompanyAddress()),
                o.getWardName(), o.getProvinceName()));
        m.put("deliveryDatetime", o.getDeliveryDatetime());
        m.put("warehouseName", o.getWarehouseName());
        m.put("itemCount", o.getOrderItems() != null ? o.getOrderItems().size() : 0);
        m.put("finalAmount", o.getFinalAmount());
        m.put("showPrices", !Boolean.TRUE.equals(o.getHideAllPrices()));
        m.put("createdAt", o.getCreatedAt());
        m.put("trips", tripsOf(o, driver));
        return m;
    }

    private Map<String, Object> toDetail(Order o, Driver driver) {
        Map<String, Object> m = new LinkedHashMap<>(toSummary(o, driver));
        m.put("receiverName", o.getReceiverName());
        m.put("contactName", o.getContactName());
        m.put("notes", o.getNotes());
        m.put("paymentStatus", o.getPaymentStatus() != null ? o.getPaymentStatus().name() : null);
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("subtotal", o.getSubtotal());
        m.put("vatAmount", o.getVatAmount());
        m.put("discountAmount", o.getDiscountAmount());
        m.put("surcharge", o.getSurcharge());

        List<Map<String, Object>> items = new ArrayList<>();
        if (o.getOrderItems() != null) {
            for (OrderItem it : o.getOrderItems()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("id", it.getId());
                im.put("productName", it.getProductName());
                im.put("unit", it.getUnit());
                im.put("quantity", readQuantity(it));
                im.put("packaging", it.getPackagingDescriptionSnapshot());
                items.add(im);
            }
        }
        m.put("items", items);
        m.put("drivers", parseDeliveryInfo(o));
        return m;
    }

    /** Số lượng — đọc qua reflection để không phụ thuộc tên getter cụ thể. */
    private Object readQuantity(OrderItem it) {
        for (String getter : new String[]{"getQuantity", "getQty", "getAmount"}) {
            try {
                Object v = OrderItem.class.getMethod(getter).invoke(it);
                if (v != null) return v;
            } catch (Exception ignored) { }
        }
        return BigDecimal.ZERO;
    }

    private int tripsOf(Order o, Driver driver) {
        for (Map<String, Object> d : parseDeliveryInfo(o)) {
            Object id = d.get("driverId");
            Object name = d.get("name");
            boolean hit = (id instanceof Number n && n.longValue() == driver.getId())
                    || (name != null && driver.getName() != null
                    && normalize(String.valueOf(name)).equals(normalize(driver.getName())));
            if (hit) {
                Object t = d.get("trips");
                return t instanceof Number n2 ? n2.intValue() : 1;
            }
        }
        return 0;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Ghép "{địa chỉ}, {phường}, {tỉnh}", bỏ phần trống (không kèm phường/tỉnh khi nhận tại kho). */
    private static String buildFullAddress(String base, String ward, String province) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (base != null && !base.isBlank()) parts.add(base.trim());
        if (ward != null && !ward.isBlank()) parts.add(ward.trim());
        if (province != null && !province.isBlank()) parts.add(province.trim());
        return String.join(", ", parts);
    }

    private static long nz(Long v) { return v != null ? v : 0L; }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("\\s+", " ");
    }
}