# Part 5: Planning Worker - Start Document

## Generation Scope
- OR-Tools CP-SAT solver integration (exact solving for ≤15 POIs)
- Constraint model building (time windows, durations, transport, priorities, no-overlap, blocked periods)
- Objective function (priority coverage, minimize travel time, match preferences, reduce fragmentation)
- Heuristic solver for large scale (>15 POIs): greedy construction + local search + simulated annealing
- Incremental replanning (fix unchanged parts, re-optimize affected subset)
- Kafka consumer for planning.jobs / planning.replan.jobs
- Result persistence (TripVersion, Activities, Routes) + publish planning.events
- Solver statistics & monitoring

## Tech Stack
- Spring Boot 3.2.5
- Spring Kafka (high-throughput consumer)
- OR-Tools Java 9.8 (CP-SAT)
- MyBatis-Plus + MySQL (result persistence)
- Redis (distributed lock, progress cache)
- Common Module, Plan Service (DTOs), Trip Service (entities)

## Expected Directory Structure
```
planning-worker/
├── pom.xml
└── src/main/java/com/tripplanner/worker/
    ├── PlanningWorkerApplication.java
    ├── config/
    │   ├── SolverConfig.java               # CP-SAT 参数配置
    │   ├── KafkaConsumerConfig.java        # 规划任务消费者
    │   └── OrToolsConfig.java              # OR-Tools 初始化
    ├── consumer/
    │   ├── PlanningJobConsumer.java        # 消费 planning.jobs
    │   └── ReplanJobConsumer.java          # 消费 planning.replan.jobs
    ├── solver/
    │   ├── CpSatSolver.java                # 精确求解器 (≤15 POI)
    │   ├── HeuristicSolver.java            # 启发式求解器 (>15 POI)
    │   ├── IncrementalReplanner.java       # 增量重规划
    │   ├── model/
    │   │   ├── PlanningProblem.java        # 问题定义
    │   │   ├── ActivityVar.java            # 活动变量
    │   │   └── TravelMatrix.java           // 距离/耗时矩阵
    │   └── constraint/
    │       ├── TimeWindowConstraint.java
    │       ├── DurationConstraint.java
    │       ├── TransportConstraint.java
    │       ├── PriorityConstraint.java
    │       ├── NoOverlapConstraint.java
    │       └── BlockedPeriodConstraint.java
    ├── service/
    │   ├── PlanningOrchestrator.java       # 编排整个求解流程
    │   ├── ResultPersistService.java       # 持久化结果到 TripService
    │   ├── ProgressPublisher.java          # 发布进度到 Redis/Kafka
    │   └── GeocodeClient.java              # 调用 Plan Service 地理编码
    ├── dto/
    │   ├── PlanningJobInput.java
    │   ├── ReplanJobInput.java
    │   └── SolverResult.java
    └── resources/
        └── application.yml
```

## Kafka Topics
- **planning.jobs** (12 partitions) - 新建行程规划
- **planning.replan.jobs** (6 partitions) - 反馈重规划
- **planning.events** (6 partitions) - 完成/失败事件发布

## Solver Parameters
```java
// CP-SAT 参数
max_time_in_seconds: 30
num_search_workers: 4 (CPU 核心数)
log_search_progress: true
relative_gap_limit: 0.01
```

## Problem Modeling

### Variables
- `start[a] ∈ [earliest_a, latest_a - minDur_a]` - 活动开始时间 (分钟粒度)
- `end[a] = start[a] + dur[a]` - 结束时间
- `interval[a] = [start[a], end[a])` - 区间变量
- `presence[a] ∈ {0,1}` - 可选地点是否安排 (必去地点 presence=1)

### Constraints
1. **Time Window**: `earliest_a ≤ start[a] < end[a] ≤ latest_a`
2. **Duration**: `minDur_a ≤ dur[a] ≤ maxDur_a`
3. **Transport**: `start[b] ≥ end[a] + travelTime(a,b,mode)`
4. **Priority**: `presence[must] = 1`
5. **No Overlap**: `AddNoOverlap([interval[a] for a in nonTransit])`
6. **Blocked Periods**: 活动不与 blocked periods 重叠
7. **Sequence**: 根据优先级和地理位置确定访问顺序

### Objective (Maximize)
```
Σ (priorityWeight[p] * presence[p])                    // 覆盖高优先级
- α * Σ travelTime(a,b) * presence[a] * presence[b]   // 最小化在途
- β * Σ |start[a] - preferredStart[a]| * presence[a]  // 贴合偏好时间
- γ * fragmentationPenalty                            // 减少碎片空窗
```

## Incremental Replanning
1. Identify affected activities (changed + downstream dependent)
2. Fix unchanged activities (add equality constraints)
3. Solve subproblem with reduced variable set
4. Merge solutions

## Progress Stages & Percentages
| Stage | Percent | Description |
|-------|---------|-------------|
| PARSE_INPUT | 10% | 已在 Plan Service 完成 |
| GEOCODE | 25% | 批量地理编码 |
| BUILD_MODEL | 40% | 构建 CP-SAT 模型 |
| SOLVE | 70% | 求解器运行 |
| ROUTE | 85% | 批量路线规划 |
| PERSIST | 95% | 保存结果 |
| COMPLETED | 100% | 发布完成事件 |