# Part 7: API Gateway - Start Document

## Generation Scope
- Spring Cloud Gateway 路由聚合 (所有微服务统一入口)
- 统一鉴权 (JWT 验证、权限校验、匿名路径放行)
- 限流 (Redis Token Bucket、IP+用户双维度)
- 熔断降级 (Resilience4j、超时、重试、降级响应)
- 请求/响应日志、追踪 ID 透传 (X-Request-Id)
- 跨域、安全头 (CSP、HSTS、X-Frame-Options)
- 服务发现集成 (或静态路由配置)
- 灰度发布支持 (基于 Header/Cookie 权重路由)

## Tech Stack
- Spring Boot 3.2.5
- Spring Cloud Gateway 4.1+
- Spring Cloud Security (Resource Server JWT)
- Resilience4j (CircuitBreaker, RateLimiter, Retry)
- Redis (限流计数器、分布式锁)
- Spring Boot Actuator + Micrometer (监控指标)
- Common Module (统一响应、异常、工具类)

## Expected Directory Structure
```
gateway/
├── pom.xml
└── src/main/java/com/tripplanner/gateway/
    ├── GatewayApplication.java
    ├── config/
    │   ├── RouteConfig.java              # 路由定义 (Java DSL)
    │   ├── SecurityConfig.java           # Resource Server JWT 验证
    │   ├── RateLimiterConfig.java        # Redis Token Bucket 限流
    │   ├── CircuitBreakerConfig.java     # Resilience4j 熔断配置
    │   ├── CorsConfig.java               # 跨域配置
    │   └── GatewayFilterConfig.java      # 全局过滤器 (日志、追踪、头)
    ├── filter/
    │   ├── JwtAuthenticationFilter.java  # JWT 解析、用户信息注入 Header
    │   ├── RequestLoggingFilter.java     # 请求/响应日志
    │   ├── TraceIdFilter.java            # X-Request-Id 生成/透传
    │   └── RateLimitFilter.java          # 自定义限流 (可选)
    ├── handler/
    │   ├── AuthExceptionHandler.java     # 认证异常统一处理
    │   └── FallbackHandler.java          # 熔断降级响应
    ├── service/
    │   └── RouteService.java             # 动态路由管理 (可选)
    └── resources/
        └── application.yml
```

## Route Mapping

| Path Prefix | Target Service | Auth Required | Rate Limit |
|-------------|----------------|---------------|------------|
| `/api/auth/**` | auth-service:8081 | 登录/注册/刷新: 否 | 10 QPS/IP |
| `/api/trips/**` | trip-service:8082 | 是 (JWT) | 50 QPS/user |
| `/api/plan/**` | plan-service:8083 | 是 (JWT) | 30 QPS/user |
| `/api/notifications/**` | notification-service:8081 | 是 (JWT) | 100 QPS/user |
| `/api/internal/**` | 内部服务 | 否 (内网/特殊 Header) | - |
| `/ws/notify/**` | notification-service:8081 | WebSocket 握手验证 | - |
| `/actuator/**` | 各服务 | 否 (内网) | - |
| `/v3/api-docs/**` | 聚合文档 | 否 | - |
| `/shared/**` | trip-service:8082 | 否 (公开分享) | - |

## JWT Validation Flow
```
Request → Gateway
    → JwtAuthenticationFilter (提取 Authorization Header)
    → 验证签名 (公钥)、检查 exp、提取 claims
    → 设置 Header: X-User-Id, X-User-Roles, X-User-Email
    → 转发到下游服务 (下游直接读 Header，无需再次验签)
```

## Rate Limiting Strategy
- **Algorithm**: Token Bucket (Redis + Lua 脚本原子操作)
- **Dimensions**: 
  - IP 维度: 全局限流 (默认 100 QPS/IP)
  - User 维度: 登录用户精细限流 (如 50 QPS/user)
  - Endpoint 维度: 敏感接口更严格 (登录 10 QPS)
- **Key**: `ratelimit:{dimension}:{identifier}:{endpoint}`
- **Response**: 429 + Retry-After Header

## Circuit Breaker Config
```yaml
resilience4j.circuitbreaker:
  instances:
    trip-service:
      registerHealthIndicator: true
      slidingWindowSize: 10
      minimumNumberOfCalls: 5
      permittedNumberOfCallsInHalfOpenState: 3
      automaticTransitionFromOpenToHalfOpenEnabled: true
      waitDurationInOpenState: 30s
      failureRateThreshold: 50
    plan-service: ...
```

## Observability
- **Metrics**: `gateway_requests_total`, `gateway_request_duration_seconds`, `gateway_circuitbreaker_state`
- **Tracing**: 透传 `X-Request-Id`、`traceparent`、`baggage`
- **Logging**: 请求行、状态码、耗时、用户ID、路由匹配
- **Access Log**: 结构化 JSON 格式