# Part 2: Auth Service - End Document

## Generation Complete Summary

### Module Structure
```
auth-service/
├── pom.xml
└── src/main/
    ├── java/com/tripplanner/auth/
    │   ├── AuthServiceApplication.java
    │   ├── config/
    │   │   ├── JwtConfig.java              # RS256 密钥配置、JwtEncoder/Decoder
    │   │   └── SecurityConfig.java         # SecurityFilterChain、CORS、Method Security
    │   ├── controller/
    │   │   ├── AuthController.java         # register, login, refresh, logout, me, password
    │   │   └── UserPreferenceController.java # preferences CRUD
    │   ├── dto/
    │   │   ├── request/
    │   │   │   ├── RegisterRequest.java
    │   │   │   ├── LoginRequest.java
    │   │   │   ├── RefreshTokenRequest.java
    │   │   │   ├── UpdateProfileRequest.java
    │   │   │   ├── UpdatePasswordRequest.java
    │   │   │   └── UpdatePreferenceRequest.java
    │   │   └── response/
    │   │       ├── LoginResponse.java
    │   │       ├── UserProfileResponse.java
    │   │       └── UserPreferenceResponse.java
    │   ├── entity/
    │   │   ├── User.java
    │   │   └── UserPreference.java
    │   ├── exception/
    │   │   ├── AuthException.java
    │   │   └── TokenException.java
    │   ├── repository/
    │   │   ├── UserRepository.java
    │   │   └── UserPreferenceRepository.java
    │   ├── security/
    │   │   ├── JwtAuthenticationFilter.java
    │   │   ├── JwtAuthenticationToken.java
    │   │   ├── UserPrincipal.java
    │   │   └── CustomPermissionEvaluator.java
    │   └── service/
    │       ├── UserService.java
    │       ├── JwtTokenService.java
    │       ├── RefreshTokenService.java
    │       ├── PermissionEvaluationService.java
    │       └── UserPreferenceService.java
    └── resources/
        └── application.yml
```

### Core Features Implemented

| Feature | Implementation |
|---------|---------------|
| **用户注册** | 邮箱唯一性校验、密码强度校验、BCrypt 加密、默认偏好初始化 |
| **用户登录** | 用户名/密码验证、状态检查、最后登录时间更新、生成 Access+Refresh Token |
| **JWT (RS256)** | 非对称加密、Access Token 15min、携带 userId/email/roles/jti |
| **Refresh Token** | Redis 存储、30天有效期、多设备支持、可撤销、rememberMe 延长 |
| **Token 刷新** | 校验 Refresh Token -> 生成新 Access Token |
| **登出** | 撤销当前设备 Refresh Token，可选撤销所有设备 |
| **密码修改** | 校验当前密码、强度校验、修改后撤销所有 Refresh Token |
| **用户档案** | 获取/更新基本信息 (name, avatar) |
| **用户偏好** | 全量/部分更新、JSON 存储、默认值初始化 |
| **权限模型** | RBAC (ROLE_USER/ROLE_ADMIN) + 资源级权限 (trip:read/write/delete/share) |
| **方法级鉴权** | `@PreAuthorize("hasPermission(#tripId, 'trip:write')")` |

### Security Highlights

1. **Stateless JWT**: 无 Session，横向扩展友好
2. **RS256 Asymmetric**: 私钥签发，公钥验证，网关可独立验签
3. **Refresh Token Rotation**: 存储于 Redis，支持撤销、多设备、过期自动清理
4. **Password Security**: BCrypt strength 10，强密码策略正则校验
5. **Rate Limiting Ready**: 预留配置，配合 Gateway + Redis 实现
6. **Audit Ready**: 关键操作记录 requestId，便于追踪

### API Endpoints

```
POST   /api/auth/register           # 注册
POST   /api/auth/login              # 登录
POST   /api/auth/refresh            # 刷新 Token
POST   /api/auth/logout             # 登出
GET    /api/auth/me                 # 当前用户信息
PUT    /api/auth/me                 # 更新个人信息
PUT    /api/auth/me/password        # 修改密码

GET    /api/auth/preferences        # 获取偏好
PUT    /api/auth/preferences        # 全量更新偏好
PATCH  /api/auth/preferences        # 部分更新偏好
```

### Configuration (application.yml)

Key properties:
- `spring.datasource.*` - MySQL 连接
- `spring.redis.*` - Redis 连接
- `jwt.private-key/public-key` - RS256 密钥 (Base64 encoded PEM)
- `jwt.access-token-expiry-minutes: 15`
- `jwt.refresh-token-expiry-days: 30`
- `mybatis-plus.configuration.map-underscore-to-camel-case: true`

### Database Tables Used
- `users` - 核心用户信息
- `user_preferences` - 偏好配置 (JSON 字段)

### Next Steps (Part 3: Trip Service)
1. 行程 CRUD (创建、查询、更新、删除、分页列表)
2. 版本管理 (创建版本、历史列表、回滚、分支)
3. 导入导出 (JSON/ICS/PDF/模板/分享链接)
4. 行程分享 (生成/撤销 share_token、公开访问)
5. 权限集成 (`@RequirePermission("trip:write")`)

### Build & Run
```bash
# 从父工程编译
cd D:\agent-trip-planner
mvn clean compile -pl common-module,auth-service -am

# 启动基础设施
docker-compose up -d mysql redis

# 设置环境变量 (JWT 密钥等)
cp .env.example .env
# 编辑 .env

# 启动 Auth Service
cd auth-service
mvn spring-boot:run
# 或
java -jar target/auth-service-1.0.0-SNAPSHOT.jar
```

### Verification
```bash
# 健康检查
curl http://localhost:8081/actuator/health

# 注册测试
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test@123","confirmPassword":"Test@123","name":"Test User"}'

# 登录测试
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test@123"}'
```