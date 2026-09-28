# Part 4: Plan Service - End Document

## Generation Complete Summary

### Module Structure
```
plan-service/
├── pom.xml
└── src/main/
    ├── java/com/tripplanner/plan/
    │   ├── PlanServiceApplication.java
    │   ├── config/
    │   │   ├── LlmConfig.java              # LLM Client (OpenAI 兼容)
    │   │   ├── MapApiConfig.java           # 高德/百度地图 API 配置
    │   │   └── KafkaConsumerConfig.java    # Kafka 消费者配置
    │   ├── controller/
    │   │   ├── PlanController.java         # 解析预览、任务进度、地理编码
    │   │   └── MapTestController.java      # 路线/距离矩阵测试端点
    │   ├── service/
    │   │   ├── InputParserService.java     # LLM 解析自由文本 → ParsedInput
    │   │   ├── GeocodeService.java         # 地理编码/逆地理编码 (双源+缓存)
    │   │   ├── RouteService.java           # 路线规划/距离矩阵 (四模式+缓存)
    │   │   ├── PlanTaskService.java        # 任务管理：进度、状态机、重试
    │   │   └── PlanEventConsumer.java      # 消费规划完成/失败事件
    │   ├── repository/
    │   │   ├── PlanTaskRepository.java
    │   │   └── GeocodeCacheRepository.java
    │   ├── entity/
    │   │   ├── PlanTask.java
    │   │   └── GeocodeCache.java
    │   ├── dto/
    │   │   ├── request/
    │   │   │   ├── ParsePreviewRequest.java
    │   │   │   └── GeocodeRequest.java
    │   │   └── response/
    │   │       ├── ParsePreviewResponse.java
    │   │       ├── PlanTaskProgressResponse.java
    │   │       ├── GeocodeResponse.java
    │   │       └── RouteResponse.java
    │   └── model/
    │       └── ParsedInput.java            # 解析结果核心模型
    └── resources/
        └── application.yml
```

### Core Features Implemented

| Feature | Implementation |
|---------|---------------|
| **输入解析** | LLM (OpenAI 兼容) + System Prompt + Few-shot + JSON Schema 约束输出 |
| **解析预览** | 同步调用 `/api/plan/parse-preview`，返回结构化 ParsedInput |
| **地理编码** | 高德(主) + 百度(备选) + Redis/MySQL 双层缓存 + 批量查询 |
| **逆地理编码** | 坐标 → 地址，同样双源缓存 |
| **路线规划** | 步行/公交/驾车/骑行 四模式，高德 API + Redis 7天缓存 |
| **距离矩阵** | 批量计算所有点对距离/耗时，支持启发式回退 |
| **任务管理** | 状态机、进度更新、重试(3次)、死信队列 |
| **事件消费** | Kafka `planning.events` 处理完成/失败回调 |

### API Endpoints

```
# Input Parsing
POST   /api/plan/parse-preview          # 解析预览 (同步)

# Task Management
GET    /api/plan/tasks/{taskId}         # 任务进度
GET    /api/plan/trips/{tripId}/tasks   # 行程的所有任务

# Geocoding
POST   /api/plan/geocode                # 地理编码 (单个/批量)
POST   /api/plan/batch-geocode          # 批量地理编码
POST   /api/plan/reverse-geocode        # 逆地理编码

# Map Testing
POST   /api/plan/map/route              # 路线测试
POST   /api/plan/map/distance-matrix    # 距离矩阵测试
```

### Data Models

#### ParsedInput (核心)
```java
{
  "places": [PlaceInfo],      // 地点列表：name, priority(must/rec/opt/excl), type, meal, duration
  "meals": [MealInfo],        // 餐饮：type(breakfast/lunch/dinner), preference, duration
  "timeRange": TimeRange,     // start, end, timezone
  "transportMode": String,    // walk/transit/drive/bike/mixed
  "preferences": UserPreferences  // visitDuration, mealDurations, activeWindow, blockedPeriods, intensity...
}
```

#### PlanTask 状态机
```
PENDING → RUNNING (PARSE_INPUT → GEOCODE → BUILD_MODEL → SOLVE → ROUTE → PERSIST)
                          ↓
                    COMPLETED | FAILED
                          ↓
              (retry ≤ 3) → DEAD_LETTER
```

### Key Implementation Details

#### LLM Prompt Engineering
- **System Prompt**: 详细的 JSON Schema 定义、优先级/类型识别规则
- **Few-shot**: 2 个完整示例 (杭州周末游、苏州自驾游)
- **Temperature**: 0.1 (确定性输出)
- **Response Format**: `json_object` 强制 JSON
- **Max Tokens**: 2000

#### Geocoding Strategy
1. Redis 查询 (30天 TTL)
2. MySQL 查询 (永久)
3. 高德 API 调用 (QPS 限制 50)
4. 失败时降级百度 API (QPS 限制 30)
5. 结果写入 Redis + MySQL

#### Route Caching
- Key: `route:originLat,originLng->destLat,destLng:mode`
- TTL: 7 天
- 模式: walk, transit, drive, bike
- 距离矩阵: 批量计算，直线距离回退

#### Task Retry Logic
- 失败时 `retryCount++`
- `retryCount < 3`: 重置为 PENDING，Worker 重新消费
- `retryCount >= 3`: 标记 DEAD_LETTER，人工介入

### Configuration (application.yml)

```yaml
server.port: 8083
spring.redis.database: 2
llm:
  base-url: ${LLM_BASE_URL}
  api-key: ${LLM_API_KEY}
  model: gpt-4o-mini
  temperature: 0.1
amap:
  api-key: ${AMAP_API_KEY}
  qps-limit: 50
baidu:
  api-key: ${BAIDU_API_KEY}
  qps-limit: 30
kafka:
  consumer.group.planning: planning-worker-group
```

### Parent POM Updated
- Added `<module>plan-service</module>`

### Integration with Other Services

| Service | Interaction |
|---------|-------------|
| **Trip Service** | 发送 `planning.jobs` / `planning.replan.jobs` 到 Kafka，Plan Service 消费进度查询 |
| **Planning Worker** | 核心消费者，处理 `planning.jobs`，发布 `planning.events` |
| **Geo/Route** | Plan Service 提供同步 API，Worker 可直接调用或通过内部事件 |
| **Notification** | 消费 `planning.events` 推送 WebSocket 进度 |

### Next Steps (Part 5: Planning Worker)
1. OR-Tools CP-SAT 求解器集成
2. 约束模型构建 (时间窗、时长、交通、优先级、无重叠)
3. 目标函数 (优先级覆盖、最小化在途、贴合偏好)
4. 启发式算法 (大规模 > 15 POI)
5. 增量求解 (反馈重规划)
6. 结果持久化 + 发布完成事件

### Build & Run
```bash
cd D:\agent-trip-planner
mvn clean compile -pl common-module,auth-service,trip-service,plan-service -am

# 启动基础设施
docker-compose up -d mysql redis kafka zookeeper

# 设置环境变量
cp .env.example .env
# 填入 LLM_API_KEY, AMAP_API_KEY, BAIDU_API_KEY

# 启动 Plan Service
cd plan-service
mvn spring-boot:run
```

### Verification
```bash
# 健康检查
curl http://localhost:8083/actuator/health

# 解析预览
curl -X POST http://localhost:8083/api/plan/parse-preview \
  -H "Content-Type: application/json" \
  -d '{
    "rawInput": "周末去杭州，想去西湖、灵隐寺、河坊街，早餐吃包子，午餐楼外楼",
    "timeStart": "2026-09-21T08:00:00",
    "timeEnd": "2026-09-22T20:00:00",
    "transportMode": "mixed"
  }'

# 地理编码
curl -X POST http://localhost:8083/api/plan/geocode \
  -H "Content-Type: application/json" \
  -d '{"query": "西湖", "city": "杭州", "source": "amap"}'
```