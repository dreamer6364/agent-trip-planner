# Part 2: Auth Service - Start Document

## Generation Scope
- Spring Security configuration (JWT filter chain, password encoder, permission evaluator)
- User registration / login / refresh token / logout APIs
- RBAC permission model (ROLE_USER, ROLE_ADMIN + resource-level permissions)
- User profile query/update, preference management
- Unit/integration tests

## Tech Stack
- Spring Boot 3.2.5
- Spring Security 6.2+
- Spring Data JPA / MyBatis-Plus
- JWT (RS256) via jjwt 0.12.5
- Redis for refresh token storage & rate limiting
- MySQL 8.0 (users, user_preferences tables)

## Expected Directory Structure
```
auth-service/
├── pom.xml
└── src/main/java/com/tripplanner/auth/
    ├── AuthServiceApplication.java
    ├── config/
    │   ├── SecurityConfig.java           # SecurityFilterChain, PasswordEncoder
    │   ├── JwtConfig.java                # JWT 公钥/私钥配置
    │   └── WebSecurityConfig.java        # 忽略路径、CORS
    ├── controller/
    │   ├── AuthController.java           # register, login, refresh, logout, me
    │   └── UserPreferenceController.java # 偏好 CRUD
    ├── service/
    │   ├── UserService.java              # 用户核心业务
    │   ├── JwtTokenService.java          # Token 生成/解析/校验
    │   ├── RefreshTokenService.java      # Redis 存储的刷新令牌管理
    │   └── PermissionEvaluationService.java # 资源级权限评估
    ├── repository/
    │   ├── UserRepository.java           # MyBatis-Plus Mapper
    │   └── UserPreferenceRepository.java
    ├── entity/
    │   ├── User.java
    │   └── UserPreference.java
    ├── dto/
    │   ├── request/
    │   │   ├── RegisterRequest.java
    │   │   ├── LoginRequest.java
    │   │   ├── RefreshTokenRequest.java
    │   │   └── UpdatePreferenceRequest.java
    │   └── response/
    │       ├── LoginResponse.java
    │       ├── UserProfileResponse.java
    │       └── UserPreferenceResponse.java
    ├── security/
    │   ├── JwtAuthenticationFilter.java  # JWT 解析 -> Authentication
    │   ├── JwtAuthenticationToken.java   # 自定义 Authentication 实现
    │   ├── UserPrincipal.java            # UserDetails 实现
    │   └── CustomPermissionEvaluator.java # @PreAuthorize("hasPermission(...)")
    └── exception/
        ├── AuthException.java
        └── TokenExpiredException.java
```

## Database Tables (from part1)
- `users` - id, email, password_hash, name, avatar_url, preferences, status, last_login_at
- `user_preferences` - user_id (PK), visit_duration_defaults, meal_durations, active_window, blocked_periods, intensity, dietary_tags, accessibility, home_location, work_location

## API Endpoints
```
POST   /api/auth/register           # 注册
POST   /api/auth/login              # 登录 -> access_token + refresh_token
POST   /api/auth/refresh            # 刷新 access_token
POST   /api/auth/logout             # 登出 (撤销 refresh_token)
GET    /api/auth/me                 # 当前用户信息
PUT    /api/auth/me                 # 更新基本信息
PUT    /api/auth/me/password        # 修改密码

GET    /api/auth/preferences        # 获取偏好
PUT    /api/auth/preferences        # 更新偏好 (全量覆盖)
PATCH  /api/auth/preferences        # 部分更新偏好
```

## Security Rules
- Access Token: 15min, RS256, 携带 userId, roles, permissions
- Refresh Token: 30d, 存储于 Redis (key: refresh_token:{userId}:{deviceId}), 可撤销
- 密码: BCrypt strength 10
- 限流: 登录/注册 10 QPS/IP, 刷新 20 QPS/user
- 资源权限: trip:read, trip:write, trip:delete, trip:share