package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.entity.Order;
import com.nhatnam.server.entity.OrderItem;
import com.nhatnam.server.entity.PaymentTransaction;
import com.nhatnam.server.enumtype.OrderStatus;
import com.nhatnam.server.enumtype.PaymentStatus;
import com.nhatnam.server.enumtype.VatRate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ENDPOINT DEV-ONLY — nạp data từng bước để test bonus split-pay cross-month.
 *
 * <h3>Flow 3 bước (gọi theo thứ tự)</h3>
 * <ol>
 *   <li>{@code POST /scenario-step1} — Wipe DB + tạo order T7 (3.3tr/vat 300k) + thu 1.8tr T7
 *       → status PENDING_PAYMENT, paid 1.8tr. Click Tính bonus T7 verify.</li>
 *   <li>{@code POST /scenario-step2} — thu 1.3tr T8 → paid 3.1tr, vẫn PENDING_PAYMENT.
 *       Click Tính bonus T8 verify.</li>
 *   <li>{@code POST /scenario-step3} — thu 200k T9 → paid 3.3tr, mark COMPLETED.
 *       Click Tính bonus T9 verify.</li>
 * </ol>
 *
 * <p>Bất kỳ bước nào gọi lại cũng cộng dồn (step2 có thể gọi lại nhưng sẽ insert
 * payment trùng → tránh gọi lại). Muốn reset: gọi {@code /wipe} rồi step1.
 *
 * <p>CHỈ dùng cho dev/stage — bảo vệ bằng {@code @PreAuthorize("hasRole('OWNER')")}.
 */
@RestController
@RequestMapping("/api/dev/bonus-test")
@RequiredArgsConstructor
@Log4j2
public class BonusTestController {

    @PersistenceContext
    private EntityManager em;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long   WH_ID     = 2L;
    private static final String WH_NAME   = "Kho Phổ Quang";
    private static final long   CUST_ID   = 20L;             // Kamereo — đổi nếu cần
    private static final String CUST_NAME = "Công Ty TNHH Kamereo";
    private static final long   P1_ID     = 1L;
    private static final String P1_NAME   = "Kem Trang Trí Bánh Rich's Nhãn Vàng Sô-cô-la";
    private static final String P1_UNIT   = "Hộp";
    private static final String ORDER_CODE = "TEST-SPLIT-01";

    // ────────────────────────────────────────────────────────────────────────
    // WIPE — xoá sạch orders + payments + bonus
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/wipe")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public ApiResponse<Map<String, Object>> wipe() {
        wipeAllOrdersAndPayments();
        return ApiResponse.ok("Đã xoá sạch orders + payments + bonus",
                Map.of("ok", true));
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 1 — Wipe + tạo order T7 + thu 1.8tr T7 (status PENDING_PAYMENT)
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/scenario-step1")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public ApiResponse<Map<String, Object>> step1(
            @RequestParam(defaultValue = "13") long sellerId) {

        // 1) Wipe sạch trước
        wipeAllOrdersAndPayments();

        // 2) Tạo order T7 — PENDING_PAYMENT, paid = 1.800.000 (sau khi thu T7)
        long createdT7 = toEpochMs(2026, 7, 15, 10, 0);
        long payT7     = toEpochMs(2026, 7, 20, 14, 0);

        Order o = buildOrder(sellerId, createdT7);
        o.setPaidAmount(new BigDecimal("1800000"));
        o.setActualAmountPaid(new BigDecimal("1800000"));
        o.setPaymentStatus(PaymentStatus.PARTIAL);
        o.setStatus(OrderStatus.PENDING_PAYMENT);
        o.setUpdatedAt(payT7);
        em.persist(o);
        em.flush();

        em.persist(buildOrderItem(o));
        insertPt(o, new BigDecimal("1800000"), payT7, "Thu T7");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("note", "Trạng thái sau Step 1: order PENDING_PAYMENT, paid=1.8tr/3.3tr");
        expected.put("T7", monthExpect(
                "3.000.000  (đơn tạo T7, trừ VAT 300k)",
                "1.363.636,36  (= (3.3tr−1.8tr) × 3/3.3 — còn chưa thu 1.5tr gross = 1.363.636 net)",
                "1.636.363,64  (= 1.800.000 × 0,9091 — tiền thu T7 trừ VAT prorate)"));
        expected.put("T8", monthExpect("0", "0 (chưa có đơn tạo T8)", "0"));
        expected.put("T9", monthExpect("0", "0", "0"));

        return ApiResponse.ok("Step 1 OK — click Tính bonus tháng 7 để verify", summary(o, 1, expected));
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 2 — thu thêm 1.3tr T8 (status vẫn PENDING_PAYMENT, paid = 3.1tr)
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/scenario-step2")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public ApiResponse<Map<String, Object>> step2() {
        Order o = findTestOrderOrThrow();
        long payT8 = toEpochMs(2026, 8, 20, 11, 0);

        insertPt(o, new BigDecimal("1300000"), payT8, "Thu T8");

        o.setPaidAmount(new BigDecimal("3100000"));
        o.setActualAmountPaid(new BigDecimal("3100000"));
        o.setPaymentStatus(PaymentStatus.PARTIAL);
        o.setUpdatedAt(payT8);
        em.merge(o);
        em.flush();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("note", "Sau Step 2: paid=3.1tr/3.3tr, vẫn PENDING_PAYMENT");
        expected.put("T7", monthExpect(
                "3.000.000  (không đổi)",
                "181.818,18  (còn chưa thu 200k gross = 181.818 net ở đơn T7 — HOLD giảm so với Step 1)",
                "1.636.363,64  (không đổi — PT T7 vẫn vậy)"));
        expected.put("T8", monthExpect("0 (không có đơn tạo T8)", "0",
                "1.181.818,18  (= 1.300.000 × 0,9091)"));
        expected.put("T9", monthExpect("0", "0", "0"));

        return ApiResponse.ok("Step 2 OK — click Tính bonus tháng 8 để verify", summary(o, 2, expected));
    }

    // ────────────────────────────────────────────────────────────────────────
    // STEP 3 — thu nốt 200k T9 → paid=3.3tr=final → mark COMPLETED
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/scenario-step3")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public ApiResponse<Map<String, Object>> step3() {
        Order o = findTestOrderOrThrow();
        long payT9 = toEpochMs(2026, 9, 10, 15, 0);

        insertPt(o, new BigDecimal("200000"), payT9, "Thu T9 (đủ)");

        o.setPaidAmount(new BigDecimal("3300000"));
        o.setActualAmountPaid(new BigDecimal("3300000"));
        o.setPaymentStatus(PaymentStatus.PAID);
        o.setStatus(OrderStatus.COMPLETED);
        o.setUpdatedAt(payT9);
        em.merge(o);
        em.flush();

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("note", "Sau Step 3: paid=3.3tr=final, status COMPLETED → không còn HOLD ở T7");
        expected.put("T7", monthExpect(
                "3.000.000",
                "0  (giờ COMPLETED, không PENDING nữa → không tính hold)",
                "1.636.363,64"));
        expected.put("T8", monthExpect("0", "0", "1.181.818,18"));
        expected.put("T9", monthExpect("0", "0", "181.818,18  (= 200.000 × 0,9091)"));
        expected.put("Σ chia HH 3 tháng", "1.636.364 + 1.181.818 + 181.818 = 3.000.000 (= net 3tr)");

        return ApiResponse.ok("Step 3 OK — đơn đã COMPLETED, click Tính bonus tháng 9", summary(o, 3, expected));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private Order buildOrder(long sellerId, long createdAt) {
        Order o = new Order();
        o.setVersion(0L);
        o.setOrderCode(ORDER_CODE);
        o.setUser(em.getReference(com.nhatnam.server.entity.User.class, sellerId));
        o.setCustomer(em.getReference(com.nhatnam.server.entity.Customer.class, CUST_ID));
        o.setCustomerName(CUST_NAME);
        o.setCustomerType("COMPANY");
        o.setWarehouseId(WH_ID);
        o.setWarehouseName(WH_NAME);
        o.setShippingAddress("Nhận tại kho");
        o.setDeliveryAddress("Nhận tại kho");
        o.setDiscountRate(0);
        o.setVatRate(VatRate.TEN);
        o.setSubtotal(new BigDecimal("3300000"));
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setVatAmount(new BigDecimal("300000"));
        o.setTotalAmount(new BigDecimal("3300000"));
        o.setFinalAmount(new BigDecimal("3300000"));
        o.setPaymentMethod("CASH");
        o.setPendingPaymentAt(createdAt);
        o.setCreatedAt(createdAt);
        o.setKpiUserId(sellerId);
        o.setDeliveryInfoJson("[{\"name\":\"Đinh Đặng Thành\",\"type\":\"TRUCK\",\"trips\":1,\"role\":\"PRIMARY\"}]");
        o.setRequirePrepayment(false);
        o.setHideAllPrices(false);
        o.setShowPrices(true);
        o.setSurcharge(BigDecimal.ZERO);
        o.setOverpaidAmount(BigDecimal.ZERO);
        o.setRefundedAmount(BigDecimal.ZERO);
        o.setPendingRefundAmount(BigDecimal.ZERO);
        o.setCreditedFromSource(BigDecimal.ZERO);
        return o;
    }

    private OrderItem buildOrderItem(Order o) {
        OrderItem oi = new OrderItem();
        oi.setOrder(o);
        oi.setProductId(P1_ID);
        oi.setProductName(P1_NAME);
        oi.setUnit(P1_UNIT);
        oi.setBasePrice(new BigDecimal("3300000"));
        oi.setUnitPrice(new BigDecimal("3300000"));
        oi.setPriceMode("BASE");
        oi.setVatRate(10);
        oi.setVatMode("INCLUSIVE");
        oi.setVatAmount(new BigDecimal("300000"));
        oi.setQuantity(BigDecimal.ONE);
        oi.setSubtotal(new BigDecimal("3300000"));
        oi.setSaleType("RETAIL");
        return oi;
    }

    private void insertPt(Order o, BigDecimal amount, long createdAt, String note) {
        PaymentTransaction pt = new PaymentTransaction();
        pt.setOrder(o);
        pt.setAmount(amount);
        pt.setPaymentMethod("CASH");
        pt.setNote(note);
        pt.setCollectedBy("BonusTest");
        pt.setCreatedAt(createdAt);
        pt.setSource("MANUAL");
        em.persist(pt);
    }

    private Order findTestOrderOrThrow() {
        List<Order> list = em.createQuery(
                        "SELECT o FROM Order o WHERE o.orderCode = :code", Order.class)
                .setParameter("code", ORDER_CODE)
                .getResultList();
        if (list.isEmpty())
            throw new IllegalStateException("Chưa chạy /scenario-step1 — không tìm thấy order TEST-SPLIT-01");
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private void wipeAllOrdersAndPayments() {
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        try {
            tryDelete("DELETE FROM office_bonus_item");
            tryDelete("DELETE FROM office_bonus_result");
            tryDelete("DELETE FROM payment_transaction");
            tryDelete("DELETE FROM order_log");
            tryDelete("DELETE FROM order_item_ingredient");
            tryDelete("DELETE FROM order_item");
            tryDelete("DELETE FROM `order`");
        } finally {
            em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
        }
    }

    private void tryDelete(String sql) {
        try {
            em.createNativeQuery(sql).executeUpdate();
        } catch (Exception e) {
            log.debug("tryDelete skipped: {} — {}", sql, e.getMessage());
        }
    }

    private static long toEpochMs(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(VN).toInstant().toEpochMilli();
    }

    private Map<String, Object> monthExpect(String totalDt, String hold, String chiaHh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalDtExVat", totalDt);
        m.put("holdExVat", hold);
        m.put("chiaHhExVat", chiaHh);
        return m;
    }

    private Map<String, Object> summary(Order o, int step, Map<String, Object> expected) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("step", step);
        r.put("orderId", o.getId());
        r.put("orderCode", o.getOrderCode());
        r.put("finalAmount", o.getFinalAmount());
        r.put("vatAmount", o.getVatAmount());
        r.put("paidAmount", o.getPaidAmount());
        r.put("status", o.getStatus());
        r.put("paymentStatus", o.getPaymentStatus());
        r.put("expectedUi", expected);
        r.put("hint", "Click Tính bonus tháng tương ứng → ảnh 3 card gửi tôi verify");
        return r;
    }
}