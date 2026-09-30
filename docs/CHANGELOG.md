# 版本更新日志

TripForge 的所有重要变更都会记录在此文件中。

**重要：每次功能改动都必须在此文件中记录，包括新增功能、优化、重构等。新记录追加在最上方，格式为 `## [版本号] - YYYY-MM-DD`。**

**版本号规则**：采用 `主.次.修订` 三段十进制（基线 1.11.0）。功能新增/增强 → 次版本 +1（如 1.11.0 → 1.12.0）；修复/部署/文档等非功能性变更 → 修订号 +1（如 1.12.0 → 1.12.1）。**每次会话内的功能变化都必须当场写入本文件（纯修复类同时写 `docs/BUGFIX.md`），不得留到以后补记。**

> **基线说明**：本文件于 2026-09-25 重置，此前的历史版本记录已按要求舍弃，不作为追溯依据。
> 当前基线版本为 **1.11.0**，其后所有变更以此为起点递增。

---

## [1.26.1] - 2026-09-30

### 文档

- 新增根目录 `README.md`（GitHub 项目首页），内容包括：
  - 项目简介与输入示例、功能特性（8 项）
  - 系统架构 ASCII 图、6 个服务端口/职责表、基础设施说明
  - 技术栈分层表（后端/前端/基础设施/LLM/地图）
  - 快速开始 5 步：克隆与 `.env` 配置、基础设施（本机 / 全 Docker 双方式 + `init-sql` 建库顺序）、JWT 密钥首次生成（密钥不入库）、构建启动（前端 robocopy + `start-all.ps1`）、访问地址与演示账号
  - 常用命令、测试入口、项目结构树、文档索引、开发规范速览
- 涉及文件：`README.md`（新增）、`docs/CHANGELOG.md`

### 验证方式与结果（2026-09-30）

| 项 | 结果 |
|---|---|
| 快速开始步骤与实际环境核对 | 服务端口/启动命令/init-sql 顺序/演示账号与 `start-all.ps1`、`init-sql/`、e2e 脚本实际配置一致 ✓ |
| 密钥扫描 | README 无任何真实密钥/数据库密码字面量（均为占位符）✓ |

## [1.26.0] - 2026-09-29

### 重构与仓库整理（公开发布准备）

- **启动脚本合并收敛**
  - 删除 7 个冗余脚本：`start.bat`、`stop.bat`、`start-auth-simple.bat`、`start-gw-simple.bat`、`start-plan-simple.bat`、`start-trip-simple.bat`（4 个 simple 脚本各自硬编码数据库密码，且与 `start-all.ps1` 职责重复）、`restart-all.ps1`（旧版全量重启会强杀所有 Java 进程，误伤无关项目，且未注入 Key/日志参数）
  - 统一入口：命令行用 `start-all.ps1 -Action start|stop|restart|status|tail`，双击用 `run.bat`（转发参数到 start-all.ps1）
  - 同步更新 `AGENTS.md` §8.3 前端部署链末段为 `.\start-all.ps1 -Action restart`
- **测试脚本数据库凭据统一**：新增根目录 `db-env.js`，从 `.env`/环境变量读取连接参数（缺密码时显式报错退出）；`check-time.js`、`showtrip.js`、`e2e-pace.js`、`e2e-diversity.js` 4 个直连 MySQL 的脚本改为引用 `dbEnv.args()`，全仓库代码不再硬编码数据库密码
- **运行期垃圾清理**：删除根目录与各服务目录散落的 76 个运行期文件（`trip-service-app.log`、各服务 `*.log`/`*.err.log`、`logs/` 旧轮转 `*.gz`、`e2e-*-report.txt` 等）；`logs/` 由启动脚本自动重建
- **GitHub 公开仓库**：脱敏后推送至 `dreamer6364/agent-trip-planner`（公开），推送前完成代码/历史密钥扫描
- 涉及文件：`AGENTS.md`、`docker-compose.yml`、`start-all.ps1`、`db-env.js`（新增）、`check-time.js`、`showtrip.js`、`e2e-pace.js`、`e2e-diversity.js`、5 个服务 `application.yml`（脱敏详见 `docs/BUGFIX.md` 1.26.0）、7 个删除的脚本

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| 全量后端单测 `mvn -o test` | 6 suites / 82 tests / 0 failures ✓ |
| 脱敏后重启 `start-all.ps1 -Action start` | 187s，6/6 服务 UP（MYSQL_PASSWORD 由 .env 注入）✓ |
| `node --check`（db-env.js + 4 脚本） | 语法全部通过 ✓ |
| `node check-time.js`（db-env 实连数据库） | ALL_PASS ✓ |
| `node verify-rest.js`（REST 全量回归） | pass=28 fail=0 ✓ |
| 代码硬编码密码扫描（HEAD） | 本地开发数据库密码在代码中出现 0 处；LLM/高德 Key、.env、私钥文件 0 处 ✓ |
| 历史提交密钥扫描（全部 8 commits） | API 密钥/私钥/.env 均 0 处 ✓ |

## [1.25.0] - 2026-09-29

### 功能增强

- **公开行程搜索框（按地点搜索）**
  - `GET /api/trips/public` 新增可选 `keyword` 参数：非空时按 **标题 / 描述 raw_input / 当前版本行程内地点名** 三路模糊匹配——地点路走 `EXISTS + JSON_SEARCH(trip_versions.activities)`，覆盖行程里的景点与餐厅名（title/raw_input 不含的地名也能搜到）；空白关键词保持原查询与索引路径
  - 新增 `TripRepository.searchPublicTrips` / `countPublicTripsByKeyword`，`TripService.listPublicTrips(page, size, keyword)` 按关键词分流
  - `ExplorePage` 标题区下方新增搜索框：300ms 输入防抖、回车即时搜索、一键清除、提示文案「支持搜索城市、景点、餐厅等地点名称」；搜索态空结果展示「未找到与「kw」相关的行程」+ 清除搜索按钮（非搜索态保留原引导文案）
  - 文件：`trip-service/.../repository/TripRepository.java`、`trip-service/.../service/TripService.java`、`trip-service/.../controller/TripController.java`、`frontend-new/src/api/trip.ts`、`frontend-new/src/views/ExplorePage.vue`
- **公开行程卡片展示作者 ID**
  - 公开行程 API 响应本就含 `userId`（`toResponse` 映射，实测 2/2 非空），但 UI 从未展示；`TripCard` 元信息区新增作者 ID 行——仅 `isPublic` 行程显示（`ri-user-3-line` 图标 + 等宽字体全量 ID + `select-all` 可整段复制），私有/草稿卡片不受影响
  - 文件：`frontend-new/src/components/trip/TripCard.vue`

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| trip-service 单测（`mvn -pl trip-service -am test`） | PASS ✓ |
| `npm run build`（vue-tsc 类型检查 + vite 构建） | PASS ✓ |
| `GET /api/trips/public` 作者 ID 覆盖 | 2/2 条目 `userId` 非空、`isPublic=true` ✓ |
| 标题关键词 | `验证-杭` → hits=1，结果含 userId ✓ |
| 行程内地点名关键词（title/raw_input 均不含，专走 JSON_SEARCH 分支） | 雷峰塔 / 南宋德寿宫遗址博物馆 / 深圳湾公园 / 深圳欢乐谷 / 深圳海洋世界 / 深圳博物馆 **6/6 命中** ✓ |
| 不存在关键词 / 空关键词 | 0 条 / 与基线一致（2 条）✓ |
| 前端部署 | gateway 提供新 `index-DOeqsbYy.js`（与 dist 一致）、`ExplorePage-D_2S6nCU.js` 已同步、`/explore` 200 ✓ |

## [1.24.0] - 2026-09-29

### 优化（AI 规划提速：单行程端到端典型 ~170s → 24-75s，行程 LLM 55-107s → 4-26s）

- **切换规划模型 `glm-4-flash` → `glm-4.5-flash`（禁用思考）**
  - glm-4.5 为推理模型，不关思考会烧光输出预算返回空（实测 135.5s 空输出）。新建 `ZhipuChatModel`（langchain4j 0.35 `ChatLanguageModel` HTTP 直连，须同时覆写 `chat(ChatRequest)` 与 abstract `generate(List<ChatMessage>)`），按模型名前缀（`glm-4.5/4.6/5`）注入 `thinking={"type":"disabled"}`（OpenAiChatModel builder 无 extra-body 参数）；仅记调用摘要日志（替代 logRequests/logResponses），瞬时故障（网络/429/5xx/空内容）重试 2 次，业务错误码（1113/1211）不重试
  - bench 实测：glm-4.5-flash slim+nothink 28.5s/803 tokens（glm-4-flash 同条件 69.3s/1088）
  - `AgentController /react-plan` 工具调用路径固定 `glm-4-flash`（该路径无法注入关思考参数）
  - 文件：`plan-service/.../agent/ZhipuChatModel.java`（新增）、`plan-service/.../agent/TripPlanningAgent.java`、`plan-service/.../controller/AgentController.java`、`plan-service/src/main/resources/application.yml`
- **距离矩阵本地化**：主路径 `buildRealDistanceMatrix`（108 次高德路线调用，QPS=5 限速 200ms/次、冷缓存 12-21s）→ `buildStraightLineMatrix`（本地直线距离，3ms-1.1s）；真实路网仍由 `correctStep` 统一修正（e2e-distance RECALC_OK 不变）；删除 `buildMatrixBlock`/`recommendMode`（`GeocodeService` QPS=3 须串行，并行 geocode 与 planExecutor 调优均无收益，已放弃）
  - 文件：`TripPlanningAgent.java`
- **行程 schema 瘦身 + 字段水合**：主 prompt/`planSingleDay` 输出改为 `{day,name,startTime,durationMin}` 四字段——`durationMin` 必须保留（3 字段版由水合用 parse 大时长兜底，导致日超预算、21:00 裁尾、访问数掉 band），让 LLM 自调节奏；其余字段 `hydrateActivity` 从 places/meals 水合（type/priority/travel/endTime 合成）；新增 `estimateTravelTimes` 按本地坐标（place 坐标 → geoNameCache → CITY_RESTAURANTS）预估相邻段交通供时间窗步骤使用；prompt 压缩至 2.0-3.4k 字符（原 6-8k），响应 0.4-1.6k 字符
  - 文件：`TripPlanningAgent.java`
- **行程 JSON 解析失败自动重试 1 次**：glm-4.5 偶发输出不合法 JSON（缺逗号/引号），原直接落入 inline 回退（不走 postPipeline，正餐/水合缺失）；现 `parseItineraryJson` 失败即重调 LLM 一次，仍失败才回退。验证中实际触发：attempt=1 解析失败 → attempt=2 成功（18.6s）
  - 文件：`TripPlanningAgent.java`

### 修复

- **持久层误杀嵌套名正餐**：`dedupeActivitiesForPersist` 餐食 vs 景点用 `nameContains` 包含判定，「晚餐·青岛老城海鲜馆」包含景点「青岛老城」被剔（6→5 缺晚餐）；按文档口径「等价视为重复」改为归一化后精确相等（meal-vs-visit 双向），同名餐厅全程去重与同日同餐次唯一口径不变——详见 `docs/BUGFIX.md` 1.24.0
  - 文件：`trip-service/.../service/TripService.java`
- **21:00 裁剪后每日景点数掉至节奏下限以下**：`belowPaceFloor` 只看游览时长不看景点数，时长达标但 21:00 裁剪把 visits 裁到下限以下时 `refillStep` 不触发（e2e-pace moderate day1 3→2 FAIL）；补入 `visitCount < pace.getMinVisits()` 判定（`fillDayGaps` 本身已按数量+时长双下限、21:00 窗口内插入）——详见 `docs/BUGFIX.md` 1.24.0
  - 文件：`TripPlanningAgent.java`

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| plan-service 单测 | 48/48 PASS ✓ |
| trip-service 单测 | 19/19 PASS ✓ |
| `e2e-pace.js` | PASS（compact 4+4、moderate 3+4、relaxed 2+2，band/cnt/预算全 OK）✓ |
| `repro-meal.js`（青岛1日+苏州3日，修复后两轮） | 全部天 lunch=Y dinner=Y ✓（「青岛老城海鲜馆」过嵌套名去重关卡）✓ |
| `e2e-dedupe.js` | PASS（无重复景点/餐食、楼外楼在、河坊街 visit+meal 共存）✓ |
| `e2e-diversity.js` | PASS（5 类目、4 天正餐全、无占位名）✓ |
| `e2e-distance.js` | RECALC_OK（西湖→雷峰塔 6.2km/55min 等真实路网修正正常）✓ |
| `verify-rest.js` / `verify-d.js` / `verify-variant.js` | 28/28、22/22、16/16 PASS ✓ |
| 耗时对比（本轮日志） | 行程 LLM 4-26s（多数 attempt=1，原 55-107s）；解析 LLM 典型 5-16s（原 13-23s）；矩阵 3ms-1.1s（原 12-21s）；e2e-pace 单行程 32s（原 ~170s，并发负载下最高 ~110s）✓ |

## [1.23.0] - 2026-09-29

### 功能增强

- **每日正餐保底（只要时间允许，每天必有午餐+晚餐）**
  - 背景：用户反馈「午餐晚餐丢失」——青岛1日 d1 仅早餐缺午晚、苏州3日 d3 全天无餐；根因链为 dedupe 跨天同名餐剔除 + ensureComplete 补餐被后续 dedupe 二次剔除 + 兜底位置过早（详见 `docs/BUGFIX.md` 1.23.0）
  - 新增纯策略类 `DailyMealPlanner`：零打扰优先落位（空隙容 60 分钟 + travel 收缩 ≤15 分钟），无空隙则按 `fixTimeOverlaps` 同构级联推演（travel 先 15 后 0）；午目标 11:30（最晚 15:00）、晚 17:30（最晚 20:00），日窗 `[dayFloor, 21:00]`，越 21:00 即判「时间不允许」放弃——宁缺餐不删景点；返回原始目标时刻，实际落位交 `fixTimeOverlaps` 顺延
  - `TripPlanningAgent` postPipeline 新增 `ensureDailyMealsStep`：插在 `applyPaceBudget` 之后、`annotateMealRestaurants` 之前（= 全流水线最后一次 dedupe 之后；其后注解/合规/校正/人性化/归一化/补时序/插休息均不删餐）；主路径与 inline 回退共用 postPipeline，故仅覆盖主路径（inline 回退自带窄窗午餐/晚餐逻辑，本轮未改，见已知限制）
  - 餐名三级生成：用户点名餐厅 → `pickRestaurant` 15 轮消费 → 泛化名候选（`city+美食/本地美食/FOOD_KEYWORDS/修饰词`）；`mealNameAvailable` 对全程活动逐一 `samePlace` 判交，保证餐名过 trip-service `dedupeActivitiesForPersist` 同名全程去重关卡
  - 餐次/餐厅识别统一 `mealBareName`（兼容前缀 `午餐·楼外楼`、后缀 `楼外楼·午餐`、裸名）与 `mealSlotOf`（餐次字优先、开始时间兜底）
  - 文件：`plan-service/.../agent/DailyMealPlanner.java`（新增）、`plan-service/.../agent/TripPlanningAgent.java`

### 修复

- **午餐晚餐丢失根因修复**：ensureComplete `usedRestaurants` 收集改用 `mealBareName` 全活动遍历（后缀式 `X·午餐` 此前只取到「午餐」，致 pickRestaurant 漏防撞名被持久层剔除）；`hasLunch/hasDinner` 改 `mealSlotOf` 槽位口径（裸餐厅名按时间判餐次，避免误判缺餐反复补餐）；`enrichMealNames` 预置全程 `mealBareName` 种子防升级撞名——详见 `docs/BUGFIX.md` 1.23.0
  - 文件：`plan-service/.../agent/TripPlanningAgent.java`

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| `DailyMealPlannerTest`（新增 12 用例） | 12/12 PASS ✓ |
| plan-service 全套单测（含 RestSchedulePolicyTest / VariantExclusionsTest / PoiSearchFuzzyTest） | 48/48 PASS ✓ |
| `repro-meal.js` 新建青岛1日 + 苏州3日 | 两案例全部天 `lunch=Y dinner=Y` ✓（苏州 d3 此前全天 0 餐）；日志确认保底触发（青岛补晚餐→泛化升级「肥三土菜馆」、苏州 d3 补午晚→午餐升级「端点烤翅」）✓ |
| `verify-rest.js` 回归 | 28/28 PASS ✓ |
| `verify-d.js` 回归 | 22/22 PASS ✓ |
| `verify-variant.js` 回归 | 16/16 PASS ✓ |
| `e2e-diversity.js` | PASS ✓（4 天午晚餐齐全、无占位名） |
| `e2e-pace.js` 三档 | 首轮 moderate/relaxed PASS、compact 触发既有 `applyPaceBudget` 景点数上限裁剪致 day2 410min<450 下限（LLM 方差 flake，与本改动机制无关：保底步未触发、budget 逻辑未改）；`PACE=compact` 复跑 PASS（8.1h/8.0h）✓ |
| 部署 | `mvn -o package -DskipTests` BUILD OK → 服务重启 → TCP 8081-8086 6/6 UP；无前端改动跳过 npm build/robocopy ✓ |
| 部署附带 | `trip-planner-kafka` 启动时 ZK `NodeExists` 崩溃（exit 1）→ `docker restart zookeeper` + `start kafka` 恢复，消费组全部重新注册 ✓ |

---

## [1.22.1] - 2026-09-29

### 功能增强

- **分享页交通行点击导航**：`ShareView` 行程卡片的路程行（「驾车 · 23分钟」）升级为按钮，与时间轴 pill 一致调用 `utils/navigation.ts` 打开高德导航（同天下一段有坐标走坐标导航，否则 POI 名称降级）；无下一段/非同天保留静态展示
  - 文件：`frontend-new/src/components/share/ShareView.vue`

### 修复

- **同日第二次休息被 `fixTimeOverlaps` 重排到下一活动之后（travel 归零不变式破坏）**：`RestSchedulePolicy.planDay` 的 `startMin` 含当日已接受休息偏移 `shift`，而后续活动钟点尚未偏移，按 startTime 重排时 shift(20) > 前一活动 travel(7) 即错位——`startMin` 改为**不含偏移**（= 前一活动当前钟点，恒 ≤ 下一活动现有钟点），标签仍按 `end+shift` 预估钟点取名；单测用例同步（840→820）
  - 文件：`plan-service/.../agent/RestSchedulePolicy.java`、`RestSchedulePolicyTest.java`
- **rest 被强生成泛化 slogan（「探索精彩旅程」）**：`TripVersionService.toResponse` 对缺 slogan 活动一律补生成，rest 落到默认分支；跳过 `activityType=rest`（休息节点无签名语需求）
  - 文件：`trip-service/.../service/TripVersionService.java`
- **版本对比同名活动误报新增/移除**：`VersionCompareView` 第一轮按名匹配用 `Map<name,act>`，多个同名「中场休息」仅首个入表，其余落第三轮被判 added/removed；改为 `Map<name, Activity[]>` 队列配对（同天优先、取出即消）
  - 文件：`frontend-new/src/components/version/VersionCompareView.vue`
- **地图线路 hover 光标不恢复**：`TripMap` polyline `mouseout` 的 `setCursor('')` 改 `'default'`
  - 文件：`frontend-new/src/components/map/TripMap.vue`
- **导航按钮内嵌 div 违反 HTML 语义**：`TransitConnector` button 图标容器 `div` → `span`（button 仅允许 phrasing content），修正未匹配 `</div>` 闭合
  - 文件：`frontend-new/src/components/timeline/TransitConnector.vue`
- **测试资产**：`verify-rest.js` 固化到项目根（此前仅临时目录）；`verify-variant.js` 景点名提取排除 `rest`——「午后小憩」等结构性名称非 POI，不再误报「排除生效」

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| `RestSchedulePolicyTest` | 9/9 PASS ✅ |
| `verify-rest.js` 连跑两轮（3 休息 / 1 休息行程） | 28/28、18/18 PASS ✅ |
| `verify-variant.js`（rest 排除后复跑） | 16/16 PASS ✅ |
| `verify-d.js` 回归 | 22/22 PASS ✅ |
| `npm run build` → `robocopy /MIR` → `mvn -o package -DskipTests` → 重启 | BUILD OK、PACKAGE OK，8081-8086 TCP 6/6 UP ✅ |
| ShareView/pill/polyline 导航人工点击 | **未人工验证** ⚠️（window.open 无法无头验证） |

---

## [1.22.0] - 2026-09-28

### 功能增强

- **交通段与地图线路点击导航（时间轴 pill / 地图 polyline → 高德导航）**：
  - 背景：时间轴中间的路程段（如「驾车 23分钟 · 9.4km」）与地图上的路线只可看不可导；本轮点击两处均打开高德新标签导航
  - 新增 `utils/navigation.ts`：**有起终点坐标** → `uri.amap.com/navigation?from=lng,lat,name&to=…&mode=…&callnative=1`（网页版路线规划，有高德 App 则唤起）；**坐标缺失** → `uri.amap.com/direction` POI 名称导航降级；出行方式映射 walk→walk、drive/taxi→car/bike、transit/subway→bus、mixed→car；两端均无有效输入不动作
  - 时间轴 `TransitConnector`：pill 由 div 升级为 `<button>`，新增 hover 上浮+阴影、聚焦 ring、外链图标、`title=点击导航此路段`；`DayTimeline` 新增 `navPointOf(index, dir)` **坐标就近吸附**——起点向前/终点向后找最近有坐标活动（rest 无坐标时「原地休息」语义：起点吸附到前一活动、终点吸附到休息节点所接管路程的下一有坐标活动）
  - 地图 `TripMap.addPolylines`：每条线路 `click` → 按该段活动起终点与出行方式打开导航；`mouseover/out` 切换 `pointer` 光标提示可点击
  - `types.ts` Activity 补充 `lat?/lng?` 显式字段（此前靠 index signature 兜底）
  - 文件：`frontend-new/src/utils/navigation.ts`（新增）、`frontend-new/src/components/timeline/{TransitConnector.vue,DayTimeline.vue}`、`frontend-new/src/components/map/TripMap.vue`、`frontend-new/src/api/types.ts`

### 验证方式与结果（2026-09-28）

| 项 | 结果 |
|---|---|
| `npm run build`（vue-tsc 类型检查 + Vite 打包） | BUILD OK ✅ |
| 发布管线：`robocopy /MIR` → stop → `mvn -o package -DskipTests` → start | 8081-8086 全 UP（TCP 探测）、gateway 首页 200 ✅ |
| 回归冒烟 `verify-d.js` | 22/22 PASS ✅ |
| 导航 URL 构造（坐标导航 / POI 名称降级 / rest 吸附端点） | 代码走查 + build 通过，**未做人工浏览器点击**（window.open 无法无头验证，需人工点一次 pill 与地图线路确认新标签打开高德页）⚠️ |

---

## [1.21.0] - 2026-09-28

### 新功能

- **智能休息节点规划（按节奏频率插入「中场休息 / 午后小憩 / 上午茶歇」）**：
  - 背景：长时段连续游览无休憩提示；本轮目标——按节奏（pace）以**不同频率**自动插入休息节点
  - 后端·调度策略 `RestSchedulePolicy`（纯函数、无 IO，可单测）：三档参数 **紧凑 150 分钟阈值/15 分钟/每日 1 次、适中 120/20/2、宽松 90/30/2**；连续游览+在途累计达阈值即在触发活动后插入；**餐食与已有休息重置连续计时**（餐即休息）；下一项是餐不插；当天最后一项不插；**21:00 作息收口预演**（插入会把当日末尾推过 21:00 则放弃该次插入）；休息名称按钟点分档：<11:30 上午茶歇、13:00-17:30 午后小憩、其余中场休息
  - 后端·管道接入：`TripPlanningAgent.postPipeline` 末尾追加两步（**20 → 22 步**）——`insertRestStep`（try/catch 失败保留原活动）→ 再跑 `fixTimeOverlaps` 收口时间轴；`insertRestNodes` 按天分组、**先按 startTime 排序对齐 List 顺序与时间轴**（否则「前一活动」travel 归零会错位），插入后按不变式传播路程——**前一活动 travel 归零（原地休息）**，休息节点接管原「前一→后一」的 travel/distance/mode，后续活动时间由 fixTimeOverlaps 自动顺延
  - 后端·落库白名单（否则 rest 会被清洗/误统计）：`isVisitActivity`、`filterActivitiesByCity`、`replaceNonPlaceVisits` visitLike、`dedupeVisitActivities`（plan-service）；`mapActivityType` 增 `case "rest"`、`dedupeActivitiesForPersist` 豁免同名去重、`buildStatsFromActivities` 新增 `restDurationMin` 统计（rest 不计 visitDuration/placeCount，计入 totalDuration）（trip-service）；`CityOwnershipUtils.filterVisitsForCity/filterActivitiesForCity` 豁免（common-module）
  - 前端：`mapTheme TYPE_THEMES`+图例、`ActivityCard` rest 配置、`VersionCompareView TYPE_LABELS`、`ShareView` 图标/中文、`activity.ts computeStats` rest 分支、`VersionStats.restDurationMin?`；**rest 卡片禁拖拽/禁换序**（`DayTimeline` `:draggable`、`ActivityCard` swap 按钮 `v-if`）
  - 单测：新增 `RestSchedulePolicyTest` 9 用例（三档参数/阈值触发/餐前不插+重置/21:00 放弃/末活动不插/每日上限/紧凑档/名称边界/类型判定）
  - 已知限制：**inline 回退路径不插休息**（仅主路径 postPipeline）；测试口径 `e2e-pace.js` 游览时长统计已排除 `rest`
  - 文件：`plan-service/.../agent/{RestSchedulePolicy.java,TripPlanningAgent.java}`、`plan-service/src/test/.../RestSchedulePolicyTest.java`、`trip-service/.../TripService.java`、`common-module/.../CityOwnershipUtils.java`、`frontend-new/src/{utils/mapTheme.ts,utils/activity.ts,api/types.ts,components/timeline/{ActivityCard.vue,DayTimeline.vue},components/version/VersionCompareView.vue,components/share/ShareView.vue}`

### 验证方式与结果（2026-09-28）

| 项 | 结果 |
|---|---|
| `RestSchedulePolicyTest` 9 用例 | 9/9 PASS ✅ |
| 后端全量单测（plan 36 + trip 19 + common 15） | `BUILD SUCCESS` 全绿 ✅ |
| `verify-rest.js` E2E（新建 3 天适中节奏行程 → rest 存在/每日 ≤2/时长 20/名称三档/时间不变式/前一活动 travel=0 且休息接管路程/无坐标/`restDurationMin` 与手工验算一致/placeCount 不含 rest/末活动 ≤21:00） | 连续 **2 轮 23/0 PASS** ✅ |
| 首轮发现的 travel 归零错位（「清河坊步行街 travel=11 未归零」）→ 根因：`insertRestNodes` 按 List 原序取「前一活动」与时间轴不一致 → 加按 startTime 排序修复，取证日志确认归零/接管正确 | 修复后全绿 ✅ |
| 回归：`verify-d` / `verify-search` / `verify-variant` / `verify-rollback` | **22/22、28/28（首轮 27/28 为高德接口波动，重跑全绿）、16/16、17/17** ✅ |
| 发布管线：`npm run build`（vue-tsc）→ `robocopy /MIR` → stop → `mvn -o package -DskipTests` → start | 8081-8086 全 UP（TCP 探测）✅ |
| 休息节点前端展示/拖拽禁用 | 仅 vue-tsc+build+代码走查，未做人工浏览器点击 ⚠️ |

---

## [1.20.0] - 2026-09-28

### 功能增强

- **换景点搜索框模糊搜索（错别字 / 字数不全 / 单字包含均能命中）**：
  - 背景：1.19.0 搜索框仅精确/包含级匹配，输错字（「西胡」）、字数不全（「杭州宋城景」）、只输特征字（「寺」）时召回不足或全部丢弃；本轮目标示例「搜『寺』弹出『红螺寺』」
  - 后端·多路召回重排：`PoiSearchService.searchByKeyword` 重写为两波召回——第一波原词并行（城市限域路 + 全国路，全国路仅在查询 ≥2 字时发，高德单字全国 `count=0` 不空耗请求）；第一波正得分数不足再发第二波单字切片路（首/尾/中/中偏前最多 4 路，仅城市限域）；每路独立 `mergeFuture`（超时/失败按空结果处理不影响他路），`Merged(item, score, cityRoad, rawRank)` 按规范化名去重、**score desc → cityRoad desc → rawRank desc** 排序，字路 `rank` 偏移 10000 保证同分时沉到原词路之后（避免切片噪声抢占头部）
  - 后端·本地模糊得分 `fuzzyScore`（0 分丢弃）：精确 100 → 双向前缀 85 → 查询⊆候选 75（「寺」→红螺寺）→ 候选⊆查询 65 → **滑窗子串编辑距离**（候选的连续子串与查询 lev=1 → 70，lev=2 且查询 ≥3 字 → 55，使「西胡」命中长名「杭州西湖风景名胜区」、「红螺寻」命中「红螺寺」）→ 整串编辑距离 1 → 70 / 距离 2 且双方 ≥4 字 → 55；单字查询不启用编辑距离（防形近字误配）；结果附 `matchType`（精确/前缀/包含/模糊匹配/近似匹配）与 `fuzzyScore`
  - 后端·结果过滤：搜索路 `SEARCH_EXCLUDE_TYPES` 豁免「地名地址」大类（「西湖」=自然地名;湖泊 是有效搜索目标），但子类剔除道路/交通站点噪声（道路名/交通地名/车站/公交）；`isNoiseName` 噪声词表增 公交站/汽车站/客运站/收费站 + 以「站」结尾或「站(」变体
  - 前端：`PoiSearchItem` 增 `matchType?/fuzzyScore?`；`SwapPanel.mapSearchItem` 将非「精确/包含」的 `matchType` 转为 tags，由既有 `AlternativeCard` 标签 chip 展示「模糊匹配/近似匹配/前缀」
  - 单测：新增 `PoiSearchFuzzyTest` 14 用例（fuzzyScore 各档位边界/滑窗子串/窗口边界/levenshtein/matchTypeOf）
  - 文件：`plan-service/.../service/PoiSearchService.java`、`plan-service/src/test/.../PoiSearchFuzzyTest.java`、`frontend-new/src/{api/types.ts,components/swap/SwapPanel.vue}`

### 验证方式与结果（2026-09-28）

| 项 | 结果 |
|---|---|
| `verify-search.js` E2E（19 基线断言 + **9 条模糊断言**：单字含字/错别字前三命中/多余字前缀/红螺寺命中/score>0 全带 matchType） | **28/28 PASS** ✅ |
| 模糊样例实测：「寺」(杭州) 6 条全含寺 `[包含75]`；「西胡」(杭州) 前三=风景名胜区/西湖/断桥残雪 `[模糊70]`；「杭州宋城景」→杭州宋城 `[前缀85]`；「红螺寻」(北京)→**红螺寺** `[模糊70]`；「宋城」(杭州) 精确前缀 6 条 | ✅ |
| `mvn -o -q test -pl plan-service`（含 `PoiSearchFuzzyTest` 14 用例） | 全绿 ✅ |
| 回归：`verify-d.js` / `verify-variant.js` / `verify-rollback.js` | **22/22、16/16（Jaccard=0.60）、17/17** ✅ |
| 回归中 `verify-d` D-6 断言调整（测试资产）：T3 的 v1 已被 `verify-variant` KEEP=6 清理窗口物理删除，「找 v1/6 活动」改为读**最早现存版本**的详情（200/slogan/活动非空），使断言抗清理、顺序无关 | ✅ |
| 发布管线：`npm run build`（vue-tsc）→ `robocopy /MIR` → stop → `mvn -o package -DskipTests` → start | 8081-8086 全 UP ✅ |
| 模糊搜索 UI 标签展示 | 仅 vue-tsc+build+代码走查，未做人工浏览器点击 ⚠️ |

---

## [1.19.0] - 2026-09-28

### 新功能

- **换景点支持搜索指定地点（搜索框换入）**：
  - 背景：换点此前只能从 16 城静态字典推荐的备选列表中选择，用户无法指定心仪地点
  - 后端·搜索端点：`PoiSearchService.searchByKeyword(keyword, city, limit)` 单次高德 `place/text` 自由关键词查询——与既有 `searchCityDiverse` 固定查询计划区分：未映射类型兜底 `attraction`（用户明确搜索的地点不因类型映射丢弃），住宿/政务/医疗大类与噪声名（售票处/停车场等）仍排除，结果去重、按 limit 截断（1-10）；`PlanController` 新增 `POST /api/plan/poi-search`（`PoiSearchRequest`：`@NotBlank keyword`/`city`/`limit=6`，空词 400），返回项含 `name/type/lat/lng/address/rating/preferredDurationMin/source`
  - 后端·换入带坐标：`ReplaceActivityRequest` 新增 `lat/lng/address`（可空）；`AlternativeService.replaceActivity` 在清旧坐标后，请求携带坐标/地址时直接写入活动 JSON（`lat/lng`、`poi_address/poiAddress`）——`resolveCoord` 首级命中免地理编码，随后既有的**邻接路网重算（前驱→新、新→后继）、当天时间后推、routes 重建、新版本落库**全链路自动生效；不带坐标时行为与原先完全一致（向后兼容）
  - 前端·SwapPanel：「当前景点」卡下新增搜索框——输入 500ms 防抖自动搜索、回车即搜、清除按钮恢复推荐列表；搜索结果以 `AlternativeCard` 复用渲染（地址作为副文本、评分/类型/建议时长照常展示，key 标 `search-*` 区分），选中后走既有 `SwapConfirm → replaceActivity` 确认流，请求附带 `lat/lng/address`；推荐列表/搜索结果/搜索空态三态互斥，关闭面板重置搜索
  - 前端·配套：`planApi.poiSearch`、`PoiSearchItem` 类型、`replaceActivity` 请求类型增 `lat/lng/address`；`TripDetailPage.mapActivityForSwap` 补传 `city: currentTrip.city`（原为 `undefined`，搜索城市限域与备选推荐均依赖）
  - 文件：`plan-service/.../{service/PoiSearchService,controller/PlanController,dto/request/PoiSearchRequest}.java`、`trip-service/.../{service/AlternativeService,dto/request/ReplaceActivityRequest}.java`、`frontend-new/src/{api/{plan,trip,types}.ts,components/swap/SwapPanel.vue,views/TripDetailPage.vue}`

### 验证方式与结果（2026-09-28）

| 项 | 结果 |
|---|---|
| `verify-search.js`（新功能 E2E 19 断言：搜索端点/坐标直写/地址写入/邻接距离重算/时间后推/routes 重建/slogan/换回数据卫生） | **19/19 PASS** ✅ |
| 关键数据：搜索「宋城」返回 5 条（杭州宋城/宋城旅游区/…）；换入后邻接段 **28.3km/81min**（真实路网）、后继时间 11:30→11:30（未到后推阈值）、slogan「给我一天，还你千年」 | ✅ |
| 空关键词校验 | 400 ✅ |
| `mvn -o -q compile/test -pl plan-service,trip-service,common-module -am` | 全绿 ✅ |
| `verify-d.js` / `verify-variant.js` / `verify-rollback.js` 回归 | **22/22、16/16（Jaccard=0.60）、17/17** ✅ |
| 前端管线：`npm run build`（vue-tsc）→ `robocopy /MIR` → `mvn -o package` → 重启 | 8081-8086 全 UP ✅ |
| 搜索框 UI | 仅 vue-tsc+build+代码走查，未做人工浏览器点击 ⚠️ |

---

## [1.18.0] - 2026-09-28

### 重构与功能增强

- **规划后处理管道重构（主/回退路径共用）**：
  - `TripPlanningAgent` 主路径与 LLM 异常回退路径原本各有一段近 125 行、顺序略有差异的后处理序列（易漂移、回退分支易漏改——1.17.0 回退路径漏清洗即为此类缺陷）；抽取 `ActivityStep`（@FunctionalInterface）+ `PlanningContext`（rawInput/places/meals/timeStart/timeEnd/city/pace/variant/excludePois/repairPool 一次性收口），统一为 `runPostPipeline(activities, ctx)` 单一 20 步 `postPipeline()`（sanitize→…→ensureComplete→enforceVariant→correct→humanize→…→applyPaceBudget），`correct`/`humanize`/`ensureComplete` 各步骤内置 try/catch 降级，回退路径外层再套兜底；`fillResult` 组装返回，fallback 标记仅回退置位
  - 文件：`plan-service/.../agent/{TripPlanningAgent,PlanningContext,ActivityStep}.java`

- **VariantSpec 换版规格收口 + 每日基准摘要进提示词**：
  - 新建 `VariantSpec`（`variant`/`excludePois`/`basePlanSummary` + `nonVariant()` 工厂 + `isActive()`），`planDetailedItinerary` 10 参（boolean+List）与 `buildItineraryPrompt` 签名收敛为 9 参（VariantSpec）；`AgentController` 解析请求体 `basePlanSummary`
  - `TripService.collectExcludePois` 升级为 `collectVariantBase(versionId)`（一次遍历）：POI 排除列表不变，新增按天聚合的**基准行程摘要**（`Day1: 西湖 → 午餐·楼外楼 → 灵隐寺；Day2: …`）经 `itineraryRequest.basePlanSummary` 透传；prompt 的 variantBlock 在排除清单后追加「上一版每日行程（须整体避开该组合与顺序）」段——LLM 换版时获得整体布局参照而非仅名称列表
  - 文件：`plan-service/.../agent/VariantSpec.java`（新增）、`{TripPlanningAgent,AgentController}.java`、`trip-service/.../service/TripService.java`

- **换版排除工具类抽取 + 单测补齐**：
  - 新建 `VariantExclusions`（public static：`variantNorm`/`stripDecorations`/`isExcludedName`/`removeExcludedFromPool`/`pickVisitRepair`/`stripStalePoiMeta`），`TripPlanningAgent` 6 个私有实现删除并改 `import static`（调用点零改动）；`ensureCompleteItinerary` 补 `excludePois`/`rawInput` 参数，其 `cityPool` 构建后同样过 `removeExcludedFromPool`——**缺口填充候选源全部贯穿排除**
  - 新增 `VariantExclusionsTest` 14 用例（等值/装饰剥离/短名前缀边界/池过滤/元数据剥离等）
  - 文件：`plan-service/.../agent/VariantExclusions.java`（新增）、`plan-service/src/test/.../VariantExclusionsTest.java`（新增）

- **换版内容差异量化（E2E Jaccard 断言）**：
  - `verify-variant.js` 新增：`Jaccard = 交集/并集 ≤ 0.75` 与 `新增景点 ≥ 1` 两断言（原仅有「集合不全同」弱断言）；清理机制同步加入（见 BUGFIX 1.18.0 数据卫生项）

- **版本对比视图增强（餐次对齐 + 变化点标注）**：
  - `VersionCompareView` 三轮匹配重构：① 按 POI 名（原逻辑）；② 未匹配餐次按「天+餐段槽位」对齐（槽位取 `早餐/午餐/晚餐` 名称词，缺省按开始时间 10:30/14:30/16:30 分界）——**换版换餐厅不再被误判为「移除+新增」两行**，正确显示为同一行的「调整·午餐换店」；③ 剩余才判 added/removed
  - 行归属改为以**旧版的天**为组浏览；状态徽标下方新增变化点小字（`时间`/`D1→D2`/`时长`/`顺序`/`午餐换店`，title 显示完整列表）
  - 文件：`frontend-new/src/components/version/VersionCompareView.vue`

- **启动脚本日志查看入口（-Action tail）**：
  - `start-all.ps1` 新增 `tail` 动作（配合 `-Service <名>` 过滤，缺省全部服务）：`Get-Content -Encoding UTF8 -Tail 60` 强制 UTF-8 解码——日志文件本身是 UTF-8（JVM 已带 `-Dfile.encoding=UTF-8`），PS5.1 缺省按 ANSI 解码会整页乱码；附带修正 `.PARAMETER Action` 文档
  - 文件：`start-all.ps1`

### 验证方式与结果（2026-09-28）

| 项 | 结果 |
|---|---|
| `mvn -o -q compile -pl trip-service,plan-service -am` | ✅ COMPILE OK |
| `mvn -o -q test -pl plan-service,trip-service,common-module -am` | **全绿**（VariantExclusions 14 + SloganService + CityOwnership）✅ |
| `verify-d.js` D 类回归（22 断言，D-2 数据无关化后） | **22/22 PASS** ✅ |
| `verify-variant.js` 换版 E2E（14 原断言 + 2 新 Jaccard 断言 + 数据卫生清理） | **16/16 PASS** ✅（47s 生成 v7；Jaccard=0.50≤0.75，新增南宋德寿宫遗址博物馆，排除生效） |
| `verify-rollback.js` 回滚/fork 回归（含 v1 保护清理） | **17/17 PASS** ✅ |
| 前端管线：`npm run build`（vue-tsc+vite）→ `robocopy /MIR` → `mvn -o package -DskipTests` → 重启 | 8081-8086 全 UP，jar 时间戳 14:13 ✅ |
| `start-all.ps1 -Action tail -Service plan-service` | 语法 0 错、BOM 保留、日志正确输出 ✅ |
| 对比视图 UI | vue-tsc+build 过；餐次对齐/变化点逻辑经代码走查，未做人工点击验证 ⚠️ |

---

## [1.17.0] - 2026-09-27

### 功能

- **创建新版本变体规划（换版：主题不变、内容不同）**：
  - 后端：`POST /api/trips/{id}/plan?variant=true` 触发换版——`TripService.triggerPlanning` 增 `variant` 参数，建新版本前 `collectExcludePois(baseVersionId)` 收集基准版本全部 `poi_name`（「午餐·餐厅」拆出餐厅名），经 `itineraryRequest`（`variant`/`excludePois`）→ `AgentController.planItinerary` 透传 → `TripPlanningAgent.planDetailedItinerary` 新 10 参重载：
    1. `applyVariantExclusions`：排除基准已用 POI，**rawInput 明确提到的景点/餐厅=主题保留**；`applyVariantMealExclusions` 同理清理餐次；
    2. `augmentVariantPlaces`：景点候选不足时补 Amap 多元真实地点，池上探至 `totalDays*maxVisits+8` 并留存 `variantRepairPool`；
    3. variantBlock 提示词硬约束（排除清单+替代要求+「系统会强制替换」告知）；
    4. `enforceVariantExclusions` 输出前硬清洗 + `removeExcludedFromPool` 过滤缺口填充候选池 + 回退路径同加清洗（详见 BUGFIX 1.17.0）
  - 前端：`tripApi.triggerPlan(id, baseVersionId?, variant?)`；`TripDetailPage`「创建新版本」改走换版（`/planning?variant=1`），版本选择器标签加日期+活动数；`PlanningPage` 识别 `variant=1`（完成态不误触发常规重规划、换版专用 toast）
  - 文件：`trip-service/.../{service/TripService,controller/TripController}.java`、`plan-service/.../{controller/AgentController,agent/TripPlanningAgent}.java`、`frontend-new/src/{api/trip.ts,views/TripDetailPage.vue,views/PlanningPage.vue}`

- **版本对比视图重构（VersionCompareView）**：
  - 新组件 `components/version/VersionCompareView.vue`：双版本下拉+换位按钮；**按 POI 名对齐的双栏 diff**（保留/内容变更/新增/移除四态着色+状态徽标列，「此版本无」占位）；统计对比（活动/游览/交通/餐次，带 ±delta 色）；差异摘要 chips；按天分组+日期头；活动名统一经 `normalizeActivities` 修 snake_case 空名 bug（见 BUGFIX 1.17.0）
  - `TripDetailPage` 对比区块替换接入，左右版本 `v-model` 双向联动
  - 文件：`frontend-new/src/components/version/VersionCompareView.vue`（新增）、`frontend-new/src/views/TripDetailPage.vue`

### 验证方式与结果（2026-09-27）

| 项 | 结果 |
|---|---|
| `verify-variant.js`（换版 E2E：触发/版本+1/主题不变/内容不同/对比数据源，14 断言） | **14/14 PASS** ✅ |
| 内容不同关键断言：v5{西湖,灵隐寺,龙井村,河坊街} → v6{西湖,断桥,灵隐寺,雷峰塔,河坊街}（交集3、淘汰龙井村、新增断桥+雷峰塔、主题三景保留） | ✅ |
| `verify-d.js` D 类回归 | **22/22 PASS** ✅ |
| `verify-rollback.js` 回归（回滚/fork 17 断言） | **17/17 PASS** ✅ |
| `mvn -o test -pl common-module,trip-service,plan-service -am` | **全绿**（Slogan 20 + CityOwnership 15）✅ |
| 前端发布管线：`npm run build` → `robocopy dist → gateway static /MIR` → `mvn -o package -DskipTests` → 重启 | 8081-8086 全 UP，产物 `TripDetailPage-2mSYR1pS.js` ✅ |
| 对比视图 UI | 无浏览器工具，仅经 vue-tsc 类型检查+build+接入代码走查验证（未做人工点击验证）⚠️ |

---

## [1.16.0] - 2026-09-26

### 功能增强

- **景点个性化签名（根据景点生成 slogan）**：
  - `SloganService` 重写为三级生成策略：
    1. **词库优先 + 同景多签**：约 200 个景点条目，热门景点配 2-3 条签名，按 `(tripId + 景点名)` 哈希稳定轮换 —— 同一行程内恒定、跨行程各不相同（如「西湖」在两行程分别出「一半湖山一半城」「淡妆浓抹总相宜」）；
    2. **名称特征模板**：词库未覆盖时按名称意象套模板（寺庙/水域/海岛/山岳/塔楼/街巷/公园/博展馆/商圈 + 餐饮专用），模板含 `{name}`；按**最长命中关键词**选择特征组，避免「青岛老城区」被「岛」、「小鱼山公园」被「山」误判；
    3. **类型兜底**：`TYPE_SLOGANS`（原逻辑保留）
  - 新增 `isGeneric(slogan, poiName)` 泛化文案判定（空/等于名称/「动词+名称」结构/动词开头或 ≤6 字且未提及景点），读取链路据此用景点专属签名**替换** LLM 泛化 notes；已提及景点或已命中词库的文案保留
  - 词库扩容：新增八达岭长城、慕田峪长城、三潭印月、苏堤、楼外楼、青岛（栈桥/八大关/五四广场/崂山/青岛啤酒博物馆/奥帆中心）、自由活动、市区漫步等条目
  - 接入点：`TripVersionService.toResponse`（所有读路径，seed=tripId，不落库、读时生成）、`AlternativeService.replace`（换点后 seed=tripId）
  - 展示沿用现有三处：时间轴活动卡、地图侧栏、分享页（前端零改动）
  - 文件：`trip-service/.../service/{SloganService,TripVersionService,AlternativeService}.java`

### 验证方式与结果（2026-09-26，`slogan-check.js` 打 API 实测）

| 场景 | 结果 |
|------|------|
| T3 杭州两次请求 | 签名完全一致（`稳定性: PASS`）✅ |
| 两行程同景点 | 西湖「一半湖山一半城」vs「淡妆浓抹总相宜」，张生记「张生记，一城风味落座」vs「把这座城市吃个明白」（同景多签轮换）✅ |
| 词库命中 | 楼外楼→「一桌杭帮菜，半部西湖史」、八大关→「八大关的秋，落叶成毯」、青岛栈桥（模糊匹配）→「飞阁回澜，青岛的序章」✅ |
| 特征模板 | 老城巷子→「烟火气里的老城巷子」、沙滩→「沙滩，海风正好」、小鱼山公园→「小鱼山公园，方寸之间的雅致」、青岛老城区→「烟火气里的青岛老城区」✅ |
| 泛化替换 | 「游览西湖」「漫步」「观海景」「了解啤酒文化」等 LLM 泛化 notes 全部被景点专属签名替换 ✅ |

本轮无前端改动（签名沿用既有展示位），仅后端 `mvn -o package -DskipTests` + 重启 6 服务验证。

---


### 新功能 / 功能增强

- **A. 餐厅推荐增强（静态优先 + 在线补评分）**：
  - 新增 `RestaurantSearchService`：先按 POI 名称精确检索，失败再按坐标就近检索（`types=050000` + `citylimit`），统一 350ms 节流 + 结果缓存，评分 < 4.0 的餐厅过滤、无评分的保留
  - `TripPlanningAgent` 两处接入 `annotateMealRestaurants`（初版规划 + 重规划），并在 coordMap 预置餐厅坐标供离线兜底
  - `TripService` 将 `rating` / `cost` 从解析结果透传到活动持久化
  - 文件：`plan-service/.../RestaurantSearchService.java`、`plan-service/.../agent/TripPlanningAgent.java`、`trip-service/.../service/TripService.java`

- **B. 反馈重规划链路修复与增强（版本原地更新）**：
  - `InternalController` 新增 `GET /internal/trips/{tripId}/versions/{versionId}` 版本详情；`createVersion` 支持 `targetVersionId` 原地更新目标版本（更新状态/parent、删除旧活动行重插），不再重复建版
  - worker 新建 `TripServiceClient` 真实加载基础版本；`ReplanJobConsumer.parseFeedback` 规则化解析用户反馈（删除动词+活动名→`REMOVE`、「X 多待 N 分钟」→`MODIFY_DURATION`），空变更早退；`persistResultToTarget` 原地落库到目标版本
  - `IncrementalReplanner`：`REMOVE` 从变量集剔除真实生效；`MODIFY_DURATION` 落地新时长且**顺延最新结束时刻与下游活动时间窗**；`ADD` 结果合并重排序；城市校验优先取基础问题城市
  - `ResultPersistService` 写入真实 `poiAddress`、数值 `lat`/`lng`、评分/人均，活动行插入抽公共方法
  - 文件：`trip-service/.../controller/InternalController.java`、`planning-worker/.../consumer/ReplanJobConsumer.java`、`planning-worker/.../solver/IncrementalReplanner.java`、`planning-worker/.../service/{TripServiceClient,ResultPersistService,PlanningOrchestrator}.java`

- **C. 跨城污染治理（城市优先级链）**：
  - `CityOwnershipUtils.extractCity` **删除「默认北京」兜底**，识别失败返回空；城市优先级：表单 `city` > LLM 解析 `city` > 正则提取 > 空（`citySource: form/llm/regex/unknown`）
  - `PlanService.parseInput` 增加 `cityHint` 参数与 `summary`/`quote` 摘要；`TripService` 建行程/触发规划传递 city 并落库 `parsedInput`；`CityOwnershipUtils.filterActivitiesForCity` 过滤非目标城市活动；城市为空时路线引擎回退 `drive`
  - 文件：`plan-service/.../util/CityOwnershipUtils.java`、`plan-service/.../service/PlanService.java`、`trip-service/.../service/TripService.java`、`planning-worker/.../consumer/ReplanJobConsumer.java`

- **D. 解析摘要 / 评分 / 距离前端呈现**：
  - 新建 `AiParseSummaryCard.vue`（城市 chip、识别失败黄条、摘要、景点/用餐 chips、原文引用、原始描述折叠），接入详情页与规划页；`TripForm.vue` 新增「目的城市」输入
  - `ActivityCard` 展示 `★评分 / 人均` 与地址行；`TripMap`/`MapPanel` info 信息增加评分与地址；`types.ts` 扩展 `ParsedInput{city,summary,citySource,quote}`、`Activity{rating,cost}`、`CreateTripRequest{city}`；中英 i18n 新增 `tripCreate.cityLabel/cityPlaceholder`、`parseSummary.*`
  - 文件：`frontend-new/src/components/trip/AiParseSummaryCard.vue`、`TripForm.vue`、`views/{TripDetailPage,PlanningPage}.vue`、`components/{timeline/ActivityCard,map/TripMap,map/MapPanel}.vue`、`api/types.ts`、`i18n/{zh-CN,en-US}.json`

### 验证方式与结果（2026-09-26，全量 E2E `verify.js`）

| 类别 | 场景 | 结果 |
|------|------|------|
| C | T1 无城市词文本 | `city=""` `citySource=unknown`，北京系活动 0 ✅ |
| C | T2 表单 city=青岛（文本含北京） | `city=青岛` `citySource=form`，北京系活动 0 ✅ |
| C | T3 杭州 | `city=杭州` `source=form`，summary/quotes 齐全 ✅ |
| A | 午餐·楼外楼 | `rating=4.5 cost=104.00 addr=四季青街道之江路1078号…` lat/lng 正确 ✅ |
| A | 晚餐·张生记 | 评分缺失保留（AMap 杭州无该餐饮 POI，属数据缺口）✅ |
| B | 反馈「不去灵隐寺，西湖多待60分钟」 | 202 建目标版本、trip 回 `completed`、版本数 1→2、`灵隐寺残留=0`、西湖 `durationMin=120→180`、无坐标活动 0 ✅ |
| D | 前端资源 | `npm run build` 通过，robocopy 后 gateway 静态包含 `parseSummary/cityPlaceholder/人均/原始描述` 等新文案 ✅ |

部署链执行：`npm run build` → `robocopy frontend-new\dist gateway\src\main\resources\static /MIR` → `mvn -o package -DskipTests` → `restart-all.ps1`，6 服务端口 8081-8086 全部就绪、gateway 返回 200。

---


### 增强

- **登录 / 注册失败提示（修复见 `docs/BUGFIX.md` 同版本条目）**：
  - 表单级错误横幅：提交按钮上方显示后端错误文案（如「邮箱或密码错误」「邮箱已被注册」），并同步弹 Toast；不再出现「整页刷新把提示刷掉」或英文 `Network Error`
  - 字段级校验错误回填：后端 `error.details` 直接标红到对应输入框（邮箱格式、密码规则、两次密码不一致等）
  - 密码规则前置提示：注册页密码框下方常显规则（8 位以上、大小写+数字+特殊字符 `@$!%*?&`），前端校验与后端 `RegisterRequest` 完全一致；登录页不再用「至少6个字符」误拦（后端仅要求非空）
  - 输入即清除该字段错误与横幅，切「登录/注册」Tab 时重置

- **API 错误统一归一化**（`frontend-new/src/api/index.ts`）：
  - 新增 `ApiError`（`message` / `code` / `status` / `details`）+ `toApiError()`，所有请求失败（含网络异常、超时、无 body 的 4xx/5xx）统一转成中文可读错误
  - 认证类接口的 401 不再触发 Token 刷新与强制跳转；Token 刷新成功后排队请求正确放行重试
  - i18n 新增 `auth.passwordHint` / `auth.loginFailed` / `auth.registerFailed`（中英双语）

### 验证结果（2026-09-26）

| 验证项 | 结果 |
|--------|------|
| `npm run typecheck`（vue-tsc） | PASS（0 错误） |
| `npm run build` | PASS，2.41s，`assets/index-4MXxMLi4.js` |
| `robocopy dist → gateway/static /MIR` | PASS，exit=3 |
| `start-all.ps1 -Action restart -Build -NoBrowser` | PASS，6 服务全部 UP，网关 200 返回新 bundle |
| 注册成功 / 重复邮箱 / 弱密码 / 密码不一致 / 错误密码 / 正常登录 | PASS ×6（200 / 401 / 400 / 401 / 401 / 200） |

## [1.14.0] - 2026-09-25

### 新增

- **行程卡片封面图（联网搜索图片）**：首页「我的行程」与公开行程卡片的纯色渐变头图升级为按「城市 + 景点」联网检索到的真实图片，检索失败/加载失败时原样保留城市渐变底图。
  - 关键词由 `city + landmarks` 生成（如 `杭州 西湖 雷峰塔`），同一关键词全站只请求一次
  - 新增后端接口 `GET /api/trips/covers?kw=<关键词>` → `ApiResponse<CoverResponse>`（`{keyword,url,title,width,height,source}`，无结果时 `data` 为 `null`）

- **trip-service 封面图搜索服务**：
  - 新增 `controller/CoverController.java`、`service/CoverImageService.java`、`dto/response/CoverResponse.java`
  - 数据源：百度图片 `acjson` 接口（免 key、UTF-8/GBK 自适应解码），**优先选横版大图**（宽高比 ≥1.2、≥400×300）适配卡片比例
  - 缓存：Redis 成功结果 7 天、明确无结果 1 小时、反爬/网络抖动等临时失败 2 分钟（`trip:cover:<md5(kw)>`）；同关键词并发用 `CompletableFuture` 去重
  - 安全：关键词去控制字符 + 60 字上限；图片直链**域名白名单**（`*.baidu.com` / `*.bdimg.com` / `*.bdstatic.com`，仅 http/https）阻断 SSRF；外部请求 5s 连接 / 8s 总超时；任何异常降级为「无封面」
  - 接口对网关与 trip-service 两侧 `permitAll`（未登录的分享页同样能出封面）
  - `trip-service/application.yml` 增加 `logging.file.name: trip-service-app.log`（cmd 重定向缓冲导致 stdout 日志滞后，改为直接落盘便于排查）

- **前端封面图渲染**：
  - 新增 `composables/useTripCover.ts`：关键词生成 + 模块级缓存 + 并发去重 + 竞态令牌
  - `TripCard.vue` 封面区叠加 `<img>`（`object-cover` + 渐入 + `loading="lazy"` + `referrerpolicy="no-referrer"`），`@error` 回退渐变底图；渐变层与原有暗色遮罩层级保持不变
  - `api/trip.ts` 新增 `searchCover(kw)`；`types.ts` 新增 `CoverResponse`

### 设计要点 / 关键坑位

| 坑 | 结论 |
|----|------|
| Java HttpClient 请求百度图片接口返回 `Forbid spider access` | **必须带 `Accept-Language: zh-CN,zh;q=0.9`**（缺该头即判定爬虫）；HTTP/1.1 与 HTTP/2 均可 |
| 响应编码不固定 | 先按 `Content-Type` charset，再严格 UTF-8 解码，失败回落 **GBK**（用 `CodingErrorAction.REPORT` 探测，避免替换字符） |
| 结果中偶发 JSON 非法转义 `\'` 导致 Jackson 解析中断（如 `hangzhou`） | 先 `body.replace("\\'", "'")` 清洗；仍失败则**正则兜底**抽取 `middleURL/hoverURL/thumbURL` |
| 临时失败若写 1 小时失败缓存会毒化关键词 | 反爬/网络抖动写 **2 分钟**短缓存，仅「确定无结果」写 1 小时 |
| 运行时 Redis 是宿主机 `D:\Redis` 服务（db0），**不是** docker `trip-planner-redis` | 应用连 `localhost:6379` 命中本机服务；`spring.redis.*` 在 Boot 3 已不绑定，实际用默认 db0，排查时勿在 docker redis 里找 key |

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `npm run typecheck`（vue-tsc） | PASS | EXIT=0 |
| `npm run build` | PASS | 2.45s，`TripCard.vue…-D6FexXQk.js`、`trip-EK5TLvNr.js` |
| `robocopy dist → gateway/static /MIR` | PASS | exit=3，dist 与 static `index.html` 时间戳一致 18:20:58 |
| `mvn -o package -DskipTests` | PASS | MVN_EXIT=0（含 trip-service 新控制器/服务与 gateway 安全配置） |
| `restart-all.ps1` | PASS | auth/trip/plan/notification/gateway UP（worker DOWN 为已知遗留） |
| 网关 `/` | 200 | 引用 `assets/index-DjY8yiFn.js`，与构建产物一致 |
| 静态包内含新逻辑 | PASS | `trip-EK5TLvNr.js` 含 `/api/trips/covers`；TripCard chunk 含 `coverLoaded` |
| `GET /api/trips/covers`（经网关 8086） | PASS×7 | `杭州 西湖 雷峰塔` / `杭州西湖` / `西湖` / `灵隐寺` / `河坊街` / `成都 宽窄巷子` / `北京 故宫` 均返回 `img0-2.baidu.com` 直链 |
| 图片直链可用性 | PASS | `Referer: http://127.0.0.1:8086/` 抓取 → 200 `image/jpeg` 75KB |
| 缓存生效 | PASS | 二次请求日志 `封面图命中缓存 keyword=杭州 西湖 雷峰塔` |
| 服务日志 | PASS | `trip-service-app.log` 中 `封面图搜索成功 keyword=…, url=…`（成功/命中/失败三类均可追溯） |

---

## [1.13.0] - 2026-09-25

### 新增

- **点击规划地点 → 地图跳转定位节点**（行程详情页双向联动的反向链路）：
  - 现有链路为地图 → 时间线（`TripMap` 点 marker `emit('selectActivity')` → `TripDetailPage.scrollToActivity` 滚动高亮卡片），本次补齐 **时间线 → 地图**
  - 交互：点击活动卡片（或卡片右上角「在地图中查看」按钮）→ 地图平移并缩放至该节点（16 级）、marker 放大高亮、打开信息气泡、右侧 MapPanel 展开；移动端（<640px）自动弹出浮层地图；对比视图自动切回时间线视图以露出地图
  - 卡片当前聚焦态：`border-brand-400 + ring-2 ring-brand-500` 描边高亮（`active` prop）

### 涉及文件（均为前端）

| 文件 | 改动 |
|------|------|
| `frontend-new/src/components/map/TripMap.vue` | 新增 `focusActivity` prop、`focusOnMap()`、`panToCoord()`、`geocodeAndPan()`、focus watch、`initMap` 末尾补跳转；`fitMapView()` 与 `activities` watch 增加 `lastFocusAt` 1.5s 保护，防止跳转后被 `setFitView`/`setCenter` 覆盖；无坐标节点现场地理编码并回填 marker |
| `frontend-new/src/components/timeline/ActivityCard.vue` | 新增 `focus` emit、`active` prop、整卡点击、hover「在地图中查看」按钮（保留原交换按钮） |
| `frontend-new/src/components/timeline/DayTimeline.vue` | 新增 `focus` emit 透传、`activeSeq` prop（按 `activity.id ?? seq` 比对） |
| `frontend-new/src/views/TripDetailPage.vue` | `focusActivity` 状态 + `handleFocusActivity()`；`DayTimeline` 绑定 `:active-seq` / `@focus`；三处 `TripMap`（桌面右栏、移动浮层、全屏地图）统一绑定 `:focus-activity` |

### 设计要点

- 聚焦**不**反向 `emit('selectActivity')`，避免与地图→时间线链路互触发循环
- `lastFocusAt` 时间戳屏蔽 `fitMapView()` 与 `activities` watch 中的 `setCenter`，覆盖异步地理编码/路线渲染导致的视野重置
- 地图未挂载（移动端浮层未开、tab 未切到地图）时仅记录状态，`initMap` 完成后据 `props.focusActivity` 补执行跳转
- 无坐标节点走 `planApi.geocode` 现场解析，失败回落城市中心

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `npm run typecheck`（vue-tsc） | PASS | EXIT=0 |
| `npm run build` | PASS | vite 构建 2.32s，`TripDetailPage-BtmizeLl.js` 94.58 kB |
| `robocopy dist → gateway/static /MIR` | PASS | robocopy exit=3（已复制），assets 时间戳 17:53 |
| `mvn -o package -DskipTests` | PASS | MVN_EXIT=0（先杀 java 进程避免 jar 锁） |
| `restart-all.ps1` | PASS | auth/trip/plan/notification/gateway UP（planning-worker DOWN 为已知遗留） |
| 网关 `/` | 200 | 引用新资源 `assets/index-D6-JSnQt.js`，chunk 文件存在 |
| 新逻辑随包可见 | PASS | `TripDetailPage-BtmizeLl.js`（内联 TripMap/ActivityCard）含 `setZoomAndCenter`、`在地图中查看`、`点击在地图中定位`、`focusActivity` |
| 后端回归 | 未触发 | 本次纯前端改动，未触及 plan/trip 服务 |

---

## [1.12.1] - 2026-09-25

### 新增

- **活动频率选择器前端正式上线**：
  - 创建页「活动频率」三选一卡片（紧凑 8~10 小时/天·约 4~6 个景点 / 适中 6~8 小时/天·约 3~5 个景点 / 宽松 3~5 小时/天·约 2~3 个景点）随构建产物对外可见、可点选，选中值随 `CreateTripRequest.pace` 提交
  - 该 UI 于 1.12.0 已在源码完成（`TripForm.vue` + `i18n/zh-CN.json` + `i18n/en-US.json`），1.12.1 才进入部署产物

### 变更

- **前端构建产物同步流程固化**：
  - `frontend-new/dist` 与 `gateway/src/main/resources/static` 必须成对更新，缺一即出现“源码有、页面无”的旧包现象
  - 本次执行链：`npm run build`（vue-tsc + vite）→ `robocopy dist → gateway/src/main/resources/static /MIR` → `mvn -o package -DskipTests` → `restart-all.ps1`（gateway 从 jar 内 `classpath:/static` 出页面，必须重打包重启）

### 流程约束（写入 AGENTS.md §8.3）

- **此后所有功能变化一律当场写入版本文档**：功能新增/增强记入 `docs/CHANGELOG.md` 次版本号；Bug 修复另记 `docs/BUGFIX.md` 同一版本号；版本号按上文规则递增，条目追加在文件最上方

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `npm run build` | PASS | `vue-tsc --noEmit` + vite 构建成功，2.30s |
| 网关 `/` | 200 | 引用新资源 `assets/index-Cy0ib_yl.js`（旧包为 9:56 版本） |
| index chunk i18n | PASS | 含 `活动频率`、`8~10 小时/天` 文案 |
| `assets/TripCreatePage-CYzmv36z.js` | 200 | 含 `compact` / `moderate` / `relaxed` 三档与 `pace` 提交字段 |
| gateway static 同步 | PASS | `/MIR` 镜像后 `index.html` 与 `assets` 时间戳一致（17:27） |

---

## [1.12.0] - 2026-09-25

### 新增

- **活动频率（pace）三档**：
  - 档位定义 `plan-service/.../plan/constant/TripPace.java`：

    | 档位 | code | 每日游览时长 | 每日景点数 |
    |------|------|-------------|-----------|
    | 紧凑 | `compact` | 8~10 小时（4~6 景点） | 4~6 |
    | 适中 | `moderate` | 6~8 小时（3~5 景点） | 3~5 |
    | 宽松 | `relaxed` | 3~5 小时（2~3 景点） | 2~3 |

  - 口径：**游览时长不含用餐与交通**；未知值回落 `moderate`；每日 21:00 硬上限；小时为“约”（E2E 下限容差 −30 分钟、景点数上限容差 +2）
  - `TripPace.of(code)` / `getMinDailyActivities()`（= minVisits+2）/ `getHoursLabel()` / `promptLine()`

- **数据库与 trip-service**：
  - `init-sql/01_schema.sql` `trips` 增列 `pace VARCHAR(16) NOT NULL DEFAULT 'moderate'`（宿主机 3306 已执行 `ALTER TABLE trips ADD COLUMN pace`）
  - `Trip` / `CreateTripRequest` / `UpdateTripRequest` / `TripResponse` 增加 `pace`；`TripService.normalizePace()` 白名单归一，`createTrip` / `updateTrip` / `triggerPlanning` / `toResponse` 全链路透传
  - `AgentController` `/parse` `/plan-itinerary` 接收 `pace` 并写入日志（`收到解析请求: ..., pace=relaxed`）

- **plan-service 规划按档约束（`TripPlanningAgent`）**：
  - `parseInput` 增加 5 参重载（默认 `moderate`）；`buildParsePrompt(..., pace)` 注入「活动频率硬约束」段，规则 5 景点数改为 `minVisits+2 ~ min(maxVisits+3, 8)`，返回结果带 `pace`
  - `supplementPlacesFromPoi(..., pace)` 解析阶段补点目标按档：compact `min(8, max(6, d+3))`、moderate `min(8, max(5, d+3))`、relaxed `min(6, max(4, d+1))`
  - `planDetailedItinerary` 6/7 参委托 8 参 `(..., explicitCity, paceCode)`，主/回退两路径结果均带 `pace`
  - `buildItineraryPrompt(..., pace)` 注入节奏段（`minPerDay` / `minVisits` / `maxVisits` / `minTotal` 参数化）；`planSingleDay(..., pace)` 新增规则 10「节奏硬约束」
  - `ensureCompleteItinerary(..., pace)`：`minPerDay = pace.getMinDailyActivities()`，补齐条件加入 `visitMinutes < minHours*60` 与 `cursor < 21:00`；开头对**紧凑档正餐压缩到 60 分钟**腾时间

- **节奏缺口填充与预算裁剪**：
  - `fillPaceGaps` / `fillDayGaps` / `buildCityPool` / `pickUniquePlace` / `setDurationMinutes` / `freeWindows`：结构定稿后按档补每日游览时长与景点数，候选耗尽即停、不产占位名
  - **阶段一（插入）**：`pickUniquePlace` 先 `places` 后 `cityPool`，逐候选做 `samePlace` 校验并登记 `usedNames`；起点顺延量取上一活动真实 `travelTimeMin`（兜底 30 分钟），避免插入后被推过 21:00；景点数达 `maxVisits` 即停止插入
  - **阶段二（延展）**：窗口装不下新活动时，延长其前面的**游览**活动（预留真实路程 + 30 分钟起），不新增交通、不推后当天结束时间
  - `applyPaceBudget(activities, pace)`：**时长 + 景点数双上限**裁剪，从最靠后且非 `must` 的 visit 裁起，**绝不裁午晚餐**，直到 `≤ maxHours*60` 且 `≤ maxVisits`（不低于 `minVisits`）
  - 主/回退路径收口：`fillPaceGaps` → `applyPaceBudget` → 真实路网 → 人性化 → `fixTimeOverlaps` → **二次补时（最多 3 轮，`belowPaceFloor` 判停）** → **最终 `applyPaceBudget` 收口**

- **前端**：
  - `frontend-new/src/api/types.ts`：`Trip.pace` 必填、`CreateTripRequest.pace?`、`UpdateTripRequest.pace?`
  - `TripForm.vue`：默认 `moderate`，三选一卡片（图标 + 小时区间 + 景点数），payload 携带 `pace`，文案走 i18n
  - `i18n/zh-CN.json` / `en-US.json`：`tripCreate.pace.*`（label/hint/compact/moderate/relaxed 及 Hours/Visits）
  - `TripDetailPage.vue` `handleDuplicate` 补 `transportMode` + `pace`

- **验证脚本**：`e2e-pace.js`（三档各建一条 2 日成都行程，校验 `db.pace`、每日游览时长/景点数区间、占位名、午晚餐、相邻时间约束、≤21:00）；支持 `PACE=compact|moderate|relaxed` 单档运行

### 优化

- **`fixTimeOverlaps` 增加 `pullDayLeft()` 前移回收**：处理超 21:00 之前先按 `start_i = end_{i-1} + travel_{i-1}`（用餐不早于作息窗口 7:30/11:30/17:30、首活动不早于 08:00）整体前移当日活动，回收 LLM 留下的空隙，减少因顺延溢出而整条裁掉的活动
- **`NON_PLACE_PHRASES` 扩充**：新增「行程结束 / 结束行程 / 结束」，`结束(57)` 这类 LLM 收尾占位不再被计为游览活动虚增时长（由 `replaceNonPlaceVisits` 换成真实地点或剔除）
- 新增诊断日志：`活动频率预算 dayN: 游览 X 分钟 / Y 个景点, 上限 A 分钟 / B 个景点`、`dayN 缺口填充: 无可用插入窗，窗口=...`、`dayN 缺口延展: 「...」延长到 N 分钟`、`二次节奏补时: 每日游览 X -> Y 分钟`

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `e2e-pace.js`（三档全量） | PASS ×3 连续 3 轮 | 紧凑 7.6~8.3h、适中 6.0~6.7h、宽松 4.0~4.5h，景点数均落在各档区间，全部 ≤21:00、午晚餐齐全、时间约束 0 违例 |
| `e2e-dedupe.js` | PASS | 杭州 1 日 6 活动，无重复/无占位/无跨城 |
| `e2e-distance.js` | PASS | `RECALC_OK`，替换后邻接段全部重算 |
| `e2e-diversity.js` | PASS | 大理 4 日 5 类别、4 天午+晚齐全、0 占位 |
| `check-time.js` | FAILURES=1 | 失败项为 `e2e-distance.js` 故意把「龙井村」换成远端「千岛湖」造成的夹具数据，非回归 |
| `mvn -o package -DskipTests` | PASS | 后端全量编译打包通过 |
| `npm run typecheck` | PASS | 前端 `vue-tsc --noEmit` 通过 |

---

## [1.11.0] - 2026-09-25

### 新增

- **高德 POI 文本搜索接入（`PoiSearchService`）**：
  - 新增 `plan-service/src/main/java/com/tripplanner/plan/service/PoiSearchService.java`
  - `MapApiConfig` 新增 `amap.place-url`（`https://restapi.amap.com/v3/place/text`）与 `getAmapPlaceUrl()`
  - `plan-service/application.yml` amap 段新增 `place-url` 配置项
  - 并行分 8 类查询：博物馆/纪念馆、步行街、夜市、公园、历史街区、文化馆、美术馆、名人故居（`QueryPlan` 列表）
  - 每次查询 3s 超时（`QUERY_TIMEOUT`），4 线程守护线程池 `poiExecutor`
  - 城市中心 `≤20km` 半径过滤（`MAX_DISTANCE_KM`），过滤县郊 POI
  - POI 名称长度限制 2~20 字符，过滤噪声结果
  - 结果按城市缓存 30 分钟（`CACHE_TTL_MS`，`MAX_CACHE_SIZE=200`）
  - `durationOf(type)` 给出建议游览时长：博物馆 120 / 步行街 90 / 夜市 90 / 公园 60 / 其它 120

- **解析阶段地点多元化补充**：
  - `TripPlanningAgent.supplementPlacesFromPoi()` + `addIfAbsent()`：解析结果不足目标数量时，按 `museum > shopping > park > temple > scenic > other` 优先级补入真实地点
  - 目标数量 `target = min(8, max(5, totalDays + 3))`，按日期跨度自适应
  - 补充后仍走 `filterPlacesByCity` + `dedupePlaces`，日志输出 `POI 补充后: N 景点 (补前 M), 新增: [...]`

- **规划兜底候选池注入 POI**：
  - `ensureCompleteItinerary()`：静态城市池不足 12 条时注入 `searchCityDiverse(city, 14)`，日志 `完整性兜底城市候选池: N 静态 + POI 补充后 M 条`
  - `planSingleDay()`：`placesInfo` 剩余不足 3 时注入 `searchCityDiverse(city, 8)`（排除已用名称）

- **非地点占位活动替换**：
  - 新增 `replaceNonPlaceVisits()` / `isNonPlaceVisit()` / `NON_PLACE_PHRASES`
  - 命中「返回酒店 / 返程 / 去机场 / 休息 / 自由活动 / 市区漫步 / 待定景点 / `(n-m)` 后缀」等占位文本的 visit 活动，替换为候选池中未使用的地点；无候选则剔除
  - 主路径与回退路径（`sanitizeActivityNames` 之后）均已接入

- **景点替换后按真实路程顺延**：
  - `trip-service` `AlternativeService.replaceActivity()` 新增步骤 `resequenceDayByTravel()`，在 `recomputeAdjacentTravel()` 之后重排同天后续活动的 `scheduled_start` / `scheduled_end`
  - 配套静态助手 `firstNonBlank` / `numToInt` / `parseClock` / `clock`

- **回归验证脚本（仓库根目录）**：
  - `e2e-dedupe.js`：杭州一日行程，校验无重复、无占位、无跨城、必备餐厅存在
  - `e2e-distance.js`：校验真实距离矩阵 + 替换后邻接路段重算（`RECALC_OK`）
  - `e2e-diversity.js`：大理 4 日行程，校验类别多样性、每餐次、时间约束、21:00 收口
  - `check-time.js`：扫描近 2 小时 `trip_versions`，批量校验占位名/餐次/时间/21:00
  - `showtrip.js <tripId>`：打印指定行程的活动明细

### 优化

- **城市景点池扩容**：
  - `TripPlanningAgent.CITY_ATTRACTIONS` 由 5 城扩至 **19 城**（北京/上海/杭州/成都/西安/大理/丽江/三亚/厦门/重庆/苏州/南京/广州/长沙/武汉/哈尔滨/青岛/昆明/洛阳），各城补入博物馆、商圈、公园类地点
  - `CITY_RESTAURANTS`：大理扩至 8 家、丽江 6 家
  - `AlternativeService.CITY_ATTRACTIONS`（trip-service，16 城）：苏州、重庆、厦门、三亚、大理、丽江、武汉、哈尔滨补入 museum 类型条目及购物点

- **提示词多元化约束**：
  - `buildParsePrompt` 规则 5：5~8 个真实地点、至少 1 个人文/文化类、至少 1 个商圈/夜市/步行街类、禁止占位名
  - `planSingleDay` 规则 5：地点必须是具体真实地名，禁止「自由活动」「市区漫步」
  - `buildItineraryPrompt` 规则 5：地点不足时用真实地名补齐，`name` 必须是具体真实地点

- **内置餐厅坐标优先于高德 API**：
  - `resolveNodeCoord()` 先查 `lookupRestaurantCoord()`（手工校准坐标），命中即返回，不再走 API 泛匹配
  - 新增 `stripPlaceSuffix()`：去掉名称尾部城市消歧括号，`南京大牌档(杭州)` → `南京大牌档`
  - 城市解析 API 调用改用 `stripPlaceSuffix(geoName)`

- **POI 半径阈值收紧**：`MAX_DISTANCE_KM` 由 35km 调整为 20km，剔除县城 POI（如「宾川中心1号商业步行街」）

### 变更

- 距离矩阵节点上限维持 `target = min(8, max(5, totalDays + 3))`
- 后处理链顺序固定为：`sanitizeActivityNames` → `replaceNonPlaceVisits` → 城市过滤 → `dedupeVisitActivities` → 归一化 → `correctActivitiesWithRealData` → `fixTimeOverlaps`（**始终为最后一步**），主路径与回退路径一致

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `e2e-dedupe.js` | PASS | 杭州 1 日 5 活动，无重复/无占位/无跨城 |
| `e2e-distance.js` | PASS | `RECALC_OK`，替换灵隐寺→雷峰塔后邻接段全部重算 |
| 大理 4 日行程 | PASS | 21 活动，5 个类别（scenic/temple/museum/shopping/park），4 天午+晚齐全，0 占位，全部满足 `start ≥ prevEnd + travel`，全部 ≤21:00 |
| 时间约束 SQL 巡检 | PASS | 近期行程版本全部满足时间与餐次约束 |

- 规划耗时基线：1 日行程 ~40s；4 日行程 ~130-158s（瓶颈为单次主 LLM 调用 ~118s，POI 检索 ~0.8s，距离矩阵 ~3s）

---

## 后续计划

- [ ] `InlinePlanningService`（trip-service 故障兜底）城市池补入 museum 类条目
- [ ] `RouteService` 偶发 `高德公交规划失败: Index 0 out of bounds for length 0`（已有驾车兜底，待根治）
- [ ] 天气 API 集成，用于户外活动推荐
- [ ] 用户偏好学习，基于规划历史
- [ ] rawInput 天数与前端日期区间不一致时的确认交互
