package com.nhatnam.server.restcontroller;

import com.nhatnam.server.common.BusinessException;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.LotPricingService;
import com.nhatnam.server.service.LotPricingService.SetLotPriceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Panel ĐIỀU CHỈNH LÔ của SUPER_ACCOUNTANT.
 *
 * <p>Liệt kê các lô mới do nhân viên kho tạo qua phiếu điều chỉnh tồn kho và
 * cho phép nhập giá vốn (theo đơn giá hoặc theo giá tổng của cả lô).
 */
@RestController
@RequestMapping("/api/accountant/lot-pricing")
@RequiredArgsConstructor
@Log4j2
@PreAuthorize("hasAnyRole('SUPER_ACCOUNTANT','ADMIN','OWNER')")
public class AccountantLotPricingController {

    private final LotPricingService lotPricingService;

    /** Các lô đang chờ nhập giá vốn */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pending() {
        try {
            return ResponseEntity.ok(ApiResponse.success(lotPricingService.listPending(), "OK"));
        } catch (Exception e) {
            log.error("lot-pricing pending error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    /** Lịch sử 100 lô đã định giá gần nhất */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history() {
        try {
            return ResponseEntity.ok(ApiResponse.success(lotPricingService.listPriced(), "OK"));
        } catch (Exception e) {
            log.error("lot-pricing history error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count() {
        return ResponseEntity.ok(ApiResponse.success(lotPricingService.countPending(), "OK"));
    }

    /** Nhập giá vốn cho 1 lô — body: { unitPrice } hoặc { totalPrice } */
    @PostMapping("/{id}/set-price")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setPrice(
            @PathVariable Long id,
            @RequestBody SetLotPriceRequest req,
            Authentication auth) {
        try {
            Long actorId = (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
            return ResponseEntity.ok(ApiResponse.success(
                    lotPricingService.setPrice(id, req, actorId), "Đã cập nhật giá vốn của lô"));
        } catch (BusinessException e) {
            return ResponseEntity.ok(ApiResponse.error(StatusCode.BAD_REQUEST, e.getMessage()));
        } catch (Exception e) {
            log.error("lot-pricing setPrice error", e);
            return ResponseEntity.ok(ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage()));
        }
    }
}
