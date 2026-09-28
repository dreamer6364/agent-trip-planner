# AGENTS.md - AI Agent 开发约束规范

本文件为 AI Agent 在本项目中工作时的约束文档，确保代码质量和一致性。

---

## 1. 项目概述

**项目名称**: Agent Trip Planner (TripForge)  
**项目类型**: AI 智能行程规划系统（微服务架构）  
**核心功能**: 用户输入自然语言行程需求，AI 自动生成结构化行程方案

---

## 2. 技术栈与版本要求

### 2.1 后端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 (LTS) | 必须使用 |
| Spring Boot | 3.1.5 | 框架基础 |
| Spring Cloud | 2022.0.5 | 微服务治理 |
| Maven | 3.8+ | 构建工具 |
| MySQL | 8.0 | 带 Spatial Extension |
| Redis | 7.x | 缓存/Session |
| Kafka | 3.6 | 消息队列 |
| Lombok | 1.18.30 | 代码简化（必须） |
| MapStruct | 1.5.5.Final | 对象映射（推荐） |
| Hutool | 5.8.22 | 工具库 |
| LangChain4j | - | LLM 集成 |

### 2.2 前端技术栈

- 纯 JavaScript (ES6+), 无框架
- Tailwind CSS
- Remix Icons
- **禁止**使用 React/Vue/Angular

---

## 3. 代码规范

### 3.1 Java 编码规范

#### 必须遵守

```java
// 1. 所有实体类使用 Lombok
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("table_name")
public class TripEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    
    @NotBlank(message = "标题不能为空")
    @Size(max = 200)
    private String title;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}

// 2. Service 层使用 @Transactional 注解
@Service
@RequiredArgsConstructor
public class TripService {
    private final TripRepository tripRepository;
    
    @Transactional(rollbackFor = Exception.class)
    public Trip createTrip(TripCreateRequest request, Long userId) {
        // 实现
    }
}

// 3. Controller 统一返回格式
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {
    @PostMapping
    public ResponseEntity<ApiResponse<Trip>> createTrip(
            @Valid @RequestBody TripCreateRequest request,
            @AuthenticationPrincipal Long userId) {
        // 实现
    }
}
```

#### 命名约定

| 类型 | 规范 | 示例 |
|------|------|------|
| 实体类 | `Xxx` | `Trip`, `Activity` |
| Repository | `XxxRepository` | `TripRepository` |
| Service | `XxxService` | `TripService` |
| Controller | `XxxController` | `TripController` |
| DTO Request | `XxxRequest` | `TripCreateRequest` |
| DTO Response | `XxxResponse` | `TripResponse` |
| 常量 | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` |
| 方法 | `camelCase` | `getTripById()` |
| 变量 | `camelCase` | `tripList` |

#### 包结构

```
com.tripplanner.{module}.controller    # 控制器
com.tripplanner.{module}.service       # 业务逻辑
com.tripplanner.{module}.service.impl  # Service 实现
com.tripplanner.{module}.repository    # 数据访问
com.tripplanner.{module}.entity        # 实体类
com.tripplanner.{module}.dto           # 数据传输对象
com.tripplanner.{module}.config        # 配置类
com.tripplanner.{module}.exception     # 异常定义
com.tripplanner.{module}.constant      # 常量
```

### 3.2 前端编码规范

```javascript
// 1. 使用 ES6+ 语法
const API_BASE = '/api';

// 2. 统一 API 调用封装
async function fetchApi(url, options = {}) {
    const token = localStorage.getItem('accessToken');
    const response = await fetch(`${API_BASE}${url}`, {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
            ...options.headers
        }
    });
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
}

// 3. DOM 操作使用语义化命名
const tripForm = document.getElementById('trip-form');
const itineraryContainer = document.getElementById('itinerary');
```

---

## 4. 架构约束

### 4.1 微服务职责边界

| 服务 | 职责 | 禁止事项 |
|------|------|----------|
| **Gateway** | 路由、鉴权、限流 | 禁止业务逻辑 |
| **Auth Service** | 用户认证、JWT | 禁止行程相关逻辑 |
| **Trip Service** | 行程 CRUD、版本管理 | 禁止 AI/LLM 调用 |
| **Plan Service** | 输入解析、任务创建 | 禁止直接写库（通过 Trip Service） |
| **Planning Worker** | 求解优化、路线计算 | 禁止暴露 HTTP 接口 |
| **Notification Service** | WebSocket 推送 | 禁止业务逻辑 |

### 4.2 服务间通信

```java
// ✅ 正确: 通过 Kafka 异步通信
kafkaTemplate.send("planning-jobs", planningJob);

// ✅ 正确: 通过 HTTP 调用其他服务
@FeignClient(name = "trip-service")
public interface TripClient {
    @GetMapping("/internal/trips/{id}")
    Trip getTripById(@PathVariable Long id);
}

// ❌ 禁止: 直接访问其他服务的数据库
// ❌ 禁止: 服务间循环依赖
```

### 4.3 数据库规范

```sql
-- 表名: 小写下划线，复数形式
CREATE TABLE trips (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL COMMENT '行程标题',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    status ENUM('DRAFT', 'PUBLISHED', 'ARCHIVED') DEFAULT 'DRAFT',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL COMMENT '软删除',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 必须使用软删除
-- 必须包含 created_at, updated_at
-- 必须有合理的索引
```

---

## 5. 安全规范

### 5.1 敏感信息处理

```java
// ✅ 正确: 使用环境变量
@Value("${amap.api.key}")
private String amapApiKey;

// ❌ 禁止: 硬编码密钥
private String apiKey = "abc123";  // 绝对禁止

// ❌ 禁止: 日志输出敏感信息
log.info("User password: {}", password);  // 禁止
```

### 5.2 认证授权

- JWT RS256 非对称加密
- Access Token: 30分钟有效期
- Refresh Token: 7天有效期
- 所有 API 需认证（除白名单路径）
- RBAC 权限控制

### 5.3 输入验证

```java
// 所有用户输入必须验证
@PostMapping
public ResponseEntity<?> createTrip(
        @Valid @RequestBody TripCreateRequest request) {
    // @Valid 触发 JSR 380 验证
}

// DTO 中定义验证规则
public class TripCreateRequest {
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最多200字")
    private String title;
    
    @NotNull(message = "开始时间不能为空")
    @Future(message = "开始时间必须是未来时间")
    private LocalDateTime startTime;
}
```

---

## 6. 测试规范

### 6.1 测试结构

```
src/test/java/
├── unit/           # 单元测试（无外部依赖）
├── integration/    # 集成测试（数据库、Redis）
└── e2e/           # 端到端测试
```

### 6.2 测试命名

```java
class TripServiceTest {
    @Test
    @DisplayName("创建行程 - 正常场景 - 应返回行程ID")
    void createTrip_normalCase_shouldReturnTripId() {
        // Given
        TripCreateRequest request = createValidRequest();
        Long userId = 1L;
        
        // When
        Trip result = tripService.createTrip(request, userId);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
    }
    
    @Test
    @DisplayName("创建行程 - 标题为空 - 应抛出异常")
    void createTrip_emptyTitle_shouldThrowException() {
        // 测试异常场景
    }
}
```

### 6.3 测试覆盖率要求

| 模块 | 最低覆盖率 |
|------|-----------|
| Service 层 | 80% |
| Repository 层 | 70% |
| Controller 层 | 60% |
| 工具类 | 90% |

---

## 7. Git 提交规范

### 7.1 提交信息格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

### 7.2 Type 类型

| Type | 说明 | 示例 |
|------|------|------|
| feat | 新功能 | feat(trip): 添加行程版本管理 |
| fix | 修复 Bug | fix(auth): 修复 JWT 过期验证问题 |
| docs | 文档更新 | docs: 更新 API 文档 |
| style | 代码格式 | style: 格式化代码 |
| refactor | 重构 | refactor(plan): 重构输入解析逻辑 |
| test | 测试 | test: 添加 TripService 单元测试 |
| chore | 构建/工具 | chore: 更新 Maven 依赖 |

### 7.3 示例

```
feat(trip): 添加行程分享功能

- 实现分享链接生成
- 添加分享权限控制
- 支持设置分享有效期

Closes #123
```

---

## 8. 文档规范

### 8.1 代码注释

```java
/**
 * 行程规划服务
 * 
 * 负责行程的创建、查询、更新、删除等核心业务逻辑。
 * 支持行程版本管理和增量更新。
 * 
 * @author TripForge Team
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
public class TripService {
    
    /**
     * 创建新行程
     * 
     * @param request 行程创建请求
     * @param userId 用户ID
     * @return 创建的行程信息
     * @throws BusinessException 当用户行程数超过限制时
     */
    @Transactional(rollbackFor = Exception.class)
    public Trip createTrip(TripCreateRequest request, Long userId) {
        // 实现
    }
}
```

### 8.2 API 文档

- 使用 SpringDoc OpenAPI 3.0
- 所有 Controller 添加 `@Tag` 和 `@Operation` 注解
- 示例：

```java
@Tag(name = "行程管理", description = "行程的CRUD操作")
@RestController
@RequestMapping("/api/trips")
public class TripController {
    
    @Operation(summary = "创建行程", description = "根据用户输入创建新行程")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "创建成功"),
        @ApiResponse(responseCode = "400", description = "参数错误")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<Trip>> createTrip(...) {
    }
}
```

### 8.3 版本文档（强制）

**每次功能变化都必须当场写入版本文档，不允许攒批、不允许事后补记。**

| 变更类型 | 写入文件 | 版本号变化 |
|----------|----------|-----------|
| 新功能 / 功能增强 / 重构 / 优化 | `docs/CHANGELOG.md` | 次版本 +1（如 1.12.0 → 1.13.0） |
| Bug 修复 / 部署修复 / 数据修复 | `docs/BUGFIX.md` | 修订号 +1（如 1.12.0 → 1.12.1） |
| 同一轮改动既有功能又有修复 | 两份文件都写，**版本号保持一致** | 按主要变更类型取号 |

规则：

1. 条目一律**追加在文件最上方**（紧随 `---` 之后），格式 `## [版本号] - YYYY-MM-DD`
2. 条目须包含：改动点、涉及文件、原因/设计要点、**验证方式与结果**（可附验证表格）
3. 版本号三段十进制，基线 1.11.0，只增不改历史条目
4. 涉及前端功能改动时，必须同步执行并在验证结果中登记：
   `npm run build` → `robocopy frontend-new\dist gateway\src\main\resources\static /MIR` → `mvn -o package -DskipTests` → `restart-all.ps1`
5. 完成变更后自查：`docs/CHANGELOG.md` / `docs/BUGFIX.md` 顶部是否已有本次版本号条目

---

## 9. 性能规范

### 9.1 数据库

- 避免 N+1 查询，使用 JOIN 或批量查询
- 大表必须有索引
- 分页查询使用游标分页（大数据量）

```java
// ✅ 正确: 使用 JOIN 查询
@Query("SELECT t FROM Trip t JOIN FETCH t.activities WHERE t.id = :id")
Optional<Trip> findByIdWithActivities(@Param("id") Long id);

// ❌ 禁止: N+1 查询
Trip trip = tripRepository.findById(id);
List<Activity> activities = activityRepository.findByTripId(trip.getId()); // N次查询
```

### 9.2 缓存使用

```java
// 热点数据使用 Redis 缓存
@Cacheable(value = "trip", key = "#tripId", unless = "#result == null")
public Trip getTripById(Long tripId) {
    return tripRepository.findById(tripId).orElse(null);
}

// 缓存失效
@CacheEvict(value = "trip", key = "#tripId")
public void deleteTrip(Long tripId) {
    tripRepository.deleteById(tripId);
}
```

### 9.3 异步处理

```java
// 耗时操作使用异步
@Async("taskExecutor")
public void sendNotification(Long userId, String message) {
    // 异步发送通知
}

// 或通过 Kafka 异步
kafkaTemplate.send("notifications", notificationEvent);
```

---

## 10. 部署规范

### 10.1 环境变量

所有配置通过环境变量注入，禁止在代码中硬编码：

```yaml
# application.yml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/trip_planner}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:}

redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:6379}

amap:
  api-key: ${AMAP_API_KEY}
```

### 10.2 Docker 规范

```dockerfile
# 使用多阶段构建
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 11. 禁止事项

### 11.1 代码层面

- ❌ 禁止使用 `System.out.println()`，必须使用 SLF4J
- ❌ 禁止在 Controller 写业务逻辑
- ❌ 禁止使用 `@SuppressWarnings` 忽略警告
- ❌ 禁止提交未处理的异常
- ❌ 禁止使用 `Thread.sleep()` 处理并发
- ❌ 禁止硬编码配置值

### 11.2 架构层面

- ❌ 禁止服务间直接访问数据库
- ❌ 禁止循环依赖
- ❌ 禁止在 Service 层处理 HTTP 请求
- ❌ 禁止在代码中存储密码/密钥

### 11.3 安全层面

- ❌ 禁止日志输出敏感信息（密码、Token、密钥）
- ❌ 禁止使用不安全的加密算法（MD5、SHA1）
- ❌ 禁止禁用 CSRF/XSS 防护
- ❌ 禁止使用 `*` 通配符查询

---

## 12. 代码审查清单

每次提交前，确保：

- [ ] 代码符合命名规范
- [ ] 添加了必要的注释
- [ ] 通过 Lint 检查
- [ ] 单元测试通过
- [ ] 无敏感信息泄露
- [ ] 异常处理完善
- [ ] 无循环依赖
- [ ] 数据库变更已添加索引

---

## 13. 常用命令

```bash
# 构建项目
mvn clean compile

# 运行测试
mvn test

# 打包
mvn clean package -DskipTests

# 运行单个测试类
mvn test -Dtest=TripServiceTest

# 代码格式化
mvn spotless:apply

# 启动服务（开发模式）
mvn spring-boot:run -pl trip-service
```

---

## 14. 参考文档

- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)
- [Spring Cloud 官方文档](https://spring.io/projects/spring-cloud)
- [MyBatis-Plus 官方文档](https://baomidou.com/)
- [LangChain4j 官方文档](https://docs.langchain4j.dev/)

---

**最后更新**: 2026-09-21  
**维护者**: TripForge Team
