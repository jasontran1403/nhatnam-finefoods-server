package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.common.ApiResponse;
import com.nhatnam.server.dto.forecast.OrderForecastDtos.ForecastResponse;
import com.nhatnam.server.dto.forecast.OrderForecastDtos.MarkContactedRequest;
import com.nhatnam.server.entity.CustomerContactLog;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.service.OrderForecastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API MÀN HÌNH "DỰ BÁO ĐẶT HÀNG" (SELLER / SUPER_SELLER).
 *
 * <p>SELLER chỉ thấy khách được gán cho mình; SUPER_SELLER / OWNER / ADMIN thấy tất cả.
 * Việc lọc nằm trong service, controller không tự phân quyền theo tham số để client
 * không thể truyền {@code sellerId} của người khác.
 */
@RestController
@RequestMapping("/api/seller/order-forecast")
@RequiredArgsConstructor
@Log4j2
public class OrderForecastController {

    private final OrderForecastService forecastService;

    /**
     * @param onlyDue mặc định TRUE — màn hình chỉ quan tâm khách tới hạn cần gọi.
     *                Truyền {@code false} khi muốn xem toàn bộ khách kèm chu kỳ dự báo.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<ForecastResponse> forecast(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "true") boolean onlyDue,
            Authentication auth) {
        try {
            User seller = (User) auth.getPrincipal();
            return ApiResponse.ok(forecastService.forecast(seller, q, onlyDue));
        } catch (Exception e) {
            log.error("[Forecast] error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /** Đánh dấu ĐÃ GỌI cho hôm nay. Khách vẫn ở lại danh sách, chỉ đổi style. */
    @PostMapping("/{customerId}/contacted")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<Map<String, Object>> markContacted(
            @PathVariable Long customerId,
            @RequestBody(required = false) MarkContactedRequest body,
            Authentication auth) {
        try {
            User seller = (User) auth.getPrincipal();
            CustomerContactLog log = forecastService.markContacted(
                    seller, customerId, body != null ? body.getNote() : null);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerId",  log.getCustomerId());
            m.put("contactedAt", log.getContactedAt());
            m.put("contactedBy", log.getSellerName());
            m.put("contactDate", log.getContactDate());
            m.put("note",        log.getNote());
            return ApiResponse.ok("Đã ghi nhận liên hệ", m);
        } catch (Exception e) {
            log.error("[Forecast] markContacted error", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /** Bỏ đánh dấu (bấm nhầm). */
    @DeleteMapping("/{customerId}/contacted")
    @PreAuthorize("hasAnyRole('SELLER','SUPER_SELLER','OWNER','ADMIN','SUPERADMIN')")
    public ApiResponse<Void> unmarkContacted(@PathVariable Long customerId, Authentication auth) {
        try {
            User seller = (User) auth.getPrincipal();
            forecastService.unmarkContacted(seller, customerId);
            return ApiResponse.ok("Đã bỏ đánh dấu", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
