package com.tripplanner.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;

/**
 * 网关安全配置
 * - /api/** 和 /ws/** → JWT 验证
 * - 其他路径 → 完全开放 (SPA + 静态资源)
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${jwt.public-key:}")
    private String publicKeyPem;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                .authorizeExchange(exchanges -> exchanges
                        // 公开端点
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        // 头像文件公开读取（<img> 直连加载，不携带 Authorization）
                        .pathMatchers(HttpMethod.GET, "/api/auth/avatars/**").permitAll()
                        .pathMatchers("/api/trips/public", "/api/trips/public/**").permitAll()
                        .pathMatchers("/api/trips/shared/**").permitAll()
                        .pathMatchers("/api/trips/covers").permitAll()
                        .pathMatchers("/api/agent/**").permitAll()
                        .pathMatchers("/shared/**").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .pathMatchers("/actuator/**").permitAll()

                        // API 路由需要认证 (除上面已放行的)
                        .pathMatchers("/api/**").authenticated()

                        // WebSocket 需要认证
                        .pathMatchers("/ws/**").authenticated()

                        // 所有其他路径 (SPA 路由、静态资源) 完全开放
                        .anyExchange().permitAll()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
                        .authenticationEntryPoint((exchange, ex) -> {
                            var response = exchange.getResponse();
                            response.setStatusCode(HttpStatus.UNAUTHORIZED);
                            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            return response.writeWith(Mono.just(
                                    response.bufferFactory().wrap(
                                            "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"未授权访问\"}}".getBytes()
                                    )
                            ));
                        })
                )

                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        String publicKeyContent = resolvePublicKey();
        publicKeyContent = publicKeyContent
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        try {
            java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
            java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(
                    java.util.Base64.getDecoder().decode(publicKeyContent)
            );
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(spec);
            return NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();
        } catch (Exception e) {
            throw new IllegalStateException("JWT 公钥加载失败", e);
        }
    }

    private String resolvePublicKey() {
        if (publicKeyPem != null && !publicKeyPem.isBlank() && !publicKeyPem.startsWith("classpath:")) {
            return publicKeyPem;
        }
        try (var is = new org.springframework.core.io.ClassPathResource("keys/public_key.pem").getInputStream()) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法从 classpath 加载 JWT 公钥", e);
        }
    }
}
