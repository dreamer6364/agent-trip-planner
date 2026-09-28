# Part 6: Notification Service - Start Document

## Generation Scope
- WebSocket 连接管理 (Socket.io / Spring WebSocket + STOMP)
- 实时进度推送 (订阅 Redis Channel + Kafka 事件)
- 认证集成 (JWT 握手验证、Token 刷新)
- 多设备/多标签页支持
- 连接心跳、重连、离线消息缓存
- 通知类型：规划进度、完成、失败、重规划、系统通知
- REST 端点：获取通知历史、未读数、标记已读

## Tech Stack
- Spring Boot 3.2.5
- Spring WebSocket + STOMP (或 Socket.io Java)
- Spring Security (JWT 握手拦截)
- Redis (会话存储、消息广播、离线队列)
- Kafka Consumer (planning.events)
- MySQL (通知持久化)
- Common Module

## Expected Directory Structure
```
notification-service/
├── pom.xml
└── src/main/java/com/tripplanner/notification/
    ├── NotificationServiceApplication.java
    ├── config/
    │   ├── WebSocketConfig.java            # STOMP 端点、拦截器、心跳
    │   ├── StompPrincipal.java             # Principal 适配
    │   └── JwtHandshakeInterceptor.java    # JWT 握手验证
    ├── controller/
    │   ├── NotificationController.java     # REST: 历史、未读、已读
    │   └── WebSocketTestController.java    # 测试端点
    ├── service/
    │   ├── WebSocketSessionManager.java    # 连接管理、多设备、广播
    │   ├── NotificationService.java        # 通知创建、查询、标记
    │   ├── ProgressEventListener.java      # 监听 Redis Channel 进度
    │   └── PlanningEventConsumer.java      # 消费 planning.events
    ├── repository/
    │   └── NotificationRepository.java
    ├── entity/
    │   └── Notification.java
    ├── dto/
    │   ├── NotificationResponse.java
    │   └── ProgressMessage.java
    └── resources/
        └── application.yml
```

## WebSocket 设计

### 连接端点
- `/ws/notify` - STOMP over WebSocket
- `/ws/notify/sockjs` - SockJS 降级

### 订阅主题
- 用户私有: `/user/queue/progress` (进度推送)
- 用户私有: `/user/queue/notification` (通知推送)
- 系统广播: `/topic/system` (维护公告等)

### 消息格式
```json
// 进度消息
{
  "type": "PROGRESS",
  "taskId": "xxx",
  "percent": 45,
  "stage": "SOLVE",
  "message": "正在求解最优路径...",
  "timestamp": "2026-09-16T10:30:00"
}

// 完成消息
{
  "type": "COMPLETED",
  "taskId": "xxx",
  "versionId": "ver-xxx",
  "resultUrl": "/api/trips/trip-xxx/versions/latest",
  "timestamp": "2026-09-16T10:30:05"
}

// 失败消息
{
  "type": "FAILED",
  "taskId": "xxx",
  "error": "求解器未找到可行解",
  "retryable": true,
  "timestamp": "2026-09-16T10:30:05"
}
```

### 认证流程
1. 前端连接: `new WebSocket('/ws/notify?token=xxx')` 或 STOMP CONNECT 带 Header
2. `JwtHandshakeInterceptor` 验证 JWT，提取 userId 存入 Principal
3. 连接建立后，SessionManager 注册 sessionId -> userId 映射 (Redis)
4. 用户订阅 `/user/queue/progress` 自动路由到个人队列

## 进度推送流程
```
Planning Worker (Kafka: planning.events / Redis Channel)
    → ProgressEventListener (监听)
    → WebSocketSessionManager (查找用户在线 sessions)
    → SimpMessagingTemplate.convertAndSendToUser(userId, "/queue/progress", message)
    → 前端接收 STOMP MESSAGE
```

## 离线消息处理
- 用户离线时，进度/通知写入 MySQL `notifications` 表
- 用户上线时，拉取未读消息推送
- Redis 缓存未读数，实时更新

## Database Tables
- `notifications` - 通知记录 (userId, type, title, content, data JSON, read, createdAt)

## Configuration
```yaml
server.port: 8081
spring.websocket:
  path: /ws/notify
  allowed-origins: "*"
  heartbeat: 30000
```