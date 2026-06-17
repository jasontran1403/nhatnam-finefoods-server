package com.nhatnam.server.config;

import com.nhatnam.server.common.CustomAccessDeniedHandler;
import com.nhatnam.server.common.CustomAuthenticationEntryPoint;
import com.nhatnam.server.enumtype.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                                .requestMatchers("/api/factory/**").hasAnyRole(Role.FACTORY_WORKER.name(), OWNER.name(), ADMIN.name(), SUPERADMIN.name(), SUPER_ACCOUNTANT.name(), ACCOUNTANT.name())
                                .requestMatchers("/api/expense-vouchers/**").hasAnyRole(SUPER_ACCOUNTANT.name(), SUPER_WAREHOUSE.name(), ADMIN.name(), OWNER.name())


                                .requestMatchers("/api/owner/production/**").hasAnyRole(OWNER.name(), FACTORY_WORKER.name())
                                .requestMatchers("/api/upload/production/**").hasAnyRole(OWNER.name(), FACTORY_WORKER.name(), SUPER_ACCOUNTANT.name())
                                .requestMatchers("/production-files/**").hasAnyRole(OWNER.name(), FACTORY_WORKER.name())

                                // HR endpoints — chỉ OWNER mới duyệt/từ chối lương và xem payslip
                                .requestMatchers("/api/hr/salaries/*/approve").hasAnyRole(OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/hr/salaries/*/reject").hasAnyRole(OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/hr/payslip/**").hasAnyRole(OWNER.name(), ADMIN.name())
                                // Còn lại HR endpoints: HR + OWNER + ADMIN
                                .requestMatchers("/api/hr/**").hasAnyRole(HR.name(), OWNER.name(), ADMIN.name())
                                .requestMatchers("/api/notifications/**").authenticated()
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
