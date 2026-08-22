package com.nhatnam.server.restcontroller;

import com.nhatnam.server.dto.request.AuthLoginRequest;
import com.nhatnam.server.dto.response.ApiResponse;
import com.nhatnam.server.dto.response.AppVersionCheckResponse;
import com.nhatnam.server.dto.response.AuthResponse;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.service.AppVersionService;
import com.nhatnam.server.service.AuthService;
import com.nhatnam.server.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Log4j2
public class AuthenticationController {
    private final AuthService authService;
    /** Đọc claim selected_role từ token hiện tại — role đang chọn không lưu ở DB. */
    private final com.nhatnam.server.config.JwtService jwtService;
    private final FileStorageService fileStorageService;
    private final AppVersionService appVersionService;

    @GetMapping("/version")
    public ResponseEntity<ApiResponse<AppVersionCheckResponse>> getVersion() {

        AppVersionCheckResponse response =
                appVersionService.getCurrentVersion();

        return ResponseEntity.ok(
                ApiResponse.<AppVersionCheckResponse>builder()
                        .code(StatusCode.SUCCESS)
                        .data(response)
                        .message("Success")
                        .time(System.currentTimeMillis() / 1000)
                        .build()
        );
    }

//    @Value("${stripe.secret-key}")
//    private String stripeSecretKey;

//    @PostMapping("/create-payment-intent")
//    public ResponseEntity<ApiResponse<Object>> createPaymentIntent(
//            @RequestBody Map<String, Object> requestBody) {
//
//        try {
//            Stripe.apiKey = stripeSecretKey;
//
//            String title = (String) requestBody.get("title");
//            Number amountRaw = (Number) requestBody.get("amount");
//            Long productId = ((Number) requestBody.get("productId")).longValue();
//
//            if (title == null || amountRaw == null || productId == null) {
//                return ResponseEntity.badRequest().body(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu thông tin")
//                );
//            }
//
//            double amount = amountRaw.doubleValue();
//            long unitAmount = Math.round(amount * 100); // cent
//
//            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
//                    .setAmount(unitAmount)
//                    .setCurrency("usd")
//                    .setDescription(title)
//                    .putMetadata("productId", String.valueOf(productId))
//                    .build();
//
//            PaymentIntent paymentIntent = PaymentIntent.create(params);
//
//            Map<String, String> responseData = new HashMap<>();
//            responseData.put("clientSecret", paymentIntent.getClientSecret());
//            responseData.put("paymentIntentId", paymentIntent.getId()); // Trả về ID để có thể hủy sau
//
//            return ResponseEntity.ok(
//                    ApiResponse.success(StatusCode.SUCCESS, responseData, "Tạo PaymentIntent thành công")
//            );
//
//        } catch (StripeException e) {
//            log.error("Lỗi Stripe: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
//            );
//        } catch (Exception e) {
//            log.error("Lỗi hệ thống: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, e.getMessage())
//            );
//        }
//    }
//
//    @PostMapping("/cancel-payment-intent")
//    public ResponseEntity<ApiResponse<Object>> cancelPaymentIntent(
//            @RequestBody Map<String, String> requestBody) {
//
//        try {
//            Stripe.apiKey = stripeSecretKey;
//
//            String paymentIntentId = requestBody.get("paymentIntentId");
//
//            if (paymentIntentId == null || paymentIntentId.isEmpty()) {
//                return ResponseEntity.badRequest().body(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Thiếu paymentIntentId")
//                );
//            }
//
//            // Lấy PaymentIntent
//            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
//
//            // Kiểm tra trạng thái hiện tại
//            String currentStatus = paymentIntent.getStatus();
//
//            // Chỉ hủy nếu chưa thành công hoặc chưa bị hủy
//            if ("succeeded".equals(currentStatus)) {
//                return ResponseEntity.ok(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Không thể hủy thanh toán đã thành công")
//                );
//            }
//
//            if ("canceled".equals(currentStatus)) {
//                return ResponseEntity.ok(
//                        ApiResponse.error(StatusCode.BAD_REQUEST, "Thanh toán đã bị hủy trước đó")
//                );
//            }
//
//            // Hủy PaymentIntent
//            PaymentIntentCancelParams cancelParams = PaymentIntentCancelParams.builder()
//                    .build();
//
//            PaymentIntent canceledPaymentIntent = paymentIntent.cancel(cancelParams);
//
//            Map<String, Object> responseData = new HashMap<>();
//            responseData.put("paymentIntentId", canceledPaymentIntent.getId());
//            responseData.put("status", canceledPaymentIntent.getStatus());
//            responseData.put("cancellationReason", "customer_canceled");
//
//            return ResponseEntity.ok(
//                    ApiResponse.success(StatusCode.SUCCESS, responseData, "Hủy thanh toán thành công")
//            );
//
//        } catch (StripeException e) {
//            log.error("Lỗi Stripe khi hủy PaymentIntent: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Lỗi Stripe: " + e.getMessage())
//            );
//        } catch (Exception e) {
//            log.error("Lỗi hệ thống khi hủy PaymentIntent: {}", e.getMessage());
//            return ResponseEntity.ok(
//                    ApiResponse.error(StatusCode.INTERNAL_SERVER_ERROR, "Lỗi hệ thống: " + e.getMessage())
//            );
//        }
//    }

    /**
     * Đăng nhập
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody AuthLoginRequest request) {
        try {
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(
                    ApiResponse.success(StatusCode.SUCCESS, response, "Login successfully")
            );
        } catch (Exception e) {
            log.error("Login failed from username {}, message: {}", request.getUsername(), e.getMessage());
            return ResponseEntity.ok(
                    ApiResponse.error(StatusCode.WRONG_PASSWORD, e.getMessage())
            );
        }
    }

    /**
     * Switch role — dùng token hiện tại, đổi sang role khác trong cùng tài khoản.
     * Endpoint này KHÔNG bị bypass bởi JwtAuthenticationFilter.
     */
    @PostMapping("/switch-role")
    public ResponseEntity<ApiResponse<AuthResponse>> switchRole(
            @RequestBody java.util.Map<String, String> body,
            org.springframework.security.core.Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.UNAUTHORIZED, "Unauthorized"));

            String newRole = body.get("role");
            if (newRole == null || newRole.isBlank())
                return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.BAD_REQUEST, "Thiếu role"));

            com.nhatnam.server.entity.User user = (com.nhatnam.server.entity.User) authentication.getPrincipal();
            AuthResponse response = authService.switchRole(user.getUsername(), newRole);
            return ResponseEntity.ok(ApiResponse.success(com.nhatnam.server.enumtype.StatusCode.SUCCESS, response, "Đã chuyển role"));
        } catch (Exception e) {
            log.error("Switch role failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    /**
     * NẠP LẠI PHIÊN sau khi OWNER đổi/gán role — user chỉ cần F5, không phải
     * đăng xuất đăng nhập lại.
     *
     * <p>Giống {@code /switch-role}, endpoint này KHÔNG bị bypass bởi
     * {@code JwtAuthenticationFilter} (xem danh sách loại trừ trong filter), vì
     * cần {@code Authentication} để biết đang là ai.
     *
     * <p>{@code accessToken} trong response có thể {@code null} — nghĩa là token
     * hiện tại vẫn dùng tốt, frontend giữ nguyên. Chỉ khi role đang chọn bị thu
     * hồi mới có token mới.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthResponse>> me(
            jakarta.servlet.http.HttpServletRequest request,
            org.springframework.security.core.Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.UNAUTHORIZED, "Unauthorized"));

            // Role đang chọn nằm trong JWT, không nằm trong DB
            String selectedRole = null;
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try { selectedRole = jwtService.extractSelectedRole(authHeader.substring(7)); }
                catch (Exception ignored) {}
            }

            com.nhatnam.server.entity.User user = (com.nhatnam.server.entity.User) authentication.getPrincipal();
            AuthResponse response = authService.refreshSession(user.getUsername(), selectedRole);
            return ResponseEntity.ok(ApiResponse.success(com.nhatnam.server.enumtype.StatusCode.SUCCESS, response, "OK"));

        } catch (com.nhatnam.server.common.BusinessException e) {
            // Phiên thật sự không dùng được nữa (khoá tài khoản, mất hết role)
            // → 901 khiến frontend đá ra màn hình đăng nhập. Đúng ý.
            log.warn("Refresh session — phiên không còn hợp lệ: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.UNAUTHORIZED, e.getMessage()));

        } catch (Exception e) {
            // Lỗi ngoài dự kiến → TUYỆT ĐỐI không trả 901. Endpoint này chạy ngầm
            // mỗi lần mở app; một trục trặc DB thoáng qua mà đá cả công ty ra
            // đăng nhập lại thì tệ hơn nhiều so với việc dùng tạm role cũ.
            log.error("Refresh session lỗi: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.BAD_REQUEST, "Không nạp lại được phiên"));
        }
    }

    /**
     * Set role mặc định — lưu vào DB (user.role field).
     * Lần đăng nhập tiếp theo sẽ tự chọn role này nếu user không chỉ định.
     */
    @PutMapping("/default-role")
    public ResponseEntity<ApiResponse<Void>> setDefaultRole(
            @RequestBody java.util.Map<String, String> body,
            org.springframework.security.core.Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated())
                return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.UNAUTHORIZED, "Unauthorized"));

            String newDefault = body.get("role"); // null = bỏ mặc định
            com.nhatnam.server.entity.User user = (com.nhatnam.server.entity.User) authentication.getPrincipal();
            authService.setDefaultRole(user.getUsername(), newDefault);
            String msg = newDefault != null ? "Đã đặt " + newDefault + " làm role mặc định" : "Đã bỏ role mặc định";
            return ResponseEntity.ok(ApiResponse.success(com.nhatnam.server.enumtype.StatusCode.SUCCESS, null, msg));
        } catch (Exception e) {
            log.error("Set default role failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(com.nhatnam.server.enumtype.StatusCode.BAD_REQUEST, e.getMessage()));
        }
    }

    @GetMapping("/images/{type}/{filename}")
    public ResponseEntity<byte[]> serveImage(
            @PathVariable String type,
            @PathVariable String filename) {
        try {
            java.util.Set<String> ALLOWED_TYPES = java.util.Set.of(
                    "product","pos-product","category","variant","ingredient",
                    "seller-import","inventory-management","landingpage",
                    "expense-voucher","landingpage-events","order-receipt",
                    "income-voucher","production","certificate",
                    "customer-contract"
            );
            if (!ALLOWED_TYPES.contains(type)) {
                log.warn("⚠️ Invalid image type requested: {}", type);
                return ResponseEntity.badRequest().build();
            }

            String filePath = "/images/" + type + "/" + filename;
            byte[] imageBytes = fileStorageService.getFile(filePath);

            HttpHeaders headers = new HttpHeaders();
            String lower = filename.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                headers.setContentType(MediaType.IMAGE_JPEG);
            else if (lower.endsWith(".webp"))
                headers.setContentType(MediaType.parseMediaType("image/webp"));
            else if (lower.endsWith(".gif"))
                headers.setContentType(MediaType.IMAGE_GIF);
            else if (lower.endsWith(".pdf")) {
                headers.setContentType(MediaType.APPLICATION_PDF);
                // inline: trình duyệt mở luôn trong <iframe> / tab mới thay vì tải
                // về. Không có header này, xem hợp đồng PDF sẽ thành tải file.
                headers.setContentDisposition(
                        org.springframework.http.ContentDisposition.inline()
                                .filename(filename).build());
            } else
                headers.setContentType(MediaType.IMAGE_PNG);
            headers.setCacheControl("max-age=86400");

            return new ResponseEntity<>(imageBytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.debug("❌ Failed to serve image: {}/{}", type, filename, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.debug("❌ Unexpected error serving image: {}/{}", type, filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}