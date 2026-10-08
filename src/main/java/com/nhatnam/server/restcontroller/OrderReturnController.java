package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.order.OrderReturnRequest;
import com.nhatnam.server.dto.response.OrderResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.OrderReturnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Trả hàng do feedback xấu — OWNER/ADMIN/SUPER_SELLER/SUPER_ACCOUNTANT.
 */
@RestController
@RequestMapping("/api/seller/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderReturnController {

    private final OrderReturnService returnService;

    @PostMapping("/{orderId}/return")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','SUPERADMIN','SUPER_SELLER','SUPER_ACCOUNTANT')")
    public ApiResponse<OrderResponse> returnItems(
            @PathVariable Long orderId,
            @RequestBody OrderReturnRequest req,
            Authentication auth) {
        User user = (User) auth.getPrincipal();
        return ApiResponse.ok(returnService.processReturn(orderId, user.getId(), req));
    }
}
