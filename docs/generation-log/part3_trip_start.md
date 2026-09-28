# Part 3: Trip Service - Start Document

## Generation Scope
- Trip CRUD (create, read, update, delete, paginated list)
- Version management (create version, history list, rollback, fork)
- Import/Export (JSON, ICS, PDF, Template, Share Link)
- Trip sharing (generate/revoke share_token, public access)
- Permission integration (`@RequirePermission("trip:write")`)
- Audit logging for key operations

## Tech Stack
- Spring Boot 3.2.5
- Spring Security (method-level auth)
- MyBatis-Plus + MySQL 8.0 (with Spatial)
- Kafka Producer (for planning tasks)
- Redis (caching, rate limiting)
- Common Module (responses, exceptions, utils)

## Expected Directory Structure
```
trip-service/
├── pom.xml
└── src/main/java/com/tripplanner/trip/
    ├── TripServiceApplication.java
    ├── config/
    │   └── KafkaConfig.java              # Kafka Producer 配置
    ├── controller/
    │   ├── TripController.java           # CRUD, versions, export, share
    │   └── TripVersionController.java    # 版本历史、回滚、分支
    ├── service/
    │   ├── TripService.java              # 核心业务逻辑
    │   ├── TripVersionService.java       # 版本管理
    │   ├── TripExportService.java        # 导出服务
    │   └── TripShareService.java         # 分享服务
    ├── repository/
    │   ├── TripRepository.java
    │   ├── TripVersionRepository.java
    │   └── ActivityRepository.java
    ├── entity/
    │   ├── Trip.java
    │   ├── TripVersion.java
    │   └── Activity.java
    ├── dto/
    │   ├── request/
    │   │   ├── CreateTripRequest.java
    │   │   ├── UpdateTripRequest.java
    │   │   ├── CreateVersionRequest.java
    │   │   ├── RollbackVersionRequest.java
    │   │   ├── ExportTripRequest.java
    │   │   └── ShareTripRequest.java
    │   └── response/
    │       ├── TripResponse.java
    │       ├── TripVersionResponse.java
    │       ├── TripListResponse.java
    │       ├── ExportResponse.java
    │       └── ShareResponse.java
    ├── event/
    │   └── TripEventPublisher.java       # 发布行程事件到 Kafka
    └── resources/
        └── application.yml
```

## Database Tables (from Part 1)
- `trips` - 主表
- `trip_versions` - 版本表
- `activities` - 活动项表
- `planning_tasks` - 规划任务表
- `audit_logs` - 审计日志表

## API Endpoints
```
# Trip CRUD
POST   /api/trips                     # 创建行程 (含解析、触发规划)
GET    /api/trips                     # 分页列表 (筛选、搜索)
GET    /api/trips/{id}                # 详情 (含最新版本)
PUT    /api/trips/{id}                # 更新基本信息
DELETE /api/trips/{id}                # 删除/归档

# Version Management
GET    /api/trips/{id}/versions       # 版本历史列表
GET    /api/trips/{id}/versions/{v}   # 特定版本详情
POST   /api/trips/{id}/versions       # 基于反馈创建新版本
POST   /api/trips/{id}/versions/{v}/rollback  # 回滚到指定版本
POST   /api/trips/{id}/versions/{v}/fork      # 基于历史版本新建行程

# Planning
POST   /api/trips/{id}/plan           # 触发/重新规划
GET    /api/planning-tasks/{taskId}   # 查询规划进度

# Export
GET    /api/trips/{id}/export         # 导出 (format=json|ics|pdf|png|template)

# Share
POST   /api/trips/{id}/share          # 生成分享链接
DELETE /api/trips/{id}/share          # 撤销分享
GET    /shared/{token}                # 公开访问 (无需登录)

# Permissions
- 所有写操作需要 trip:write 权限
- 读操作需要 trip:read 权限
- 删除需要 trip:delete 权限
- 分享需要 trip:share 权限
```

## Key Business Logic

### Create Trip Flow
1. 接收自由文本 + 时间范围 + 交通方式 + 偏好
2. 调用 LLM/NLP 解析 → ParsedInput (结构化地点、活动、优先级)
3. 创建 Trip (status=draft) + TripVersion v1 (status=draft)
4. 发送 PlanningJob 到 Kafka
5. 返回 202 Accepted {taskId, wsUrl}
6. 前端 WebSocket 等待完成

### Version Management
- 每次规划/反馈/编辑创建新版本，version_num 递增
- parent_version_id 形成版本树
- 存储完整快照 (activities, routes, conflicts, stats)
- 支持回滚 (创建新版本复制旧版本数据)
- 支持分支 (fork 创建新行程)

### Export Formats
- **JSON**: 完整数据结构
- **ICS**: iCalendar 格式，可导入日历应用
- **PDF**: 图文混排行程单
- **PNG**: 地图+时间轴截图
- **Template**: 去除个人信息，可作为模板分享

### Sharing
- 生成唯一 share_token (URL-safe base64)
- 设置 trips.is_public=true
- 公开访问 /shared/{token} 无需登录
- 可设置过期时间、访问密码
- 记录查看次数