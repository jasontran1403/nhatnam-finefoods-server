package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.VoucherRedemptionService;
import com.nhatnam.server.service.VoucherRedemptionService.RedemptionPreview;
import com.nhatnam.server.service.VoucherRedemptionService.RedemptionResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * API THANH TOÁN ĐƠN HÀNG BẰNG VOUCHER.
 *
 * <p>Mở cho SELLER, SUPER_SELLER, ACCOUNTANT, SUPER_ACCOUNTANT và OWNER/ADMIN — đây là
 * thao tác tại quầy hoặc lúc đối soát, cả hai nhóm đều cần làm được.
 */
@RestController
@RequestMapping("/api/orders/{orderId}/voucher-payment")
@RequiredArgsConstructor
@Log4j2
public class VoucherRedemptionController {

    private final VoucherRedemptionService redemptionService;

    private static final String ROLES =
            "hasAnyRole('SELLER','SUPER_SELLER','ACCOUNTANT','SUPER_ACCOUNTANT','OWNER','ADMIN','SUPERADMIN')";

    /**
     * KIỂM TRA VOUCHER — không thay đổi gì.
     *
     * <p>Gọi ngay sau khi nhân viên nhập mã hoặc quét QR, để hiện trước số tiền trừ được.
     * Trả về {@code applicable = false} kèm lý do thay vì lỗi HTTP: mã sai hoặc voucher
     * hết hạn là tình huống bình thường ở quầy, không phải sự cố hệ thống.
     */
    @GetMapping("/preview")
    @PreAuthorize(ROLES)
    public ApiResponse<RedemptionPreview> preview(@PathVariable Long orderId,
                                                  @RequestParam String code) {
        try {
            return ApiResponse.ok(redemptionService.preview(code, orderId));
        } catch (Exception e) {
            log.warn("[VoucherPay] preview lỗi đơn #{}: {}", orderId, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * ÁP DỤNG VOUCHER vào đơn.
     *
     * <p>Ghi nhận một khoản thu {@code paymentMethod = VOUCHER}. Đơn có thể tiếp tục thu
     * bằng tiền mặt / chuyển khoản cho phần còn lại — mỗi lần thu là một dòng riêng.
     */
    @PostMapping
    @PreAuthorize(ROLES)
    public ApiResponse<RedemptionResult> redeem(@PathVariable Long orderId,
                                                @RequestBody RedeemRequest body,
                                                Authentication auth) {
        try {
            User actor = auth != null ? (User) auth.getPrincipal() : null;
            return ApiResponse.ok("Đã thanh toán bằng voucher",
                    redemptionService.redeem(body.getCode(), orderId, body.getAmount(), actor));
        } catch (Exception e) {
            log.warn("[VoucherPay] redeem lỗi đơn #{}: {}", orderId, e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    /** Các voucher đã trừ vào đơn này — hiển thị ở màn hình chi tiết đơn hàng. */
    @GetMapping("/usages")
    @PreAuthorize(ROLES)
    public ApiResponse<List<Map<String, Object>>> usages(@PathVariable Long orderId) {
        try {
            return ApiResponse.ok(redemptionService.usagesOfOrder(orderId));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @Data
    public static class RedeemRequest {
        /** Mã voucher — nhập tay hoặc lấy từ QR. */
        private String code;
        /**
         * Số tiền muốn dùng. Bỏ trống = dùng tối đa có thể.
         * Nhập tay khi khách muốn giữ lại số dư cho lần mua sau.
         */
        private BigDecimal amount;
    }
}
