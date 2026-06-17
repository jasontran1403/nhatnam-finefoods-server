package com.nhatnam.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class CartHoldService {

    private static final long HOLD_DURATION_MS = 10 * 60 * 1000L;

    private final SimpMessagingTemplate messagingTemplate;

    public CartHoldService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastIngredientStockUpdate(Long warehouseId, Long ingredientId) {
        broadcastStockUpdate(warehouseId, ingredientId);
    }

    public void broadcastIngredientStockUpdate(Long warehouseId, Long ingredientId, BigDecimal stockQuantity) {
        BigDecimal totalHeld = getTotalHeldQuantity(warehouseId, ingredientId);
        try {
            messagingTemplate.convertAndSend("/topic/stock/" + warehouseId,
                    Map.of(
                            "ingredientId", ingredientId,
                            "heldQty", totalHeld,
                            "stockQuantity", stockQuantity  // ← thêm
                    ));
        } catch (Exception e) {
            log.warn("[HOLD] broadcastStockUpdate failed: {}", e.getMessage());
        }
    }

    private final ConcurrentHashMap<Long, CartHold> holds = new ConcurrentHashMap<>();

    public static class CartHold {
        public final Long sellerId;
        public final Long warehouseId;
        public final Map<Long, BigDecimal> items = new ConcurrentHashMap<>();
        public final long createdAt = System.currentTimeMillis();

        public CartHold(Long sellerId, Long warehouseId) {
            this.sellerId    = sellerId;
            this.warehouseId = warehouseId;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > HOLD_DURATION_MS;
        }

        public long remainingMs() {
            return Math.max(0, createdAt + HOLD_DURATION_MS - System.currentTimeMillis());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public synchronized void updateHold(Long sellerId, Long warehouseId,
                                        Long ingredientId, BigDecimal qty) {
        CartHold hold = holds.computeIfAbsent(sellerId, k -> new CartHold(sellerId, warehouseId));

        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            hold.items.remove(ingredientId);
        } else {
            hold.items.put(ingredientId, qty);
        }

        broadcastStockUpdate(warehouseId, ingredientId);
    }

    /** Seller chủ động release (clear cart / order xong) — KHÔNG clear giỏ frontend. */
    public synchronized void releaseCart(Long sellerId) {
        releaseCartInternal(sellerId, false);
    }

    /** Hết 3 phút — release stock VÀ báo frontend clear giỏ. */
    private synchronized void releaseCartExpired(Long sellerId) {
        releaseCartInternal(sellerId, true);
    }

    private synchronized void releaseCartInternal(Long sellerId, boolean expired) {
        CartHold hold = holds.remove(sellerId);
        if (hold == null) return;

        Set<Long> ingredientIds = new HashSet<>(hold.items.keySet());
        hold.items.clear();

        for (Long ingId : ingredientIds) {
            broadcastStockUpdate(hold.warehouseId, ingId);
        }

        if (expired) {
            // Broadcast lên topic chung, payload chứa sellerId để client tự lọc
            broadcastCartEvent("CART_EXPIRED", sellerId);
        }

    }

    public BigDecimal getTotalHeldQuantity(Long warehouseId, Long ingredientId) {
        BigDecimal total = BigDecimal.ZERO;
        for (CartHold hold : holds.values()) {
            if (!hold.warehouseId.equals(warehouseId)) continue;
            if (hold.isExpired()) continue;
            total = total.add(hold.items.getOrDefault(ingredientId, BigDecimal.ZERO));
        }
        return total;
    }

    public long getRemainingMs(Long sellerId) {
        CartHold hold = holds.get(sellerId);
        return hold == null ? 0 : hold.remainingMs();
    }

    // ── Scheduled cleanup ─────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void cleanupExpiredHolds() {
        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, CartHold> entry : holds.entrySet()) {
            if (entry.getValue().isExpired()) toRemove.add(entry.getKey());
        }
        for (Long sellerId : toRemove) {
            releaseCartExpired(sellerId);
        }
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    /**
     * Stock update: /topic/stock/{warehouseId}
     * Payload: { ingredientId, heldQty }
     */
    private void broadcastStockUpdate(Long warehouseId, Long ingredientId) {
        BigDecimal totalHeld = getTotalHeldQuantity(warehouseId, ingredientId);
        try {
            messagingTemplate.convertAndSend("/topic/stock/" + warehouseId,
                    Map.of("ingredientId", ingredientId, "heldQty", totalHeld));
        } catch (Exception e) {
            log.warn("[HOLD] broadcastStockUpdate failed: {}", e.getMessage());
        }
    }

    /**
     * Cart event: /topic/cart-events
     * Payload: { event: "CART_EXPIRED", sellerId: 7 }
     *
     * Tất cả POS client đều subscribe topic này.
     * Client so sánh payload.sellerId với userId của mình,
     * nếu trùng thì clear giỏ hàng.
     */
    private void broadcastCartEvent(String event, Long sellerId) {
        try {
            messagingTemplate.convertAndSend("/topic/cart-events",
                    Map.of("event", event, "sellerId", sellerId));
        } catch (Exception e) {
            log.warn("[HOLD] broadcastCartEvent failed: {}", e.getMessage());
        }
    }
}