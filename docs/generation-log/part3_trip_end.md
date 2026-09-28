# Part 3: Trip Service - End Document

## Generation Complete Summary

### Module Structure
```
trip-service/
├── pom.xml
└── src/main/
    ├── java/com/tripplanner/trip/
    │   ├── TripServiceApplication.java
    │   ├── config/
    │   │   └── KafkaConfig.java              # Kafka Producer 配置
    │   ├── controller/
    │   │   ├── TripController.java           # CRUD、规划触发、公开访问
    │   │   ├── TripVersionController.java    # 版本历史、回滚、分支
    │   │   ├── TripExportController.java     # 导出 (JSON/ICS/PDF/PNG/Template)
    │   │   └── TripShareController.java      # 分享链接生成/撤销
    │   ├── service/
    │   │   ├── TripService.java              # 核心业务：创建、查询、更新、删除、规划触发
    │   │   ├── TripVersionService.java       # 版本管理：历史、回滚、分支、结果更新
    │   │   ├── TripExportService.java        # 导出服务：JSON/ICS/PDF/PNG/Template
    │   │   └── TripShareService.java         # 分享服务：Token 生成、密码、过期
    │   ├── repository/
    │   │   ├── TripRepository.java
    │   │   ├── TripVersionRepository.java
    │   │   └── ActivityRepository.java
    │   ├── entity/
    │   │   ├── Trip.java
    │   │   ├── TripVersion.java
    │   │   └── Activity.java
    │   ├── dto/
    │   │   ├── request/
    │   │   │   ├── CreateTripRequest.java
    │   │   │   ├── UpdateTripRequest.java
    │   │   │   ├── CreateVersionRequest.java
    │   │   │   ├── RollbackVersionRequest.java
    │   │   │   ├── ExportTripRequest.java
    │   │   │   └── ShareTripRequest.java
    │   │   └── response/
    │   │       ├── TripResponse.java
    │   │       ├── TripVersionResponse.java
    │   │       ├── TripListResponse.java
    │   │       ├── ExportResponse.java
    │   │       └── ShareResponse.java
    │   └── event/
    │       └── TripEventPublisher.java       # 发送规划任务到 Kafka
    └── resources/
        └── application.yml
```

### Core Features Implemented

| Feature | Implementation |
|---------|---------------|
| **行程 CRUD** | 创建(触发规划)、分页查询(关键词/状态筛选)、详情(含最新版本)、更新、归档 |
| **异步规划触发** | 创建行程/重新规划时发送 Kafka 消息，返回 202 Accepted + taskId |
| **版本管理** | 历史列表、指定版本详情、基于反馈新建版本、回滚、分支 |
| **导出功能** | JSON(完整数据)、ICS(日历)、PDF(占位)、PNG(占位)、Template(去敏模板) |
| **分享功能** | URL-safe Token、可选密码/过期时间/导出权限、撤销分享、公开访问 |
| **权限控制** | `@RequirePermission("trip:read/write/delete/share")` 资源级鉴权 |
| **事件驱动** | TripEventPublisher 发送 PlanningJob 到 Kafka (plan/replan) |

### API Endpoints

```
# Trip CRUD
POST   /api/trips                     # 创建行程 (201 Created)
GET    /api/trips                     # 分页列表 (支持 keyword, status)
GET    /api/trips/{id}                # 详情 (含最新版本)
PUT    /api/trips/{id}                # 更新基本信息
DELETE /api/trips/{id}                # 归档

# Planning
POST   /api/trips/{id}/plan           # 触发规划 (202 Accepted)

# Version Management
GET    /api/trips/{id}/versions       # 版本历史列表
GET    /api/trips/{id}/versions/{v}   # 指定版本详情
POST   /api/trips/{id}/versions       # 基于反馈新建版本 (202 Accepted)
POST   /api/trips/{id}/versions/{v}/rollback  # 回滚
POST   /api/trips/{id}/versions/{v}/fork      # 分支新建行程 (201 Created)

# Export
GET    /api/trips/{id}/export?format=json|ics|pdf|png|template

# Share
POST   /api/trips/{id}/share          # 生成分享链接
DELETE /api/trips/{id}/share          # 撤销分享
GET    /api/trips/{id}/share          # 获取分享信息
GET    /shared/{token}                # 公开访问 (无需登录)

# Public
GET    /api/trips/public              # 公开行程列表 (无需登录)
```

### Database Integration

Uses tables from Part 1:
- `trips` - 主表，含 share_token、is_public、view_count
- `trip_versions` - 版本表，存储完整快照 (activities/routes/conflicts/stats/feedback/solver_meta)
- `activities` - 扁平化活动项，含空间索引
- `planning_tasks` - 规划任务追踪

### Key Business Flows

#### Create Trip Flow
```
POST /api/trips {title, rawInput, timeStart, timeEnd, transportMode, preferences}
    ↓
TripService.createTrip()
    ├─ INSERT trips (status=planning)
    ├─ INSERT trip_versions v1 (status=draft, 空活动)
    ├─ TripEventPublisher → Kafka:planning.jobs {tripId, versionId, input}
    └─ RETURN 201 Created {tripId, currentVersionId, status: "planning"}
    ↓
Frontend: WebSocket 连接 /ws/progress?taskId=xxx 等待完成
```

#### Feedback Replan Flow
```
POST /api/trips/{id}/versions {feedback, baseVersionId}
    ↓
TripVersionService.createVersionFromFeedback()
    ├─ SELECT baseVersion
    ├─ INSERT trip_versions vN (status=planning, 继承基础版本活动, 记录 feedback)
    ├─ UPDATE trips.current_version_id = vN.id, status=planning
    ├─ TripEventPublisher → Kafka:planning.replan.jobs {tripId, versionId, feedback, baseVersionId}
    └─ RETURN 202 Accepted {versionId, versionNum}
```

#### Version Rollback Flow
```
POST /api/trips/{id}/versions/{v}/rollback
    ↓
TripVersionService.rollbackToVersion()
    ├─ SELECT targetVersion (v)
    ├─ INSERT trip_versions vN+1 (复制 v 的 activities/routes/conflicts/stats)
    ├─ UPDATE trips.current_version_id = vN+1.id, status=completed
    └─ RETURN 200 OK {newVersion}
```

### Export Formats Detail

| Format | Content | Implementation |
|--------|---------|----------------|
| **json** | 完整行程数据结构 | Base64 Data URL 返回 |
| **ics** | iCalendar 格式 | ical4j 生成 VEVENT，支持导入 Google/Outlook/Apple Calendar |
| **pdf** | 图文行程单 | iText7 + Thymeleaf 模板 (占位) |
| **png** | 地图+时间轴截图 | Headless Chrome / MapLibre 静态渲染 (占位) |
| **template** | 去敏模板 | 移除用户信息，保留结构 |

### Share Security

- **Token**: 32 字符 URL-safe Base64 (UUID 编码)
- **Password**: 可选，存储于 preferences.shareConfig.password
- **Expiry**: 可选天数，存储 expiresAt，过期自动拒绝
- **Allow Export**: 控制公开访问者是否可导出
- **View Count**: 每次访问自增

### Configuration (application.yml)

```yaml
server.port: 8082
spring.datasource.*: MySQL 连接
spring.redis.database: 1 (区分 auth-service 的 0)
spring.kafka.producer.*: 生产者配置
jwt.public-key: 从环境变量注入，用于权限评估
share.base-url: 分享链接域名
```

### Parent POM Updated
- Added `<module>trip-service</module>`

### Next Steps (Part 4: Plan Service)
1. 输入解析 (LLM/NLP 结构化)
2. 规划任务管理 (进度查询、状态机)
3. 地理编码集成 (高德/百度)
4. 与 Planning Worker 协作

### Build & Run
```bash
cd D:\agent-trip-planner
mvn clean compile -pl common-module,auth-service,trip-service -am

# 启动基础设施
docker-compose up -d mysql redis kafka zookeeper

# 启动 Trip Service
cd trip-service
mvn spring-boot:run
```

### Verification
```bash
# 健康检查
curl http://localhost:8082/actuator/health

# 创建行程 (需先登录获取 token)
curl -X POST http://localhost:8082/api/trips \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "杭州周末游",
    "rawInput": "想去西湖、灵隐寺、河坊街",
    "timeStart": "2026-09-21T08:00:00",
    "timeEnd": "2026-09-22T20:00:00",
    "transportMode": "mixed"
  }'

# 查询列表
curl -H "Authorization: Bearer <access_token>" \
  "http://localhost:8082/api/trips?page=1&size=10"
```