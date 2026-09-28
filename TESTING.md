# TESTING.md - 测试规范与指南

本文档定义 Agent Trip Planner 项目的测试策略、规范和执行指南。

---

## 1. 测试金字塔

```
        ┌─────────┐
        │  E2E    │  少量端到端测试
        ├─────────┤
        │ 集成测试 │  服务间交互测试
        ├─────────┤
        │ 单元测试 │  大量单元测试（核心）
        └─────────┘
```

| 层级 | 目标覆盖率 | 执行频率 | 工具 |
|------|-----------|----------|------|
| 单元测试 | ≥80% | 每次提交 | JUnit 5 + Mockito |
| 集成测试 | ≥60% | 每次合并 | Testcontainers |
| E2E 测试 | 核心流程 | 发布前 | MockMvc / TestRestTemplate |

---

## 2. 目录结构

```
src/test/java/
├── unit/                       # 单元测试（无外部依赖）
│   └── com.tripplanner.{module}
│       ├── service/
│       ├── util/
│       └── ...
├── integration/                # 集成测试（数据库、Redis、Kafka）
│   └── com.tripplanner.{module}
│       ├── repository/
│       └── ...
└── e2e/                       # 端到端测试
    └── com.tripplanner.{module}
        └── controller/
```

---

## 3. 单元测试规范

### 3.1 命名规范

```java
// 格式: 方法名_场景_预期结果
class TripServiceTest {
    
    @Test
    @DisplayName("创建行程 - 正常场景 - 应返回行程ID")
    void createTrip_normalCase_shouldReturnTripId() { }
    
    @Test
    @DisplayName("创建行程 - 标题为空 - 应抛出验证异常")
    void createTrip_emptyTitle_shouldThrowValidationException() { }
    
    @Test
    @DisplayName("创建行程 - 用户不存在 - 应抛出404异常")
    void createTrip_userNotFound_shouldThrowNotFoundException() { }
}
```

### 3.2 AAA 模式

```java
@Test
void createTrip_normalCase_shouldReturnTripId() {
    // Arrange (准备)
    CreateTripRequest request = new CreateTripRequest();
    request.setTitle("杭州两日游");
    request.setRawInput("去杭州玩两天");
    String userId = "user-001";
    
    when(tripRepository.insert(any())).thenReturn(1);
    
    // Act (执行)
    TripResponse result = tripService.createTrip(userId, request);
    
    // Assert (断言)
    assertThat(result).isNotNull();
    assertThat(result.getId()).isNotBlank();
    assertThat(result.getTitle()).isEqualTo("杭州两日游");
    verify(tripRepository).insert(any(Trip.class));
}
```

### 3.3 测试模板

```java
@ExtendWith(MockitoExtension.class)
class ServiceTest {

    @Mock
    private Repository repository;

    @InjectMocks
    private Service service;

    @Test
    @DisplayName("方法名 - 场景 - 预期结果")
    void methodName_scenario_expectedResult() {
        // Arrange
        
        // Act
        
        // Assert
    }
}
```

---

## 4. 集成测试规范

### 4.1 Testcontainers 配置

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TripRepositoryIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("trip_planner_test")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("init-sql/01_schema.sql");

    @Container
    static RedisContainer<?> redis = new RedisContainer("redis:7")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private TripRepository tripRepository;

    @Test
    void insertAndFindById_shouldWork() {
        // Given
        Trip trip = createTestTrip();
        tripRepository.insert(trip);

        // When
        Trip found = tripRepository.selectById(trip.getId());

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo(trip.getTitle());
    }
}
```

### 4.2 Kafka 集成测试

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class KafkaIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void sendAndReceivePlanningJob_shouldWork() {
        // Given
        PlanningJob job = new PlanningJob();
        job.setTripId("trip-001");

        // When & Then
        CompletableFuture<ConsumerRecord<String, Object>> future = 
            KafkaTestUtils.getSingleMessage(kafkaTemplate, "planning.jobs");
        
        kafkaTemplate.send("planning.jobs", "trip-001", job);
        
        ConsumerRecord<String, Object> record = future.get(10, TimeUnit.SECONDS);
        assertThat(record.value()).isNotNull();
    }
}
```

---

## 5. Controller 测试规范

### 5.1 MockMvc 测试

```java
@WebMvcTest(TripController.class)
class TripControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TripService tripService;

    @Test
    @DisplayName("创建行程 - 应返回 201")
    void createTrip_shouldReturn201() throws Exception {
        // Given
        CreateTripRequest request = new CreateTripRequest();
        request.setTitle("杭州两日游");
        
        TripResponse response = TripResponse.builder()
                .id("trip-001")
                .title("杭州两日游")
                .build();
        
        when(tripService.createTrip(any(), any())).thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("X-User-Id", "user-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("trip-001"));
    }

    @Test
    @DisplayName("获取行程 - 不存在 - 应返回 404")
    void getTrip_notFound_shouldReturn404() throws Exception {
        // Given
        when(tripService.getTrip(any(), eq("nonexistent")))
                .thenThrow(BizException.notFound("行程", "nonexistent"));

        // When & Then
        mockMvc.perform(get("/api/trips/nonexistent")
                .header("X-User-Id", "user-001"))
                .andExpect(status().isNotFound());
    }
}
```

---

## 6. 各模块测试重点

### 6.1 common-module

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `GeoUtils` | 单元测试 | 距离计算精度、边界值 |
| `JsonUtils` | 单元测试 | 序列化/反序列化、空值处理 |
| `ApiResponse` | 单元测试 | 静态工厂方法、泛型类型 |
| `BizException` | 单元测试 | 异常码、HTTP 状态码 |
| `GatewayHeaderAuthFilter` | 单元测试 | Header 解析、空值处理 |

### 6.2 auth-service

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `UserService` | 单元测试 | 注册、登录、密码验证 |
| `JwtTokenService` | 单元测试 | Token 生成、解析、验证 |
| `RefreshTokenService` | 集成测试 | Redis 存储、Token 刷新 |
| `AuthController` | 集成测试 | 完整认证流程 |

### 6.3 gateway

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `JwtAuthenticationFilter` | 单元测试 | JWT 解析、Header 注入 |
| `RouteConfig` | 集成测试 | 路由匹配、转发正确性 |
| `RateLimiterConfig` | 集成测试 | 限流触发、KeyResolver |

### 6.4 trip-service

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `TripService` | 单元测试 | CRUD、权限检查、版本管理 |
| `TripVersionService` | 单元测试 | 版本创建、回滚、分支 |
| `TripRepository` | 集成测试 | SQL 正确性、索引命中 |
| `TripController` | 集成测试 | 完整 API 流程 |

### 6.5 plan-service

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `InputParserService` | 单元测试 | LLM 响应解析、降级逻辑 |
| `TripPlanningAgent` | 单元测试 | 输入解析、季节过滤 |
| `GeocodeService` | 集成测试 | 高德 API 调用、缓存 |
| `RouteService` | 集成测试 | 路线规划、多交通方式 |

### 6.6 planning-worker

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `HeuristicSolver` | 单元测试 | 贪心构造、局部搜索、退火 |
| `PlanningOrchestrator` | 单元测试 | 求解器选择、降级 |
| `PlanningJobConsumer` | 集成测试 | Kafka 消费、任务处理 |
| `ResultPersistService` | 集成测试 | 结果持久化、版本更新 |

### 6.7 notification-service

| 组件 | 测试类型 | 重点 |
|------|----------|------|
| `WebSocketSessionManager` | 单元测试 | 会话注册/注销、消息路由 |
| `NotificationService` | 单元测试 | 通知创建、未读统计 |
| `PlanningEventConsumer` | 集成测试 | 事件消费、推送 |

---

## 7. 测试数据管理

### 7.1 测试数据工厂

```java
public final class TestDataFactory {
    
    private TestDataFactory() {}
    
    public static Trip createTrip(String userId, String title) {
        Trip trip = new Trip();
        trip.setId(UUID.randomUUID().toString().replace("-", ""));
        trip.setUserId(userId);
        trip.setTitle(title);
        trip.setRawInput("测试行程");
        trip.setStatus("draft");
        trip.setViewCount(0);
        return trip;
    }
    
    public static User createUser(String email) {
        User user = new User();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail(email);
        user.setName("Test User");
        user.setStatus("active");
        return user;
    }
}
```

### 7.2 测试配置

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/trip_planner_test
    username: test
    password: test
  
  redis:
    host: localhost
    port: 6379
  
  kafka:
    bootstrap-servers: localhost:9092

llm:
  api-key: test-key
  model: test-model

amap:
  api-key: test-key
```

---

## 8. 测试执行

### 8.1 Maven 命令

```bash
# 运行所有测试
mvn test

# 运行单元测试（跳过集成测试）
mvn test -Dtest="*Test" -DfailIfNoTests=false

# 运行单个测试类
mvn test -Dtest=TripServiceTest

# 运行单个测试方法
mvn test -Dtest=TripServiceTest#createTrip_normalCase

# 运行集成测试
mvn verify -Pintegration-test

# 生成覆盖率报告
mvn jacoco:report
```

### 8.2 IDE 执行

- **IntelliJ IDEA**: 右键测试类 → Run 'XxxTest'
- **VS Code**: 安装 Java Test Runner 插件

---

## 9. 测试覆盖率

### 9.1 覆盖率要求

| 模块 | 最低覆盖率 | 目标覆盖率 |
|------|-----------|-----------|
| common-module | 90% | 95% |
| auth-service | 80% | 85% |
| gateway | 70% | 80% |
| trip-service | 80% | 85% |
| plan-service | 70% | 80% |
| planning-worker | 80% | 85% |
| notification-service | 70% | 80% |

### 9.2 覆盖率检查

```xml
<!-- pom.xml 配置 -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <rules>
            <rule>
                <element>BUNDLE</element>
                <limits>
                    <limit>
                        <counter>LINE</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.80</minimum>
                    </limit>
                </limits>
            </rule>
        </rules>
    </configuration>
</plugin>
```

---

## 10. 常见测试场景

### 10.1 行程规划流程

```java
@Test
@DisplayName("完整行程规划流程 - 应成功生成行程")
void fullPlanningFlow_shouldGenerateItinerary() {
    // 1. 创建行程
    // 2. 触发规划
    // 3. 等待异步完成
    // 4. 验证结果
    // 5. 验证版本创建
}
```

### 10.2 认证授权流程

```java
@Test
@DisplayName("登录获取Token - 刷新Token - 访问受保护资源")
void authFlow_shouldWorkEndToEnd() {
    // 1. 注册用户
    // 2. 登录获取 Token
    // 3. 使用 Token 访问 API
    // 4. 刷新 Token
    // 5. 使用新 Token 访问
}
```

### 10.3 异常处理流程

```java
@Test
@DisplayName("LLM 服务不可用 - 应回退到关键词解析")
void llmUnavailable_shouldFallbackToKeywordParsing() {
    // 1. Mock LLM 返回错误
    // 2. 调用解析服务
    // 3. 验证回退逻辑
    // 4. 验证关键词提取结果
}
```

---

## 11. CI/CD 集成

### 11.1 GitHub Actions

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: trip_planner_test
        ports:
          - 3306:3306
      
      redis:
        image: redis:7
        ports:
          - 6379:6379
      
      kafka:
        image: confluentinc/cp-kafka:7.4.0
        ports:
          - 9092:9092
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Run tests
        run: mvn test
      
      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

---

## 12. 测试检查清单

每次提交前，确保：

- [ ] 新增代码有对应单元测试
- [ ] 所有现有测试通过
- [ ] 测试覆盖率满足最低要求
- [ ] 测试命名符合规范
- [ ] 测试数据不依赖外部环境
- [ ] 集成测试使用 Testcontainers
- [ ] 无硬编码的测试数据
- [ ] 测试独立，不依赖执行顺序

---

**最后更新**: 2026-09-21
