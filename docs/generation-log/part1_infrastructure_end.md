# 第一部分：项目骨架 & 基础设施 - 结束文档

## 生成完成清单

### 1. Maven 父工程
- `pom.xml` - 统一依赖版本管理、插件配置、模块聚合

### 2. 公共模块 (common-module)
```
common-module/
├── pom.xml
└── src/main/java/com/tripplanner/common/
    ├── CommonModuleApplication.java
    ├── annotation/
    │   ├── RequirePermission.java      # 资源权限注解
    │   └── LogExecutionTime.java       # 方法耗时日志注解
    ├── config/
    │   ├── JacksonConfig.java          # Jackson 序列化配置
    │   ├── RedisConfig.java            # Redis 模板 & CacheManager
    │   ├── WebMvcConfig.java           # CORS、日期格式化
    │   └── OpenApiConfig.java          # Swagger/OpenAPI 3 配置
    ├── exception/
    │   ├── BizException.java           # 业务异常基类 + 静态工厂
    │   └── GlobalExceptionHandler.java # 全局异常处理器
    ├── response/
    │   ├── ApiResponse.java            # 统一成功响应
    │   ├── ErrorDetail.java            # 错误详情
    │   ├── Meta.java                   # 分页/版本元数据
    │   └── AsyncTaskResponse.java      # 异步任务响应
    └── util/
        ├── RequestIdUtils.java         # 请求 ID 生成/获取 (链路追踪)
        ├── SpelUtils.java              # SpEL 表达式解析
        ├── JsonUtils.java              # JSON 序列化/反序列化
        └── GeoUtils.java               # 地理空间计算 (Haversine)
```

### 3. 基础设施编排
- `docker-compose.yml` - MySQL、Redis、Kafka、Zookeeper、VictoriaMetrics、Jaeger、Vector
- `init-sql/01_schema.sql` - 完整建表脚本 (9张表：users, trips, trip_versions, activities, pois, planning_tasks, user_preferences, audit_logs, system_configs)
- `init-sql/02_indexes.sql` - 附加组合索引优化
- `init-sql/03_test_data.sql` - 开发/测试基础数据 (3用户、8个杭州POI、1条完整行程)
- `.env.example` - 环境变量模板 (含 JWT 密钥生成说明、地图 API、LLM API 配置)
- `.gitignore` - 完整忽略规则
- `vector/vector.yaml` - 日志收集配置 (Docker Logs → VictoriaMetrics + Jaeger)

## 核心设计决策

| 项 | 选择 | 理由 |
|----|------|------|
| 数据库 | MySQL 8.0 + Spatial | 团队熟悉、空间扩展满足 POI 查询、JSON 支持良好 |
| 缓存 | Redis 7 + Lettuce | 高性能、支持集群、Spring Cache 集成简单 |
| 消息队列 | Kafka 3.6 | 高吞吐、持久化、回溯能力、适合异步规划任务 |
| 服务框架 | Spring Boot 3.2 + Spring Cloud 2023 | Java 21 原生支持、生态成熟、团队技术栈统一 |
| 认证 | JWT RS256 + Redis Refresh Token | 无状态、微服务友好、Token 可撤销 |
| 监控 | VictoriaMetrics + Jaeger + Vector | 轻量、成本可控、Grafana 生态兼容 |
| 部署 | Docker Compose (开发) | 单机/少量节点即可跑通全链路、无 K8s 运维负担 |

## 表结构要点

1. **trips** - 行程主表，支持游客模式 (`user_id` nullable)、分享 token、版本控制
2. **trip_versions** - 不可变版本链，存储完整快照 (activities/routes/conflicts/stats)，支持回滚/分支
3. **activities** - 扁平化存储，冗余 poi_name/location 避免连表，含空间索引
4. **pois** - 多源缓存 (amap/baidu/osm/manual)，全文搜索 + 空间索引
5. **planning_tasks** - 异步任务追踪，记录进度、阶段、求解器统计

## 下一步：Auth Service

待生成内容：
- Spring Security 配置 (JWT 过滤器、密码编码器、权限评估器)
- 用户注册/登录/刷新 Token/登出 API
- RBAC 权限模型 (ROLE_USER, ROLE_ADMIN + 资源级权限)
- 用户信息查询/更新、偏好管理
- 单元/集成测试

## 启动验证

```bash
# 1. 复制环境变量
cp .env.example .env
# 编辑 .env 填入实际密钥 (JWT、地图 API、LLM API)

# 2. 启动基础设施
docker-compose up -d mysql redis zookeeper kafka victoria-metrics jaeger vector

# 3. 验证数据库初始化
docker-compose logs mysql | grep "initialized"

# 4. 访问监控
# VictoriaMetrics: http://localhost:8428
# Jaeger UI: http://localhost:16686
# Vector API: http://localhost:8686
```