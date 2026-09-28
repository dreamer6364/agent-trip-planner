# Part 6: Notification Service - End Document

## Generation Complete Summary

### Module Structure
```
notification-service/
├── pom.xml
└── src/main/
    ├── java/com/tripplanner/notification/
    │   ├── NotificationServiceApplication.java
    │   ├── config/
    │   │   ├── WebSocketConfig.java            # STOMP 配置、消息代理、端点
    │   │   └── JwtHandshakeInterceptor.java    # JWT 握手验证 + STOMP CONNECT 拦截
    │   ├── controller/
    │   │   ├── NotificationController.java     # REST: 历史、未读、标记已读
    │   │   └── WebSocketTestController.java    # 测试端点
    │   ├── service/
    │   │   ├── WebSocketSessionManager.java    # 连接管理、多设备、广播、踢出
    │   │   ├── NotificationService.java        # 通知创建、查询、推送、持久化
    │   │   ├── ProgressEventListener.java      # Redis Channel 进度监听
    │   │   └── PlanningEventConsumer.java      # Kafka planning.events 消费
    │   ├── repository/
    │   │   └── NotificationRepository.java
    │   ├── entity/
    │   │   └── Notification.java
    │   ├── dto/
    │   │   ├── NotificationResponse.java
    │   │   └── ProgressMessage.java
    │   └── resources/
    │       └── application.yml
```

### Core Features Implemented

| Feature | Implementation |
|---------|---------------|
| **WebSocket STOMP** | Spring WebSocket + SockJS 降级、`/ws/notify` 端点、简单消息代理 |
| **JWT 认证** | 握手拦截器验证 Query Param/Header Token、STOMP CONNECT 二次验证、Principal 注入 |
| **连接管理** | 多设备/多标签页支持、Redis 持久化会话映射、心跳刷新 TTL、踢出用户 |
| **消息路由** | `/user/queue/progress` (进度)、`/user/queue/notification` (通知)、`/topic/system` (广播) |
| **进度推送** | Redis Channel 监听 + `SimpMessagingTemplate.convertAndSendToUser` 实时推送 |
| **规划事件** | Kafka `planning.events` 消费：COMPLETED/FAILED/REPLAN_COMPLETED/REPLAN_FAILED |
| **通知持久化** | MySQL 存储、未读数统计、分页查询、批量标记已读、过期清理 |
| **离线处理** | 用户离线时写入 DB，上线时拉取未读推送 |

### WebSocket Message Flow

```
Planning Worker 
    → Kafka: planning.events / Redis Channel: planning:progress:channel
    → Notification Service (PlanningEventConsumer / ProgressEventListener)
    → WebSocketSessionManager (查找用户在线 sessions)
    → SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/progress", message)
    → 前端 STOMP Client 接收 MESSAGE
```

### STOMP Destinations

| Destination | Purpose | Routing |
|-------------|---------|---------|
| `/app/notify` | 客户端发送 | @MessageMapping |
| `/user/queue/progress` | 规划进度 | 用户私有队列 |
| `/user/queue/notification` | 系统通知 | 用户私有队列 |
| `/user/queue/kickout` | 踢出通知 | 用户私有队列 |
| `/topic/system` | 系统广播 | 所有在线用户 |

### Message Formats

**Progress (进度):**
```json
{
  "type": "PROGRESS|COMPLETED|FAILED|REPLAN_COMPLETED|REPLAN_FAILED",
  "taskId": "xxx",
  "tripId": "xxx",
  "versionId": "xxx",
  "percent": 45,
  "stage": "SOLVE",
  "message": "正在求解最优路径...",
  "resultVersionId": "ver-xxx",
  "resultUrl": "/api/trips/xxx/versions/xxx",
  "error": "错误信息",
  "retryable": true,
  "timestamp": "2026-09-16T10:30:00"
}
```

**Notification (持久化):**
```json
{
  "id": "notif-xxx",
  "type": "COMPLETED|FAILED|REPLAN_COMPLETED|REPLAN_FAILED|SYSTEM|SHARE",
  "title": "行程规划完成",
  "content": "您的行程已生成，包含 5 个地点",
  "data": {"taskId": "xxx", "tripId": "xxx", "resultUrl": "/api/trips/..."},
  "read": false,
  "priority": "normal",
  "createdAt": "2026-09-16T10:30:05"
}
```

### Authentication Flow

```
1. Client: new WebSocket('/ws/notify?token=JWT')
2. JwtHandshakeInterceptor.beforeHandshake()
   - 从 Query Param / Header 提取 token
   - 验证 JWT (解析 claims, 检查 exp)
   - attributes.put("userId", userId)
3. Client: STOMP CONNECT (可带 Authorization Header)
4. JwtHandshakeInterceptor.preSend(CONNECT)
   - 再次验证 token
   - accessor.setUser(new StompPrincipal(userId))
5. SessionManager.registerSession(userId, sessionId)
   - 本地缓存 + Redis 持久化
6. Client: SUBSCRIBE /user/queue/progress
7. Server: 进度/事件到达 → sendToUser(userId, "/queue/progress", msg)
```

### REST API Endpoints

```
GET    /api/notifications           # 分页通知历史
GET    /api/notifications/unread    # 未读通知列表
GET    /api/notifications/unread-count  # 未读数
PUT    /api/notifications/read      # 标记已读 (批量)
PUT    /api/notifications/read-all  # 全部标记已读
DELETE /api/notifications/cleanup   # 清理过期 (保留 100 条)
```

### Configuration (application.yml)

```yaml
server.port: 8081
spring.websocket.path: /ws/notify
spring.websocket.heartbeat: 30000
spring.redis.database: 4
spring.kafka.consumer.group-id: notification-service-group
jwt.public-key: ${JWT_PUBLIC_KEY}
```

### Parent POM Updated
- Added `<module>notification-service</module>`

### Integration Summary

```
┌─────────────────┐     Kafka/Redis      ┌──────────────────────┐
│ Planning Worker │ ──────────────────→  │ Notification Service │
│                 │  planning.events     │                      │
│                 │  progress channel    │  - ProgressEventListener (Redis)
└─────────────────┘                      │  - PlanningEventConsumer (Kafka)
                                         │  - WebSocketSessionManager
                                         └───────────┬──────────┘
                                                   │ STOMP / WebSocket
                                                   ▼
                                          ┌──────────────────────┐
                                          │      Frontend        │
                                          │  - /queue/progress   │
                                          │  - /queue/notification│
                                          │  - /topic/system     │
                                          └──────────────────────┘
```

### Next Steps (Part 7: API Gateway)
1. Spring Cloud Gateway 路由聚合
2. 统一鉴权 (JWT 验证、权限校验)
3. 限流 (Redis Token Bucket)
4. 熔断降级 (Resilience4j)
5. 请求/响应日志、追踪 ID 透传
6. 跨域、安全头
7. 服务发现 (或静态路由)

### Build & Run
```bash
cd D:\agent-trip-planner
mvn clean compile -pl common-module,auth-service,trip-service,plan-service,planning-worker,notification-service -am

# 启动基础设施
docker-compose up -d mysql redis kafka zookeeper victoria-metrics jaeger

# 启动 Notification Service
cd notification-service
mvn spring-boot:run
```

### Verification
```bash
# 健康检查
curl http://localhost:8081/actuator/health

# WebSocket 测试 (需先登录获取 token)
# 前端连接示例:
# const ws = new WebSocket('ws://localhost:8081/ws/notify?token=YOUR_JWT');
# const stomp = Stomp.over(ws);
# stomp.connect({}, () => {
#   stomp.subscribe('/user/queue/progress', (msg) => console.log(JSON.parse(msg.body)));
# });

# 测试推送 (需登录)
curl -X POST http://localhost:8081/api/ws-test/progress \
  -H "Authorization: Bearer <token>" \
  -d "percent=50&stage=SOLVE"
```