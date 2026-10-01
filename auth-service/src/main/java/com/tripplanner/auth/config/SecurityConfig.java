package com.tripplanner.auth.config;

import com.tripplanner.auth.security.CustomPermissionEvaluator;
import com.tripplanner.auth.security.JwtAuthenticationFilter;
import com.tripplanner.common.service.PermissionEvaluationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // 启用 @PreAuthorize
public class SecurityConfig {

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String apiDocsPath;

    @Value("${springdoc.swagger-ui.path:/swagger-ui.html}")
    private String swaggerUiPath;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public CustomPermissionEvaluator permissionEvaluator(PermissionEvaluationService permissionEvaluationService) {
        return new CustomPermissionEvaluator(permissionEvaluationService);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开端点
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").permitAll() // 允许匿名访问，由过滤器处理
                        // 跨服务公开查询：批量获取用户名（卡片作者展示）
                        .requestMatchers(HttpMethod.GET, "/api/users/names").permitAll()
                        // 文档端点
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Actuator 健康检查
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        // 其他需要认证
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"未授权访问\"}}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"权限不足\"}}");
                        })
                );

        return http.build();
    }
}