package com.nhatnam.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.enumtype.StatusCode;
import com.nhatnam.server.repository.TokenRepository;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.SecurityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

  private final JwtService jwtService;
  private final UserDetailsService userDetailsService;
  private final TokenRepository tokenRepository;
  private final ObjectMapper objectMapper;

  @Override
  protected void doFilterInternal(
          @NonNull HttpServletRequest request,
          @NonNull HttpServletResponse response,
          @NonNull FilterChain filterChain
  ) throws ServletException, IOException {

    // Nếu response đã bị commit (ví dụ do async dispatch, WebSocket handshake,
    // hoặc một filter trước đó đã ghi dữ liệu) → không làm gì thêm, tránh lỗi
    // "Unable to handle the Spring Security Exception because the response is already committed"
    if (response.isCommitted()) {
      log.warn("Response đã committed trước khi vào JwtAuthenticationFilter — bỏ qua filter cho path={}",
              request.getServletPath());
      return;
    }

    final String path = request.getServletPath();

    // /api/auth/** là public, TRỪ mấy endpoint cần biết "đang là ai":
    //   switch-role, default-role  → thao tác trên tài khoản đang đăng nhập
    //   me                         → nạp lại roles/kho sau khi OWNER đổi phân quyền
    // Bỏ sót endpoint nào ở đây là Authentication sẽ null và endpoint đó trả 401.
    //
    // /ws/** được bypass JWT filter vì handshake WebSocket (đặc biệt SockJS)
    // thường KHÔNG mang header Authorization. Việc xác thực WebSocket được
    // thực hiện trong WebSocketConfig qua ChannelInterceptor (đọc STOMP CONNECT header).
    if ((path.startsWith("/api/auth")
            && !path.equals("/api/auth/switch-role")
            && !path.equals("/api/auth/default-role")
            && !path.equals("/api/auth/me"))
            || path.startsWith("/ws")
            || path.startsWith("/api/auth/landingpage")
            || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }

    final String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      sendErrorResponse(response, StatusCode.UNAUTHORIZED, "Thiếu hoặc sai định dạng token");
      return;
    }

    final String jwt = authHeader.substring(7);

    try {
      final String userEmail = jwtService.extractUsername(jwt);

      // Nếu chưa có authentication trong context → xác thực JWT
      if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

        boolean isTokenValidInDb = tokenRepository.findByToken(jwt)
                .map(t -> !t.isExpired() && !t.isRevoked())
                .orElse(false);

        if (jwtService.isTokenValid(jwt, userDetails) && isTokenValidInDb) {

          // ── Multi-role: nếu JWT có selected_role, override authorities ──────
          Collection<? extends GrantedAuthority> authorities = userDetails.getAuthorities();
          String selectedRoleStr = jwtService.extractSelectedRole(jwt);
          if (selectedRoleStr != null && !selectedRoleStr.isBlank() && userDetails instanceof User u) {
            try {
              Role selectedRole = Role.valueOf(selectedRoleStr);
              // Chỉ áp dụng nếu role này thực sự được phép với user này
              if (u.getAllRoles().contains(selectedRole)) {
                authorities = selectedRole.getAuthorities();
              }
            } catch (IllegalArgumentException ignored) {}
          }

          UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                  userDetails, null, authorities
          );
          authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authToken);

          filterChain.doFilter(request, response);
          return;
        } else {
          sendErrorResponse(response, StatusCode.UNAUTHORIZED, "Token không hợp lệ hoặc đã bị thu hồi");
          return;
        }
      }

      // ── Các nhánh còn lại ────────────────────────────────────────────────
      // Nếu đã có authentication sẵn (ví dụ filter khác set trước, hoặc request
      // đã được xác thực ở tầng trước) → cho qua luôn, KHÔNG báo lỗi oan.
      if (SecurityContextHolder.getContext().getAuthentication() != null) {
        filterChain.doFilter(request, response);
        return;
      }

      // Chỉ báo lỗi khi thực sự không extract được username từ token
      if (userEmail == null) {
        sendErrorResponse(response, StatusCode.JWT_INVALID_SIGNATURE,
                "Token không hợp lệ (không extract được username)");
        return;
      }

      // Fallback an toàn — không nên rơi vào đây, nhưng vẫn cho qua
      // để tránh commit response oan uổng.
      filterChain.doFilter(request, response);

    } catch (ExpiredJwtException e) {
      log.warn("Token hết hạn: {}", e.getMessage());
      sendErrorResponse(response, StatusCode.JWT_EXPIRED, "Token đã hết hạn");
    } catch (SecurityException | MalformedJwtException | UnsupportedJwtException | IllegalArgumentException e) {
      log.warn("Token invalid (signature/format): {}", e.getMessage());
      sendErrorResponse(response, StatusCode.JWT_INVALID_SIGNATURE, "Token không hợp lệ (chữ ký hoặc định dạng sai)");
    } catch (Exception e) {
      log.error("Lỗi xử lý JWT không xác định: {}", e.getMessage(), e);
      sendErrorResponse(response, StatusCode.UNAUTHORIZED, "Lỗi xác thực token");
    }
  }

  /**
   * Ghi response lỗi xác thực.
   *
   * LƯU Ý QUAN TRỌNG:
   *  - Phải kiểm tra {@code response.isCommitted()} trước khi ghi, nếu không sẽ
   *    gây lỗi "Unable to handle the Spring Security Exception because the
   *    response is already committed" khi filter chain phía sau (AuthorizationFilter)
   *    cố gắng xử lý AccessDeniedException.
   *  - Trả về HTTP 401 (SC_UNAUTHORIZED) đúng chuẩn thay vì 200 như trước.
   *    Trường {@code code} trong body vẫn giữ nguyên để client phân biệt
   *    JWT_EXPIRED / JWT_INVALID_SIGNATURE / UNAUTHORIZED.
   */
  private void sendErrorResponse(HttpServletResponse response, int code, String message) throws IOException {
    if (response.isCommitted()) {
      log.warn("Response đã committed — không thể ghi lỗi. code={}, msg={}", code, message);
      return;
    }

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json;charset=UTF-8");

    Map<String, Object> body = new HashMap<>();
    body.put("code", code);
    body.put("success", StatusCode.isSuccess(code));
    body.put("message", message);
    body.put("timestamp", Instant.now().toString());

    objectMapper.writeValue(response.getWriter(), body);
    response.getWriter().flush();
  }
}