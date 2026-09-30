# TripForge · Agent Trip Planner

> AI 智能行程规划系统：用自然语言描述需求，系统自动解析并生成**结构化、可执行、带地图路线的每日行程**，支持在线编辑、版本管理与公开分享。

输入示例：`想 11 月去成都玩三天，喜欢熊猫和老街，节奏别太赶` → 系统自动生成逐日景点/餐厅/交通安排、游玩时长与路程时间校验后的完整行程。

---

## 功能特性

- **自然语言输入**：LLM 解析目的地、日期、偏好、节奏（紧凑/适中/宽松）等结构化约束
- **AI 行程规划**：规划引擎求解每日景点安排、餐次保底、营业时间与 21:00 闭园约束、真实路程时间
- **行程版本管理**：规划产出多版本，支持查看、回滚与增量编辑
- **公开行程广场**：发布/浏览公开行程，按**城市、景点、餐厅等地点名称**搜索，展示作者 ID
- **实时进度推送**：WebSocket (STOMP) 推送规划任务状态
- **地图能力**：高德地图地理编码 / 路线规划，交通段与地图线路点击导航
- **账号体系**：JWT (RS256) 认证、刷新令牌、RBAC 权限
- **行程导出**：HTML/图片导出（html2canvas + jsPDF）

## 系统架构

```
浏览器 (Vue 3 SPA)
     │  :8086
     ▼
┌──────────────────────────────────────────────────┐
│ Gateway  路由 · JWT 鉴权 · 限流 · 前端静态资源      │
└──────────────────────────────────────────────────┘
     ├──────────────┬──────────────┬───────────────┐
     ▼              ▼              ▼               ▼
 Auth :8081     Trip :8082     Plan :8083    Notification :8085
 认证/用户        行程 CRUD       LLM 输入解析     WebSocket 推送
 RBAC/偏好       版本/公开广场    规划任务创建
                     │              │
                     │              ▼  Kafka
                     │       Planning Worker :8084
                     └──────── 求解优化 · 路线计算
```

**基础设施**：MySQL 8.0（行程数据）· Redis 7（缓存/会话）· Kafka + ZooKeeper（异步任务）· 可选 VictoriaMetrics / Jaeger / Vector（监控链路）

| 服务 | 端口 | 职责 |
|------|------|------|
| gateway | 8086 | 统一入口：路由、鉴权、限流、前端静态资源 |
| auth-service | 8081 | 用户认证、JWT 签发、用户偏好 |
| trip-service | 8082 | 行程 CRUD、版本管理、公开行程 |
| plan-service | 8083 | 自然语言解析（LLM）、规划任务创建 |
| planning-worker | 8084 | 行程求解优化、路线计算（无对外 HTTP） |
| notification-service | 8085 | WebSocket 实时推送 |

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 21 · Spring Boot 3.1.5 · Spring Cloud 2022.0.5 · MyBatis-Plus · Lombok · LangChain4j · Hutool |
| 前端 | Vue 3 · TypeScript · Vite · Pinia · Tailwind CSS · STOMP WebSocket |
| 基础设施 | MySQL 8.0 · Redis 7 · Kafka 3.6 · Docker Compose（可选全量基础设施） |
| LLM | 智谱 GLM（`glm-4.5-flash`，OpenAI 兼容接口可替换，支持 Ollama 本地模型） |
| 地图 | 高德 Web 服务 API（百度为备选降级） |

## 快速开始

### 环境要求

- JDK 21、Maven 3.8+、Node.js 18+
- MySQL 8.0、Redis 7、Kafka + ZooKeeper（本机服务或 Docker 任一）

### 1. 获取代码并配置环境变量

```bash
git clone git@github.com:dreamer6364/agent-trip-planner.git
cd agent-trip-planner
copy .env.example .env    # Windows；然后编辑 .env
```

`.env` 中至少配置（**不会提交到仓库**，已由 `.gitignore` 忽略）：

- `AMAP_API_KEY` — 高德地图（[申请](https://lbs.amap.com/)），缺失会导致地标/路程缺失
- `ZHIPU_API_KEY` / `LLM_API_KEY` — LLM 规划与解析，缺失会导致 AI 规划失败
- `MYSQL_PASSWORD` — 数据库密码（配置文件不再内置默认值，由启动脚本注入）

### 2. 准备基础设施

**方式 A（本机，推荐）**：Windows 服务运行 MySQL80 (3306) 与 Redis (6379)，Kafka/ZooKeeper 用 Docker：

```bash
docker compose up -d zookeeper kafka
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS trip_planner DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p trip_planner < init-sql/01_schema.sql
mysql -uroot -p trip_planner < init-sql/02_indexes.sql
mysql -uroot -p trip_planner < init-sql/03_test_data.sql            # 演示数据（含演示账号）
mysql -uroot -p trip_planner < init-sql/04_migration_20260926.sql
```

**方式 B（全 Docker 基础设施）**：`docker compose up -d` 启动 MySQL/Redis/Kafka 等（MySQL 映射 3307，需相应调整 `.env` 与服务连接参数）。

### 3. 生成 JWT 密钥（仅首次克隆）

JWT 使用 RS256，密钥文件不入库（`.gitignore` 忽略）。首次克隆需生成并放置：

```bash
openssl genrsa -out auth-service/src/main/resources/keys/private_key.pem 2048
openssl rsa -in auth-service/src/main/resources/keys/private_key.pem -pubout \
  -out auth-service/src/main/resources/keys/public_key.pem
# 公钥复制为 gateway 与 notification-service 的 resources/keys/public_key.pem
# gateway 亦需 private_key.pem（与 auth 相同即可）
```

### 4. 构建并启动

```powershell
# 前端构建（产物同步到 gateway 静态资源）
cd frontend-new; npm install; npm run build; cd ..
robocopy frontend-new\dist gateway\src\main\resources\static /MIR

# 一键启动（后端 jar 缺失时自动构建；含健康检查）
.\start-all.ps1 -Action start
# 或双击 run.bat
```

### 5. 访问系统

- 前端：<http://localhost:8086>
- 演示账号：`admin@tripplanner.com` / `Admin@123456`
- 各服务健康检查：`http://localhost:8081~8086/actuator/health`

## 常用命令

```powershell
.\start-all.ps1 -Action status     # 查看 6 服务状态
.\start-all.ps1 -Action restart    # 重启（部署后）
.\start-all.ps1 -Action stop       # 停止本项目服务
.\start-all.ps1 -Action tail -Service plan-service   # 按 UTF-8 查看日志
.\start-all.ps1 -Build -BuildFrontend -Action start  # 先构建再启动
```

## 测试

```bash
mvn -o test                 # 后端全量单测
node verify-rest.js          # REST 回归（需服务已启动）
node e2e-pace.js             # 节奏分档端到端（创建真实行程，约 1-2 分钟）
node e2e-diversity.js        # 多样化与时间约束端到端
node check-time.js           # 校验近 2 小时行程版本的时间/餐次约束
```

详见 [TESTING.md](TESTING.md)。改动版本必须同步登记 [docs/CHANGELOG.md](docs/CHANGELOG.md) / [docs/BUGFIX.md](docs/BUGFIX.md)。

## 项目结构

```
├── gateway/                 # Spring Cloud Gateway（8086，含前端静态资源）
├── auth-service/            # 认证服务（8081）
├── trip-service/            # 行程服务（8082）
├── plan-service/            # 规划解析服务（8083）
├── planning-worker/         # 规划求解 Worker（8084，Kafka 消费）
├── notification-service/    # 通知服务（8085，WebSocket）
├── common-module/           # 公共模块
├── frontend-new/            # Vue 3 前端（构建产物 → gateway/static）
├── init-sql/                # 建库脚本（schema / indexes / 演示数据）
├── docs/                    # API 文档 · 架构设计 · CHANGELOG · BUGFIX
├── start-all.ps1 / run.bat  # 统一启动入口
├── e2e-*.js / verify-*.js   # 端到端与回归脚本
└── docker-compose.yml       # 基础设施编排（MySQL/Redis/Kafka/监控）
```

## 文档

| 文档 | 说明 |
|------|------|
| [AGENTS.md](AGENTS.md) | 开发约束规范：架构边界、编码规范、版本文档制度（**贡献前必读**）|
| [TESTING.md](TESTING.md) | 测试矩阵与执行方式 |
| [docs/API.md](docs/API.md) | REST API 全量文档 |
| [docs/架构设计.md](docs/架构设计.md) | 架构与模块设计 |
| [docs/需求文档.md](docs/需求文档.md) | 需求与业务规则 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) / [docs/BUGFIX.md](docs/BUGFIX.md) | 版本变更 / 修复记录 |

## 开发规范速览

- 提交信息遵循 Conventional Commits：`feat(trip): …` / `fix(plan): …` / `chore: …`
- 每次功能/修复**当场**写入 CHANGELOG / BUGFIX 对应版本条目（规则见 AGENTS.md §8.3）
- 微服务间只经 Kafka 或 HTTP（Feign）通信，禁止跨库直连与循环依赖
- 敏感信息一律走 `.env` / 环境变量，禁止硬编码入库
