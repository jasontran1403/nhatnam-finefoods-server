package com.nhatnam.server.config;

import com.nhatnam.server.common.CustomAccessDeniedHandler;
import com.nhatnam.server.common.CustomAuthenticationEntryPoint;
import com.nhatnam.server.enumtype.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static com.nhatnam.server.enumtype.Role.*;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final String[] WHITE_LIST_URL = {
            "/api/auth/**",
            "/api/seller/products",
            "/api/seller/products/**",
            "/ws/**",
            "/configuration/ui",
            "/configuration/security",
            "/webjars/**"
    };

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final LogoutHandler logoutHandler;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                .authorizeHttpRequests(req ->
                        req.requestMatchers(WHITE_LIST_URL).permitAll()
                                .requestMatchers("/api/user/**").hasRole(USER.name())
                                .requestMatchers("/api/pos/**").hasRole(POS.name())
                                .requestMatchers("/api/shipper/**").hasAnyRole(SHIPPER.name(), SUPERADMIN.name())
                                .requestMatchers("/api/seller/**").hasAnyRole(SELLER.name(), SUPER_SELLER.name(), SUPERADMIN.name(), ADMIN.name(), OWNER.name())
                                .requestMatchers("/api/warehouse/**").hasAnyRole(WAREHOUSE.name(), SUPER_WAREHOUSE.name(), ADMIN.name(), SUPERADMIN.name(), OWNER.name())
                                .requestMatchers("/api/accountant/**").hasAnyRole(SELLER.name(), SUPER_SELLER.name(), ACCOUNTANT.name(), SUPER_ACCOUNTANT.name(), SUPERADMIN.name(), ADMIN.name(), OWNER.name())
                                .requestMatchers("/api/operator/**").hasAnyRole(Role.OPERATOR.name(), ADMIN.name(), OWNER.name())
                                // Quản lý camera — chỉ OWNER/ADMIN
                                .requestMatchers("/api/camera/**").hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                // Danh mục nguyên liệu xưởng (factory_material) — CHỈ Owner/Admin được
                                // xem/tạo/sửa/bật-tắt (route thật: FactoryMaterialController, GET dùng
                                // chung cho xưởng xem danh mục để chọn — chỉ chặn ghi).
                                // Đặt TRƯỚC rule /api/factory/** rộng hơn để khớp đúng rule này trước.
                                .requestMatchers(HttpMethod.POST, "/api/owner/factory/materials").hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                .requestMatchers(HttpMethod.PUT, "/api/owner/factory/materials/**").hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                .requestMatchers(HttpMethod.PATCH, "/api/owner/factory/materials/**").hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                // Export/Import Excel máy móc & thành phẩm sản xuất — chỉ Owner/Admin.
                                // Đặt TRƯỚC rule /api/factory/** để khớp đúng rule này trước.
                                .requestMatchers(
                                        "/api/owner/factory/machines/export", "/api/owner/factory/machines/import",
                                        "/api/owner/factory/products/export", "/api/owner/factory/products/import")
                                .hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                // Endpoint cũ tạo NVL từ phía xưởng (/api/factory/materials, POST) — không
                                // còn cho phép xưởng gọi nữa, chỉ còn Owner/Admin (giữ endpoint để không
                                // phá vỡ tương thích ngược, nhưng FACTORY_WORKER/SUPER_FACTORY_WORKER/
                                // FACTORY_ACCOUNTANT đã bị loại khỏi danh sách được phép tạo).
                                .requestMatchers(HttpMethod.POST, "/api/factory/materials").hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                .requestMatchers("/api/factory/**").hasAnyRole(
                                        Role.FACTORY_WORKER.name(), Role.SUPER_FACTORY_WORKER.name(),
                                        Role.FACTORY_ACCOUNTANT.name(), Role.FACTORY_MANAGER.name(),
                                        Role.FACTORY_PRODUCTION_WORKER.name(), Role.FACTORY_STAFF.name(),
                                        OWNER.name(), ADMIN.name(), SUPERADMIN.name(),
                                        SUPER_ACCOUNTANT.name(), ACCOUNTANT.name())
                                .requestMatchers("/api/factory-accountant/**").hasAnyRole(Role.FACTORY_ACCOUNTANT.name(), OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                .requestMatchers("/api/expense-vouchers/**").hasAnyRole(SUPER_ACCOUNTANT.name(), SUPER_WAREHOUSE.name(), ADMIN.name(), OWNER.name(), ACCOUNTANT.name())


                                // ⚠️ Chỉ OWNER/ADMIN — xoá sạch dữ liệu module sản xuất (môi trường test).
                                // Đặt TRƯỚC rule owner/production rộng hơn (rule đó cho cả FACTORY_WORKER)
                                // để Spring Security khớp đúng rule cụ thể này trước.
                                .requestMatchers("/api/owner/production-reset/**").hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name())
                                // Công nợ NCC + phiếu chi NCC — Owner xem, Accountant/Super Accountant xem + tạo phiếu chi.
                                // Đặt TRƯỚC rule owner/production/** rộng hơn để khớp đúng rule cụ thể này trước.
                                .requestMatchers("/api/owner/production/vendor-debts/**", "/api/owner/production/vendor-expenses/**")
                                .hasAnyRole(OWNER.name(), ACCOUNTANT.name(), SUPER_ACCOUNTANT.name(), ADMIN.name(), SUPERADMIN.name())
                                // Quản lý nhà cung cấp (Owner/Admin xem — công nợ + lịch sử đặt hàng + phân tích giá).
                                // Đặt TRƯỚC rule owner/production/** rộng hơn (rule đó KHÔNG có ADMIN/ACCOUNTANT).
                                .requestMatchers("/api/owner/production/suppliers/**")
                                .hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name(), ACCOUNTANT.name(), SUPER_ACCOUNTANT.name())
                                // SELLER/SUPER_SELLER được CHỦ ĐỘNG lập kế hoạch sản xuất, nhưng
                                // KHÔNG được đụng máy móc, xưởng, tồn NVL hay reset dữ liệu.
                                // Vì vậy mở theo TỪNG endpoint thay vì thêm hai role này vào rule
                                // /api/owner/production/** bên dưới — rule đó bao cả POST tạo máy,
                                // sửa xưởng, và sẽ âm thầm trao quyền vượt xa nhu cầu.
                                // Đặt TRƯỚC rule rộng để Spring Security khớp đúng rule cụ thể này.
                                .requestMatchers(HttpMethod.GET,
                                        "/api/owner/production/dashboard",
                                        "/api/owner/production/plans",
                                        "/api/owner/production/plans/**",
                                        "/api/owner/production/work-orders/**",
                                        "/api/owner/production/factories")
                                .hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name(),
                                        SELLER.name(), SUPER_SELLER.name(),
                                        FACTORY_WORKER.name(), SUPER_FACTORY_WORKER.name(), FACTORY_STAFF.name())
                                .requestMatchers(HttpMethod.POST, "/api/owner/production/plans")
                                .hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name(),
                                        SELLER.name(), SUPER_SELLER.name())
                                .requestMatchers("/api/owner/production/**").hasAnyRole(OWNER.name(), FACTORY_WORKER.name(), SUPER_FACTORY_WORKER.name(), FACTORY_STAFF.name())
                                .requestMatchers("/api/upload/production/**").hasAnyRole(OWNER.name(), FACTORY_WORKER.name(), SUPER_FACTORY_WORKER.name(), SUPER_ACCOUNTANT.name(), FACTORY_STAFF.name())
                                .requestMatchers("/production-files/**").hasAnyRole(OWNER.name(), FACTORY_WORKER.name(), SUPER_FACTORY_WORKER.name(), FACTORY_STAFF.name())

                                // HR endpoints — chỉ OWNER mới duyệt/từ chối lương
                                .requestMatchers("/api/hr/salaries/*/approve").hasAnyRole(OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/hr/salaries/bulk-approve").hasAnyRole(OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/hr/salaries/*/reject").hasAnyRole(OWNER.name(), ADMIN.name())
                                // Tính lương theo tháng — chỉ OWNER mới duyệt/từ chối toàn bộ batch
                                .requestMatchers("/api/hr/payroll/batches/*/approve").hasAnyRole(OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/hr/payroll/batches/*/reject").hasAnyRole(OWNER.name(), ADMIN.name())
                                // Tạo/export/import/tải phiếu lương — HR + SUPER_ACCOUNTANT + OWNER + ADMIN
                                .requestMatchers("/api/hr/payroll/**").hasAnyRole(HR.name(), SUPER_ACCOUNTANT.name(), OWNER.name(), ADMIN.name())
                                // Import/Export lương — HR + SUPER_ACCOUNTANT + OWNER + ADMIN
                                .requestMatchers("/api/hr/salaries/export").hasAnyRole(HR.name(), SUPER_ACCOUNTANT.name(), OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/hr/salaries/import").hasAnyRole(HR.name(), SUPER_ACCOUNTANT.name(), OWNER.name(), ADMIN.name())
                                // Còn lại HR endpoints: HR + SUPER_ACCOUNTANT + OWNER + ADMIN
                                .requestMatchers("/api/hr/**").hasAnyRole(HR.name(), SUPER_ACCOUNTANT.name(), OWNER.name(), ADMIN.name())
                                // ── Cổng tài xế ─────────────────────────────────────────
                                .requestMatchers("/api/driver/**")
                                .hasAnyRole(DRIVER.name(), OWNER.name(), ADMIN.name(), SUPERADMIN.name())

                                // ── Quản lý tài xế + gắn tài khoản ──────────────────────
                                .requestMatchers("/api/admin/drivers/**")
                                .hasAnyRole(OWNER.name(), ADMIN.name(), SUPERADMIN.name(),
                                        WAREHOUSE.name(), SUPER_WAREHOUSE.name())

                                // ── Quản lý lương xưởng ─────────────────────────────────
                                // Mọi nhân viên xưởng đều gọi /periods và /my-payslip nên
                                // ở đây chỉ cần đăng nhập; quyền upload bảng chấm công và
                                // xem KPI được chặn bằng @PreAuthorize trong controller.
                                .requestMatchers("/api/factory-payroll/**").authenticated()

                                // ── Mật khẩu xem lương (passcode 6 số) ──────────────────
                                // MỌI nhân viên đều phải gọi được /status, /verify, PUT đổi
                                // passcode cho chính mình. Riêng /locked-users và /unlock
                                // chặn bằng @PreAuthorize trong PayrollPasscodeController.
                                .requestMatchers("/api/payroll-passcode/**").authenticated()

                                .requestMatchers("/api/notifications/**").authenticated()
                                // ── Quản lý khách hàng: ACCOUNTANT dùng chung màn hình
                                //    khách hàng với SUPER_ACCOUNTANT (xem/lọc + khóa/mở bán).
                                //    Đặt TRƯỚC rule /api/admin/** để nới quyền riêng cho tài
                                //    nguyên khách hàng mà không mở toàn bộ /api/admin/**.
                                .requestMatchers("/api/admin/customers/**")
                                .hasAnyRole(ADMIN.name(), OWNER.name(), SUPER_ACCOUNTANT.name(),
                                        HR.name(), ACCOUNTANT.name())
                                .requestMatchers("/api/admin/**").hasAnyRole(ADMIN.name(), OWNER.name(), SUPER_ACCOUNTANT.name(), HR.name())
                                .requestMatchers("/api/superadmin/**").hasRole(SUPERADMIN.name())
                                .anyRequest().authenticated()
                )

                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .logout(logout ->
                        logout.logoutUrl("/api/auth/logout")
                                .addLogoutHandler(logoutHandler)
                                .logoutSuccessHandler((request, response, authentication) ->
                                        SecurityContextHolder.clearContext())
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}