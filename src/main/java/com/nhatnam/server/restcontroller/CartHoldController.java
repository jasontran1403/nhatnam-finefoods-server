package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.CartHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/seller/cart-hold")
@RequiredArgsConstructor
public class CartHoldController {

    private final CartHoldService cartHoldService;

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateHold(
            @RequestBody UpdateHoldRequest req,
            Authentication auth) {
        Long sellerId = getSellerId(auth);
        if (sellerId == null)
            return ResponseEntity.ok(ApiResponse.error(401, "Unauthorized"));

        for (var item : req.items()) {
            cartHoldService.updateHold(sellerId, req.warehouseId(),
                    item.ingredientId(), item.qty());
        }

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("remainingMs", cartHoldService.getRemainingMs(sellerId)), "OK"));
    }

    @PostMapping("/release")
    public ResponseEntity<ApiResponse<Void>> releaseCart(Authentication auth) {
        Long sellerId = getSellerId(auth);
        if (sellerId != null) cartHoldService.releaseCart(sellerId);
        return ResponseEntity.ok(ApiResponse.success(null, "Released"));
    }

    @PostMapping("/held-quantities")
    public ResponseEntity<ApiResponse<Map<Long, BigDecimal>>> getHeldQuantities(
            @RequestBody HeldQtyRequest req) {
        Map<Long, BigDecimal> result = new java.util.HashMap<>();
        for (Long ingId : req.ingredientIds()) {
            BigDecimal held = cartHoldService.getTotalHeldQuantity(req.warehouseId(), ingId);
            if (held.compareTo(BigDecimal.ZERO) > 0) result.put(ingId, held);
        }
        return ResponseEntity.ok(ApiResponse.success(result, "OK"));
    }

    public record UpdateHoldRequest(Long warehouseId, List<HoldItem> items) {}
    public record HoldItem(Long ingredientId, BigDecimal qty) {}
    public record HeldQtyRequest(Long warehouseId, List<Long> ingredientIds) {}

    private Long getSellerId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User u)) return null;
        return u.getId();
    }
}