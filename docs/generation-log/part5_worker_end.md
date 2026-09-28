# Part 5: Planning Worker - End Document

## Generation Complete Summary

### Module Structure
```
planning-worker/
├── pom.xml
└── src/main/
    ├── java/com/tripplanner/worker/
    │   ├── PlanningWorkerApplication.java
    │   ├── config/
    │   │   ├── SolverConfig.java           # CP-SAT 参数 (时间、工作线程、Gap)
    │   │   ├── OrToolsConfig.java          # OR-Tools 本地库加载自检
    │   │   └── KafkaConsumerConfig.java    # 高并发消费者 (concurrency=4)
    │   ├── consumer/
    │   │   ├── PlanningJobConsumer.java    # 消费 planning.jobs (新建规划)
    │   │   └── ReplanJobConsumer.java      # 消费 planning.replan.jobs (反馈重规划)
    │   ├── solver/
    │   │   ├── CpSatSolver.java            # 精确求解器 (≤15 POI)
    │   │   ├── HeuristicSolver.java        # 启发式求解器 (>15 POI)
    │   │   ├── IncrementalReplanner.java   # 增量重规划
    │   │   └── model/
    │   │       ├── PlanningProblem.java    # 问题定义
    │   │       ├── ActivityVar.java        # 活动变量
    │   │       └── TravelMatrix.java       # 距离/耗时矩阵
    │   ├── service/
    │   │   ├── PlanningOrchestrator.java   # 求解器选择编排
    │   │   ├── GeocodeClient.java          # 调用 Plan Service 地理编码/路线
    │   │   ├── ProgressPublisher.java      # Redis/Kafka 进度发布
    │   │   └── ResultPersistService.java   # 调用 TripService 持久化
    │   └── dto/
    │       └── SolverResult.java
    └── resources/
        └── application.yml
```

### Core Features Implemented

| Feature | Implementation |
|---------|---------------|
| **CP-SAT 精确求解** | OR-Tools Java 9.8, 变量/约束/目标函数完整建模 |
| **启发式求解** | 贪心构造 + 局部搜索(2-opt/relocate/insert) + 模拟退火 |
| **增量重规划** | 受影响活动识别 → 固定未变部分 → 子问题求解 → 结果合并 |
| **约束建模** | 时间窗、时长、交通、优先级、无重叠、避开时段 |
| **目标函数** | 优先级覆盖 - α×在途时间 - β×偏好时间偏差 - γ×碎片化 |
| **Kafka 消费** | planning.jobs / planning.replan.jobs, 手动 ACK, 并发度 4 |
| **进度发布** | Redis 存储 + Channel 实时推送 + Kafka 事件发布 |
| **结果持久化** | 调用 TripService 内部 API 保存版本、活动、路线、统计 |

### Solver Comparison

| Aspect | CP-SAT (Exact) | Heuristic |
|--------|----------------|-----------|
| **适用规模** | ≤15 activities | >15 activities |
| **最优性** | 保证全局最优/可行 | 近似最优 |
| **求解时间** | 1-30s | 0.5-5s |
| **约束处理** | 硬约束严格满足 | 启发式满足，可能轻微违约 |
| **增量支持** | 原生支持 (固定变量) | 自定义实现 |

### Constraint Model Details

```java
// Variables per activity
start[a] ∈ [0, horizon]
end[a] = start[a] + dur[a]
interval[a] = [start[a], end[a])
presence[a] ∈ {0,1}  // optional only

// Constraints
1. minDur ≤ dur[a] ≤ maxDur
2. earliest ≤ start[a] < end[a] ≤ latest
3. presence[must] = 1
4. start[b] ≥ end[a] + travel(a,b,mode)  (sequence)
5. NoOverlap(non-transit intervals)
6. Blocked periods: end ≤ blockStart OR start ≥ blockEnd

// Objective: Maximize
Σ priorityWeight * presence
- α * Σ travelTime * presence[a] * presence[b]
- β * Σ |start - preferredStart| * presence
```

### Kafka Topics & Events

| Topic | Partitions | Consumer Group | Purpose |
|-------|------------|----------------|---------|
| `planning.jobs` | 12 | planning-worker-group | 新建行程规划 |
| `planning.replan.jobs` | 6 | planning-worker-group | 反馈重规划 |
| `planning.events` | 6 | notification-service-group | 完成/失败事件 |

### Progress Stages

| Stage | Percent | Description |
|-------|---------|-------------|
| PARSE_INPUT | 5% | 输入解析 (Plan Service 已完成) |
| GEOCODE | 15% | 批量地理编码 |
| BUILD_MATRIX | 30% | 距离矩阵计算 |
| BUILD_MODEL | 45% | CP-SAT 模型构建 |
| SOLVE | 55-70% | 求解器运行 |
| ROUTE | 80% | 详细路线生成 |
| PERSIST | 90% | 结果保存 |
| COMPLETED | 100% | 发布完成事件 |

### Configuration (application.yml)

```yaml
server.port: 8084
spring.kafka.listener.concurrency: 4
solver:
  max-time-seconds: 30
  num-workers: 4
  heuristic-threshold: 15
plan-service.url: http://localhost:8083
trip-service.url: http://localhost:8082
```

### Parent POM Updated
- Added `<module>planning-worker</module>`

### Integration Flow

```
Trip Service (POST /api/trips) 
    → Kafka: planning.jobs
    → Planning Worker 消费
    → Plan Service (地理编码/距离矩阵)
    → OR-Tools CP-SAT / Heuristic 求解
    → Plan Service (路线详情)
    → TripService (持久化版本)
    → Kafka: planning.events (PLANNING_COMPLETED)
    → Notification Service (WebSocket 推送)
    → Frontend 接收结果
```

### Replanning Flow

```
Frontend (用户反馈)
    → Trip Service (POST /api/trips/{id}/versions)
    → Kafka: planning.replan.jobs
    → Planning Worker 消费
    → 增量重规划
    → 固定未变活动，仅优化受影响部分
    → 合并结果
    → 持久化新版本
    → 发布 REPLAN_COMPLETED 事件
```

### Next Steps (Part 6: Notification Service)
1. WebSocket 连接管理 (Socket.io / Spring WebSocket)
2. 进度实时推送 (Redis Channel 订阅)
3. 完成/失败通知
4. 认证集成 (JWT 验证)
5. 多设备支持

### Build & Run
```bash
cd D:\agent-trip-planner
mvn clean compile -pl common-module,auth-service,trip-service,plan-service,planning-worker -am

# 启动基础设施
docker-compose up -d mysql redis kafka zookeeper victoria-metrics jaeger

# 启动 Planning Worker
cd planning-worker
mvn spring-boot:run
# 或多实例
java -jar target/planning-worker-1.0.0-SNAPSHOT.jar
```

### Verification
```bash
# 健康检查
curl http://localhost:8084/actuator/health

# 查看 Kafka 消费
docker-compose exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group planning-worker-group --describe

# 手动发送测试任务
docker-compose exec kafka kafka-console-producer.sh --bootstrap-server localhost:9092 --topic planning.jobs <<< '{"taskId":"test-1","tripId":"trip-1","versionId":"ver-1","input":"{}"}'
```