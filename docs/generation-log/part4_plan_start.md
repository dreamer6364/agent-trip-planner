# Part 4: Plan Service - Start Document

## Generation Scope
- Input parsing (LLM/NLP structured extraction from free text)
- Planning task management (progress tracking, status machine)
- Geocoding integration (Amap/Baidu with caching & fallback)
- Distance matrix computation
- Coordination with Planning Worker (async job submission, result handling)
- Task retry & dead letter handling

## Tech Stack
- Spring Boot 3.2.5
- Spring Kafka (consumer for planning events)
- LLM Integration (OpenAI compatible API)
- Amap/Baidu Map SDK (geocoding, routing)
- Redis (caching, distributed locks)
- MySQL (task persistence)
- Common Module (responses, exceptions, utils)

## Expected Directory Structure
```
plan-service/
├── pom.xml
└── src/main/java/com/tripplanner/plan/
    ├── PlanServiceApplication.java
    ├── config/
    │   ├── LlmConfig.java                  # LLM Client 配置
    │   ├── MapApiConfig.java               # 高德/百度 API 配置
    │   └── KafkaConsumerConfig.java        # Kafka Consumer 配置
    ├── controller/
    │   ├── PlanController.java             # 解析预览、任务进度查询
    │   └── GeocodeController.java          # 地理编码测试端点
    ├── service/
    │   ├── InputParserService.java         # LLM 解析自由文本
    │   ├── PlanTaskService.java            # 任务管理：进度、状态、重试
    │   ├── GeocodeService.java             # 地理编码/逆地理编码
    │   ├── RouteService.java               # 路线规划、距离矩阵
    │   └── PlanEventConsumer.java          # 消费规划完成事件
    ├── repository/
    │   ├── PlanTaskRepository.java
    │   └── GeocodeCacheRepository.java
    ├── entity/
    │   ├── PlanTask.java
    │   └── GeocodeCache.java
    ├── dto/
    │   ├── request/
    │   │   ├── ParsePreviewRequest.java
    │   │   └── GeocodeRequest.java
    │   └── response/
    │       ├── ParsePreviewResponse.java
    │       ├── PlanTaskProgressResponse.java
    │       ├── GeocodeResponse.java
    │       └── RouteResponse.java
    ├── model/
    │   ├── ParsedInput.java                # 解析结果结构
    │   ├── PlaceInfo.java
    │   ├── ActivityInfo.java
    │   └── TimeRange.java
    └── resources/
        └── application.yml
```

## Database Tables (from Part 1)
- `planning_tasks` - 规划任务表
- `pois` - POI 缓存表 (复用)
- 新增: `geocode_cache` - 地理编码缓存表 (或复用 pois)

## API Endpoints
```
# Input Parsing
POST   /api/plan/parse-preview          # 解析预览 (同步，不触发规划)
POST   /api/plan/parse-and-plan         # 解析并直接创建行程+规划

# Task Management
GET    /api/plan/tasks/{taskId}         # 查询任务进度
GET    /api/plan/tasks/{taskId}/stream  # SSE 进度流

# Geocoding
POST   /api/plan/geocode                # 地址/名称 -> 坐标
POST   /api/plan/reverse-geocode        # 坐标 -> 地址
POST   /api/plan/batch-geocode          # 批量地理编码

# Routing
POST   /api/plan/route                  # 单条路线
POST   /api/plan/distance-matrix        # 距离矩阵
POST   /api/plan/batch-route            # 批量路线

# Internal (Worker 回调)
POST   /api/plan/internal/task-complete # 规划完成回调
POST   /api/plan/internal/task-failed   # 规划失败回调
```

## Core Models

### ParsedInput (解析结果)
```java
{
  "places": [
    {"name": "西湖", "priority": "must", "type": "scenic", "preferredDurationMin": 180},
    {"name": "灵隐寺", "priority": "recommended", "type": "temple"},
    {"name": "楼外楼", "priority": "must", "type": "restaurant", "meal": "lunch"}
  ],
  "meals": [
    {"type": "breakfast", "preference": "包子"},
    {"type": "lunch", "preference": "杭帮菜"},
    {"type": "dinner", "preference": "河坊街小吃"}
  ],
  "timeRange": {"start": "2026-09-21T08:00:00", "end": "2026-09-22T20:00:00"},
  "transportMode": "mixed",
  "preferences": {...}
}
```

### PlanTask Status Machine
```
PENDING → RUNNING (PARSE_INPUT → GEOCODE → BUILD_MODEL → SOLVE → ROUTE → PERSIST)
                          ↓
                    COMPLETED | FAILED
                          ↓
                    (retry up to 3 times) → DEAD_LETTER
```

## LLM Prompt Engineering
- System prompt: 结构化输出指令、优先级识别、时间偏好提取
- Few-shot examples: 3-5 个示例
- Output format: JSON Schema 强制约束
- Temperature: 0.1 (确定性)
- Max tokens: 2000

## Geocoding Strategy
1. 本地缓存查询 (Redis → MySQL)
2. 高德 API (主)
3. 百度 API (备选)
4. 失败记录、人工处理队列

## Caching
- Geocode: Redis 30d, MySQL 永久
- Route: Redis 7d
- Distance Matrix: Redis 7d
- Cache key: hash(query + source + params)