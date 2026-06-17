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

    final String path = request.getServletPath();

    if ((path.startsWith("/api/auth") && !path.equals("/api/auth/switch-role") && !path.equals("/api/auth/default-role"))
            || path.startsWith("/ws") || path.startsWith("/api/auth/landingpage") ||
            "OPTIONS".equalsIgnoreCase(request.getMethod())) {
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

      sendErrorResponse(response, StatusCode.JWT_INVALID_SIGNATURE, "Token không hợp lệ (không extract được username)");
      return;

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

  private void sendErrorResponse(HttpServletResponse response, int code, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
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