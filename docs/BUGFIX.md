# Bug 修复日志

TripForge 的所有 Bug 修复都会记录在此文件中。

**重要：每次 Bug 修复都必须在此文件中记录，包含问题描述、原因分析、修复方案、验证方式。新记录追加在最上方，格式为 `## [版本号] - YYYY-MM-DD`。**

**版本号规则**：与 `docs/CHANGELOG.md` 使用同一版本序列（基线 1.11.0，功能新增 → 次版本 +1，修复/部署类 → 修订号 +1）。同一版本的修复与功能条目版本号保持一致，便于对齐查阅。

> **基线说明**：本文件于 2026-09-25 重置，此前的历史修复记录已按要求舍弃，不作为追溯依据。
> 当前基线版本为 **1.11.0**，其后所有修复以此为起点递增。

---

## [1.35.0] - 2026-10-04

### 修复（餐段距离/时间失真：终末补餐写入的旅行切分估算值不再被真实路网校正）

- **现象**：用户反馈「现在有一些距离时间仍不准确，**特别是餐食部分的距离时间**」。DB 实测两类失真：
  1. **伪造里程**：`meal travel=15min/1km`、`travelDistanceKm = travelMin*0.067`（宁夏 trip 日志：沙坡头 → 餐厅直线 **144.7km**，却显示 `15min/20km`；宁夏天另有 `15min/1km`）
  2. **串城坐标**：海南 trip `04d5d712`（city=海南/海口）景点全在海口(20.0,110.3)，餐点却解析到**三亚**(18.22,109.52)——`地理编码成功: 海南鸡饭店 -> (109.51989,18.223123)`，展示 `11min/2.8km` 实为 **214km**
- **根因**：
  1. 管道末位 `ensureDailyMealsStep`(2222) / `checkMealWindowsStep`(2224) 位于 `correctStep`(2211) **之后**，补餐/出窗重放写入的 `meal.travelTimeMin = p.travelMin()`、`travelDistanceKm = travelMin*0.067`、`prevAct.splitTravel = min(prevTravel,15)` 之后**没有任何步骤再用真实路网校正**
  2. `coordBefore` / `annotateMealRestaurants` 调用发生在 `correctStep` **之前**，此时活动尚无 lat/lng → 参考点恒 `null` → 挑餐厅退化为「全市候选」，就近推荐失效；`GeocodeService.CITY_CENTERS`(37 城)**缺 海口/银川/海南/宁夏** → 跨城毒坐标守卫 `center==null → return true` 整体失能
  3. `RestaurantSearchService.queryPois` 只用 `/v3/place/text`（无 `location`/`radius`）→ 返回全市前 8 条；`city=省份` 时 `citylimit` 弱化 → 跨市结果
  4. `pickRestaurant` 旧公式 `score = dist + (tagMatch ? 0 : 50000)`（**50km 惩罚**）与 `searchNearby` 旧排序 `d - rating*1000`（**评分 1000 分量级压过距离**）——口味/评分完全压过距离
- **修复**：
  1. **管线末位新增 `correctMealLegsStep`**（挂在 `checkMealWindowsStep` 之后，其后不允许再有改写 travel 的步骤）：逐天取有坐标的锚点，仅修正**至少一端是餐、且两锚点相邻**的腿（`travelTimeMin` 语义是「到下一个活动的路程」→ 承载体必须是起点 `from`）；`mealLegNeedsRoute`（现有里程 vs `straight*1.35`，阈值 `max(1.0, roadEst*0.4)`）筛选 → `findBestRoute` 并行算路（RouteService 带 Redis 7 天缓存）→ 写 `travelTimeMin`/`travelDistanceKm`/`transportToNext`
  2. **距离是地理事实，时间是排程约束**：有时间**增加**才跑 `fixTimeOverlaps`；若采纳后 `mealWindowViolations` 数量增加 → 回退 `travelTimeMin`（**保留真实距离**）+ 重跑 `fixTimeOverlaps`；仅缩短/等值时直接落值，不触碰时间轴
  3. **串城餐点重定位 `repairMealCoord`**：餐点距同日**前后两锚点都 > `MEAL_COORD_TRUST_KM`(60km)** → 以邻近锚点为圆心 `lookupExact(bare, city, ref)` → 失败再 `searchNearby("美食", city, ref)` 重定位；仍失败 → 加入 identity `untrustedMeals` 集合、**跳过其相关腿**（毒坐标不入库存）
  4. **`coordBefore` 本地坐标回退**：活动缺 lat/lng 时回退 `estimateCoord`（`enrichMealNames` 已预热 geoNameCache，不发 API）→ 补餐参考点不再恒 null；`annotateMealRestaurants` 改按 day+time 排序遍历、**跨天重置参考点**
- 涉及文件：`plan-service`（`TripPlanningAgent.java`、`RestaurantSearchService.java`、`MapApiConfig.java`、`application.yml`、新增 `test/.../MealTravelCorrectionTest.java`、`test/.../RestaurantNearbyScoreTest.java`、`MealWindowEnforceTest.java` 反射签名同步）
- **验证方式与结果（2026-10-04）**

| 项 | 结果 |
|---|---|
| 全量 `mvn -o test` | **147/0**（基线 137 + MealTravelCorrectionTest 7 + RestaurantNearbyScoreTest 3）✅ |
| 部署 | stop → `mvn -o -q -DskipTests package` → start → **6/6 UP（8081-8086）** ✅ |
| 黄金重规划宁夏 trip `6f183b3a` | `餐段路程补正完成: 修正 4 段，坐标重定位 0 个，不可信 0 个`、`CHECK_MEALS: 违规 0 -> 0`；落库里程与直线估算一致（怀远夜市→午餐 `2min/0.1km`、银川步行街→晚餐 `3min/0.2km` 原为伪造 `15min/1km`）✅ |
| 新建「E2E海南跨城-1.35.0」（city=海南，原三亚毒坐标对抗） | **18/18 PASS 0 FAIL**：6 个餐点距最近活动 **max 1.0km**（原 214km）、餐厅距上一景点 0.05~0.98km、进餐段里程全部落在 `直线×1.35±60%` 内、餐窗全合规；日志 `餐段路程补正完成: 修正 4 段（仅距离/时间缩短）` ✅ |
| `node check-meals.js`（新口径门禁） | **violations=0 PASS** ✅ |

---

## [1.34.1] - 2026-10-04

### 修复（timeStart 与日程脱节致出窗午餐无法回收 / 午餐退化右缘收紧 14:00）

- **现象**：宁夏截图问题单（trip `6f183b3a`，16:57 创建）：day1 午餐 **15:45-17:15** 出窗，plan-service 日志 `CHECK_MEALS: 1 处时间窗违规未修复: day1 午餐 15:45 出窗`、`每日正餐保底: day1 午餐出窗且强制入窗失败，保留原位 15:45`；`check-meals.js` FAIL。同时用户提出口径要求：**午餐 14:00 前、晚餐 20:00 前**（原退化右缘午 15:00 不满足）
- **根因**：① 用户未指定出发时刻 → 前端默认 `timeStart` 时分=创建时刻（16:57），而 LLM 把 day1 排为 08:00 起——两者脱节；② `firstDayFloor` 取 16:57 > 窗口右缘 13:30 → `DailyMealPlanner` 走退化分支，**唯一候选 max(target, floor)=16:57 恒晚于 cap 15:00** → `plan`/`planForced` 必然判空 → 出窗午餐「保留原位」永不回收；判定侧 `mealWindowViolations` 同 floor 同口径，检出却修不了（守卫对脱节行程整体失能）
- **修复**：
  1. **floor 与日程对齐**：新增 `TripPlanningAgent.alignFirstFloor`——`ensureDailyMeals` / `mealWindowViolations` 构建 byDay 后取 `min(firstFloor, day1 实际最早活动开始)`，timeStart 与日程脱节时以日程事实为准；落库侧 `MealWindowGuard.findViolations` 同口径（`alignFirstFloor`）
  2. **午间退化右缘 15:00 → 14:00**：`DailyMealPlanner.latestStart`、`mealWindowViolations` cap、`MealWindowGuard` cap 三处同步（常规右缘 13:30/19:30 不变；晚退化 20:00 保持），任何情况午餐起点 ≤14:00，满足用户口径
  3. 门禁同步：`check-meals.js` FAIL 口径午餐区间 `[11:00, 15:00]` → `[11:00, 14:00]`
- 涉及文件：`plan-service`（`TripPlanningAgent.java`、`DailyMealPlanner.java`、`MealWindowEnforceTest.java`、`DailyMealPlannerTest.java`）、`trip-service`（`MealWindowGuard.java`、新增 `test/.../MealWindowGuardTest.java`）、`check-meals.js`
- **验证方式与结果（2026-10-04）**

| 项 | 结果 |
|---|---|
| 全量 `mvn -o test` | **137/0**（基线 133 + 新增 MealWindowGuardTest 3 + 宁夏脱节回归 case 1；`-pl` 单跑会因 common-module 旧 jar 假红，全量 reactor 为准）✅ |
| 部署 | stop → `mvn -o -q -DskipTests package` → start → **6/6 UP（8081-8086）** ✅ |
| 新建「去宁夏」×2（AI agent 管线实跑） | `CHECK_MEALS: 违规 0 -> 0` ×2；落库餐窗全合规（午餐 11:30、晚餐 17:30-19:29）✅ |
| **黄金验证**：重规划原宁夏 trip `6f183b3a`（timeStart 16:57 + 原 15:45 出窗餐完整复现，owner 换 admin 触发，1.31.0 先例） | `每日正餐保底: day1 午餐出窗(15:45)，重放到 12:35`（零打扰落位，邮政博物馆 14:45 不动）；`CHECK_MEALS: 违规 0 -> 0`；4 天餐次 `RESULT: all meals in window` EXIT=0 ✅ |
| `node check-meals.js 20`（新口径门禁） | **violations=0 PASS** ✅ |

---

## [1.32.0] - 2026-10-04

### 修复（导航栏/侧边栏硬编码用户信息、头像 URL 无法清空、侧边栏 /settings 死链）

- **现象**：① 导航栏头像恒为「User」首字母、移动端侧边栏恒显示 `User` / `user@example.com`，与登录账号完全无关；② 资料页把头像链接清空后保存，服务端旧头像依旧（新选「默认头像」不生效）；③ 侧边栏「设置」项跳转 `/settings`，router 无此路由 → 白屏
- **根因**：① `AppNavbar.vue:129` / `AppSidebar.vue:97-100` 硬编码 `name="User"` 等字面量，未接 `authStore`；② `ProfilePage.saveProfile` 用 `avatarUrl: trim() || undefined` 兜底，空串被吞成 `undefined` 不入请求，而后端 `UserService.updateProfile:137` 是 `null 才跳过`——空值永远到不了服务端；③ 侧边栏 navLinks 写死 `/settings`
- **修复**：① 三处全部接入 `authStore.userName` / `user.email` / `user.avatarUrl`（随 1.32.0 渲染层一并生效）；② 保存改恒传 `avatarUrl: trim()`（空串覆盖清除，`null` 语义保留给「字段不参与更新」）；③ `/settings` → `/profile` 并复用 `nav.profile` 文案
- 涉及文件：`frontend-new/src/components/layout/{AppNavbar,AppSidebar}.vue`、`src/views/ProfilePage.vue`
- **验证方式与结果（2026-10-04）**

| 项 | 结果 |
|---|---|
| `npm run build` | EXIT=0 ✅ |
| 部署 | stop → package → start，**6/6 UP** ✅ |
| `avcheck.js` E2E | **13/0**：保存后导航栏=fox、`GET /api/auth/me` 持久化、刷新存活；默认清空后 `avatarUrl=''` 且导航栏回退首字母（原 ② 号问题路径全链路通过）✅ |
| 侧边栏死链 | 菜单项已指向 `/profile`（代码级修复，随包部署）✅ |

---

## [1.31.0] - 2026-10-04

### 修复（出窗午餐无人校验保留原位 / 跨零点回绕致 21:00 判超漏检 / 休息节点交通展示错位）

- **现象**
  1. 复现行程 `47cb48d9`（截图问题单）：day1 午餐 15:11 落在作息窗口（午 11:00-13:30）之外；`check-meals.js` 扫描近 30 天历史存量 **104 处**同类违规（午餐 >14:00、晚餐 >20:30 等）
  2. 隐患：强制入窗级联可能把活动结束推过 24:00，`LocalTime.plusMinutes` 跨零点回绕（24:15→00:15）——`fixTimeOverlaps` 用 `isAfter(21:00)` 判超**漏检**（回绕后恒为凌晨）、`pullDayLeft` 会把后续活动**前拉到凌晨** 造成时间轴污染
  3. 休息节点展示：休息卡显示自身携带的真实路程（「0 分钟 · 0km」类无意义信息），休息后地点的 pill 显示的是 rest 内部路程而非「休息前→休息后」直达段
- **根因**
  1. `DailyMealPlanner.plan()` 以 21:00 收口为硬约束，LLM 排满日必然判空；`ensureDailyMeals` 判空分支「宁缺餐不删景」直接保留原位——出窗餐从 1.29.0 出窗回收机制的「窗口内无槽原样放回」兜底中漏过，此后无任何步骤再校验（管线末位无检查节点）
  2. 回绕：既有 `fixTimeOverlaps`/`pullDayLeft` 写于无强制级联的年代，隐含「结束 ≤ 24:00」假设；1.31.0 强制入窗放开 21:00 收口后该假设不再成立
  3. 展示：rest 携带真实路程是 `transitDurationMin` 统计的既定依赖（REST-5 不变式），但 ActivityCard/TransitConnector/ShareView 按「相邻卡片直读」渲染，未按 rest 链聚合
- **修复**
  1. **强制入窗**：`DailyMealPlanner.planForced()`（窗口右缘仍硬约束，级联放开 21:00）→ `ensureDailyMeals` plan 判空后重试，`forcedDay` 当日收口调 `fixTimeOverlaps` 裁尾（截断至 21:00，放不下才移除；正餐被挤优先剔其前游览）；仍失败才 WARN 保留原位/跳过
  2. **管线末位检查**：新增 `checkMealWindowsStep`（存在性+窗口，windowCap 同口径含首日退化 15:00/20:00），违规修复→复验→仍违规 fail-loud WARN + `CHECK_MEALS: 违规 X -> Y` 汇总；trip-service 落库侧 `MealWindowGuard` 告警双保险（仅 WARN 不拦截）
  3. **回绕防护**：`fixTimeOverlaps` 判超改分钟运算（`start+dur > 1260`，回绕必然 >21:00 一并命中）；`pullDayLeft` 改分钟运算（prevEndMin 可 >1439）并封顶 23:59，杜绝前拉凌晨
  4. **展示修正**（B，后端数据零改动）：`legToNext` 聚合 rest 链直达段；休息卡恒不显示交通信息；提示+pill 都放休息前（可导航），休息后 pill 隐藏；分享页同口径
- **涉及文件**：`plan-service`（`DailyMealPlanner.java`、`TripPlanningAgent.java`）、`trip-service`（新增 `MealWindowGuard.java`、`TripService.java`、`InternalController.java`）、`frontend-new`（`activity.ts`、`ActivityCard.vue`、`DayTimeline.vue`、`ShareView.vue`）、新增 `check-meals.js`
- **验证方式与结果（2026-10-04）**

| 项 | 结果 |
|---|---|
| `mvn -o test` 全量 | **120/0**（新增 MealWindowEnforceTest：出窗+缺餐一次修复后 0 违规、干净输入 no-op、违规清单口径、过满日强制补双餐裁尾 ≤21:00）✅ |
| `node verify-meals.js` | **60/0** ✅ |
| 复现 `47cb48d9`（owner 换 admin 触发→立即恢复） | **fail=0**（原 15:11 问题行程重生成后 4 天餐次全入窗、收口 ≤21:00、无回绕）✅ |
| `node check-meals.js`（新门禁） | 近 120 分钟新版本 **violations=0**；历史存量 104 条 INFO（证明扫描器可检出）✅ |
| plan-service 日志 | `CHECK_MEALS 0->0` ×9、强制入窗 WARN ×4 ✅ |
| `node verify-rest.js` | **23/0**（rest 后端不变式未回归）✅ |
| rest 视觉核对（puppeteer-core+Edge 截图） | **11/0** + 截图人工确认详情页/分享页均符合「提示+pill 在休息前、休息卡无交通信息」✅ |
| 前端构建部署链 | `npm run build` → robocopy → `mvn -o -q -DskipTests package` → restart，6/6 UP ✅ |

---

## [1.30.0] - 2026-10-03

### 修复（景区型目的地 city 参数静默失效致全国污染 / 毒坐标级联删游览 / VERIFY_CITY 外城正餐整条剔除）

- **现象**（E2E 实锤，1.29.0 收尾遗留）
  - 长白山 2 日 relaxed：餐饮检索返回北京西单店（「悦融琥珀·京鲁菜(西单店)」）、商场/购物返回太原/长沙等全国结果；`geocode 长白山风景区` 返回新疆哈密毒坐标 (92.5,41.9)；北坡→天池 301km/320min、天池→晚餐 4207km 毒路线；最终 **day2 恒 2 游览且缺午餐**（`verify-meals` 19:50 部署前批 caseB d2 FAIL：no lunch + idle 145min）
  - 长春 `47cb48d9` day3/4 残留 10-01 20:01 旧版本数据（上轮遗留，待根因修复后重生成）
- **根因**（高德直调实测，脚本证据 `amap-test.txt`/`amap-test2.txt`）
  1. `rest?city=长白山&citylimit=true` → `count=1000` **全国结果**——高德不识别「长白山」为行政区，citylimit **静默失效**，POI/餐饮/交通检索全部失去城市过滤
  2. `geocode?address=长白山风景区` → 新疆毒坐标；`GeocodeService.CITY_CENTERS` 无长白山 → `center==null` → `resultCityMatches` 直接 `return true` 放行 → **跨城裁决完全关闭**
  3. 毒坐标 → 路网异常 → `fixTimeOverlaps` 21:00 级联每轮删掉 day2 最后一个游览（长白山/黄山/燕莎）→ 游览数恒 2
  4. `TripService.verifyCityOwnership` 对**跨城餐直接剔除** → 西单店午餐消失
  5. **节奏侧共因**：`TripPace.RELAXED` 景点数区间为 **2~3**（minVisits=2）——21:00 级联删到 2 个游览后 `visitCount < minVisits` 恒为 false，`belowPaceFloor` 判「已达标」**保底填充永不触发**，每日 2 游览被固化
- **修复**：
  1. **城市别名层**（新增 `AmapCityAlias`）：`长白山 → 安图县`（行政区实测覆盖北坡/聚龙温泉/长白山风景区，二道白河镇 geocode 亦归安图县）+ `radiusKm` 区域半径（长白山 120km，其余回退调用方默认值）
  2. `GeocodeService`：`CITY_CENTERS` 补长白山锚点 {42.05, 128.05}——恢复 150km 跨城裁决与「未知区域名回落中心坐标」兜底；`callAmapGeocode` / `callAmapPlace` 的 city 参数统一走别名
  3. `PoiSearchService`：`queryLoose` / `searchOne` city 走别名；`filterByDistance` 阈值改 `AmapCityAlias.radiusKm(city, MAX_DISTANCE_KM)`；新增 `NATIONAL_GUARD_KM=150` + `withinRadius()`——**全国路候选守卫**：先 `resolveCityCenter` 再按城市半径过滤交通候选（citylimit 失效时兜底）
  4. `RestaurantSearchService.queryPois`、`RouteService.callAmapTransit` city 走别名（共 5 处调用点）
  5. `TripService.verifyCityOwnership` 重写：跨城 **visit 仍剔除**；跨城 **meal 降级不删**（新 `degradeForeignMeal`：名称截到首个 `·` 前成通用餐次，清除 lat/lng/address/rating/cost 等异地细节）；「剔除过多」判定仅按剔除的 visit 计（`rejected*2 > checked`）；仅降级时记日志 `VERIFY_CITY 降级外城餐厅为通用餐次`
  6. **节奏下限**：`TripPace.RELAXED` 景点数 2~3 → **3~4**（minVisits 2→3，使保底填充在 2 游览时可触发）；前端 i18n `relaxedVisits` 文案同步 zh「约 3~4 个景点」/ en 对应项，`npm run build` + robocopy 后随包部署
- 涉及文件：
  - `plan-service`：`service/AmapCityAlias.java`（新增）、`service/GeocodeService.java`、`service/PoiSearchService.java`、`service/RestaurantSearchService.java`、`service/RouteService.java`、`constant/TripPace.java`、`src/test/.../service/AmapCityAliasTest.java`（新增 5 例）
  - `trip-service`：`service/TripService.java`、`src/test/.../service/TripServiceVerifyCityTest.java`（新增 4 例）
  - `frontend-new/src/i18n/zh-CN.json`、`frontend-new/src/i18n/en-US.json`

### 已知问题（非本轮回归，未在本轮修复）

- **caseC（北京 3 日 compact）day2 visits=2 < compact 下限 4**：`assert-d3d4` 报 FAIL。版本史实测 **10-01 22:23 起多轮复现**（d2:v2 与 d2:v4 并存的 LLM 方差）——八达岭 5h + 颐和园 3h 占满日程且日内最大空窗 74min < `fillDayGaps` 触发阈值 120min → 保底填充不触发。建议后续：景点数未达 `minVisits` 时把空窗阈值降档（如 ≥60min）再插入。

### 验证方式与结果（2026-10-03）

| 项 | 结果 |
|---|---|
| `mvn -o -q compile -pl plan-service,trip-service -am` | exit=0 ✓ |
| `mvn -o -q test`（全量） | **112 tests / 0 failures / 0 errors**（103 基线 + 新增 9；定向 `-pl` 单跑曾因本地仓库 common-module 旧包误报，reactor 全量复跑全绿）✓ |
| `node verify-meals.js`（杭州3d / 长白山2d / 北京3d / 成都2d） | **pass=60 fail=0**（19:50 部署前批 caseB d2 无午餐 FAIL → 20:49-20:54 部署后批 4 用例 10 天全过）✓ |
| caseB 长白山修复前后对比 | 19:50 `d1:v3 d2:v2 无午餐` → 20:52 `d1:v3 d2:v3 午餐·东北菜 / 晚餐·长白山美食`，餐次与景点全部本地 ✓ |
| 长春 `47cb48d9` 重生成 | owner 临时切 admin（`11111111…`）触发 `POST /plan` → **立即恢复原属主 `c18e1c05…`**；新版本 `e8015e5b` 2026-10-03 21:00:19，`assert-trips` acts=24 **OK / FAIL=0**（替换掉 10-01 20:01 旧数据）✓ |
| `assert-d3d4`（4h 窗口） | FAIL=3 = 部署前旧批 2 条（caseB 无午餐/caseC 旧数据，部署后新批已不再出现）+ caseC 已知问题 1 条；**部署后新批 A/B/D 全 OK**、仅 caseC（见已知问题）✓ |
| 部署回归 | stop → `mvn -o -q -DskipTests package` → start → 6/6 UP（8081-8086）✓ |
| 前端（RELAXED 文案） | `npm run build` → `robocopy frontend-new\dist gateway\...static /MIR` → `mvn -o package -DskipTests` → restart 已于本轮完成，`relaxedVisits` 新文案随网关静态包下发 ✓ |

---

## [1.29.0] - 2026-10-01

### 修复（正餐落出作息窗口 / 21:00 收口删餐 / 落库餐次误判 / 跨城数据污染）

- **作息窗口违规（E2E 实锤 5 处，全部清零）**
  - **现象**：`verify-meals` 首轮 5 FAIL——caseA d1 午餐 14:45、caseC d1 午餐 14:20 / 晚餐 19:33、caseC d2 午餐 13:58 / 晚餐 19:52（窗口：午 11:00-13:30、晚 17:00-19:30）
  - **根因**：LLM 原生餐先被人性化校准钳回窗口，其后填充活动插入 / 真实路网修正又经 `fixTimeOverlaps` 把餐顺延出窗口；`ensureDailyMeals` 只补「缺失」不回收「出窗」，补餐自身还允许落位到 15:00/20:00——没有任何步骤把餐收回窗口（日志 0 条「保底」触发实锤）
  - **修复（B11 三件套）**：
    1. **出窗餐回收**：`ensureDailyMeals` 逐天把落出窗口的午/晚餐摘下重放（复用原对象保留名称/坐标/餐厅），窗口内确实无槽时原样放回——宁出窗不可丢餐；同餐次重复出窗餐才丢弃
    2. **窗口右缘收紧**：`DailyMealPlanner` 实际落位上限由「午 15:00 / 晚 20:00」收紧为窗口右缘 13:30 / 19:30；仅当首日出发时刻本身晚于右缘时退化回旧口径（保住「晚出发也能补餐」）
    3. **旅行切分**：插入空隙起点 = 上一活动结束 + `min(原路程, 15 分钟)`——景区→市区 58 分钟长路程不再整段压在餐前把餐顶出窗口；落位侧同步把上一活动路程收缩为「到正餐」步行段、剩余段由餐到下一段承担，维持 REST-4 相邻不变式（餐起点 ≥ 上一活动结束 + 上一活动到餐的路程）
    4. 出窗重放/级联右推会改变时间轴——任意落位发生即按天重建输出并重排，避免原列表相对顺序过期
  - **21:00 收口删餐（B8）**：`fixTimeOverlaps` 对被挤过 21:00 的用餐原先是整条剔除（丢餐另一根因链）；改为「用餐绝不优先删除」——先截断该餐至 21:00 前（≥15 分钟），不够则回溯截短其前方可截的用餐，仍不行才兜底剔除（防死循环）
  - **管道终末兜底（B7）**：`postPipeline` 末尾（rest 插入 / 收口修复之后）再挂一次 `ensureDailyMealsStep`，新增 `pushFromIndex` 自行级联右推（其后无 fixTimeOverlaps 可依赖）
- **落库餐次误判（B9）**
  - **现象**：无餐次字的晚餐被 slot 去重删空；「撞名但当日晚餐仍空缺」的正餐被同名全程去重误删
  - **根因**：`TripService.mealSlotOf` 用 `substring(11,13)` 取小时——只对 ISO `yyyy-MM-ddTHH:mm:ss` 成立，对 `HH:mm` 恒越界 → 晚餐被判成午餐，slot 冲突删空；`dedupeActivitiesForPersist` 撞名判定不看餐次槽位
  - **修复**：时间解析改 `indexOf(':')` + `substring(max(0, ci-2), ci)` 兼容 `HH:mm` 与 ISO；去重先锁「同日同餐次唯一」，撞名但当日在窗餐次仍空缺时保留正餐并记日志（`入库去重: 撞名但当日x餐空缺，保留正餐`）
- **跨城数据污染（B10）**
  - **现象**：高德把「长白山」编码回北京坐标，产生 695km / 382min 毒路线；换版排除检索缺「西单/日坛」等地名漏过滤；长白山地名在城市归属表缺登记被当跨城剔除
  - **修复**：
    - `GeocodeService`：抽出静态 `CITY_CENTERS`（补长春/大连），`resultCityMatches` 三级裁决 = 名称匹配 → 已知中心 150km 距离裁决 → 未知区域名（如长白山）放行；应用于 Redis 缓存 / DB 缓存 / 主响应（3.5 丢弃置 null 走 POI 兜底）/ POI 兜底（3.6）全路径，不跨城才写缓存
    - 路线守卫：`correctStep` 写回循环 `routeLooksPoisoned`（>300km 或 >240min）→ `reestimateRouteFromCoords`（直线 ×1.35 重估，仍超限返 null 保留原值）；`fillZeroTravelTimes` 直线 >300km 置 -1 走无坐标经验值
    - `CityOwnershipUtils`：CITIES 补「长白山」；登记长白山天池/长白瀑布/聚龙火山温泉/绿渊潭/地下森林/美人松公园/二道白河/长白山国际度假区（**不**登记裸「天池」，避免抢注新疆天池）；北京组补 西单/西单大悦城/西单商场/日坛/日坛公园
- 涉及文件：`plan-service/.../agent/DailyMealPlanner.java`、`agent/TripPlanningAgent.java`、`service/GeocodeService.java`、`service/PoiSearchService.java`（A 组联动）、`trip-service/.../service/TripService.java`、`common-module/.../util/CityOwnershipUtils.java`、`plan-service/src/test/.../agent/DailyMealPlannerTest.java`

### 验证方式与结果（2026-10-01）

| 项 | 结果 |
|---|---|
| `node verify-meals.js` | **60/0**——修复前 5 处窗口违规（14:45 / 14:20 / 19:33 / 13:58 / 19:52）全部清零；10/10 天午+晚齐全 ✓ |
| `node verify-rest.js` | 23/0——旅行切分后 REST-4 相邻不变式全过 ✓ |
| `DailyMealPlannerTest` | 20/20（新增窗口右缘、旅行切分 2 例；改造 1 例适配）✓ |
| plan-service / trip-service 全量单测 | 56/56、32/32 ✓ |
| 出窗回收日志（plan-service） | 27 条「出窗→重放」成功、2 条「无槽→保留原位」、0 条失败/丢餐 ✓ |
| `node check-time.js`（DB 最新版本抽查） | 本轮 5 个行程最终版本 0 FAIL ✓ |
| 部署回归 | stop → `mvn -o -DskipTests package` → start → 6/6 UP（8081-8086）✓ |

---

## [1.27.0] - 2026-10-01

### 修复（语言切换按钮点击后界面语言不变）

- **现象**：导航栏「中/EN」按钮点击后按钮文字会变，但整站文案始终中文；个人中心保存语言偏好刷新后也不生效
- **根因**（三处断链叠加）：
  1. `AppNavbar.toggleLanguage` 写入 `'zh'/'en'`，而 i18n 注册的 locale 码是 `'zh-CN'/'en-US'` —— 未注册码触发 `fallbackLocale: 'zh-CN'`，等于没切
  2. 直接给 `useI18n()` 的 `locale` 赋值不落 localStorage，刷新即丢；`appStore.setLocale`（个人中心入口）只写 store + localStorage，**不回写 vue-i18n**，保存后界面同样不变
  3. `document.documentElement.lang` 与 dayjs locale 从未随切换更新（dayjs 在 `main.ts` 写死 `zh-cn`，`NotificationItem` 内还有一处组件级重复设置）
- **修复**：
  - `i18n/index.ts` 新增 `applyLocale(lang)`：规范化语言码 → 同步 `i18n.global.locale` + `html lang` + `dayjs.locale` → 持久化 `tf_locale`；`normalizeLocale` 兼容历史 `'zh'/'en'`
  - `AppNavbar.toggleLanguage` 与 `appStore.setLocale` 统一经 `applyLocale` 单入口；按钮显示态判断改为 `locale === 'zh-CN'`
  - `main.ts` 启动时 `applyLocale(localStorage.getItem('tf_locale'))`，移除硬编码 `dayjs.locale('zh-cn')`；删除 `NotificationItem` 组件内 `dayjs.locale('zh-cn')`
  - 按钮切换逻辑本身与全站文案迁移为 `t()` 一并完成（见 CHANGELOG 1.27.0）
- 涉及文件：`frontend-new/src/i18n/index.ts`、`frontend-new/src/main.ts`、`frontend-new/src/stores/app.ts`、`frontend-new/src/components/layout/AppNavbar.vue`、`frontend-new/src/components/notification/NotificationItem.vue`

### 验证方式与结果（2026-10-01）

| 项 | 结果 |
|---|---|
| 切换链路代码走查 | 按钮/个人中心/启动三入口均汇入 `applyLocale`，语言码规范化后与注册码一致 ✓ |
| 语言包键覆盖静态扫描 | 549 个静态 `t()` 键 0 缺失，zh/en 531 键结构对等 ✓ |
| `npm run build` | vue-tsc + vite 构建通过 ✓ |
| 服务端产物字节级抽查 | 网关下发 JS 同时包含 zh/en 词条 ✓ |
| 部署 + 回归 | 6/6 UP；`node verify-rest.js` pass=23 fail=0 ✓ |

## [1.26.2] - 2026-09-30

### 修复（行程导出 JSON/PDF/ICS 三种格式均无法下载）

- **现象**：行程详情页「导出」面板三种格式全部失败
  - JSON/ICS：点击后无任何下载发生（浏览器控制台为 data: URL 顶层导航/弹窗被拦截）
  - PDF：返回 403 —— 前端拿到的 `downloadUrl` 是 `/api/trips/{id}/export/pdf?...`，而该下载端点后端根本不存在（只有 `GET /{id}/export` 元数据接口）
- **根因**：
  1. 元数据接口把**文件内容**编码成 `data:application/base64` 塞进 `downloadUrl`，前端用 `window.open` 打开——Chrome 禁止顶层 `data:` 导航，且 `await` 接口响应后用户手势已失效，触发弹窗拦截
  2. PDF 路径返回一个从未实现的下载端点地址
  3. 附带缺陷：`formatIcsDateTime` 在 `HH:mm:ss` 后**多拼一个 `00`**（生成非法 `DTSTART`，日历无法导入）；真实数据 `scheduled_start` 是 `"08:00"` 纯时间（无日期）会被整段跳过 → ICS 空文件；`getBytes()` 用平台默认编码；`exportTrip` 中 `Map.of()` 遇 `preferences`/`conflicts` 为 null 直接 NPE；PDF 从未真实渲染（返回 0 字节占位）
- **修复**：
  - 新增真实文件下载端点 `GET /api/trips/{id}/export/file?format=&version=&includeMap=&includeStats=&language=&timezone=`（`@RequirePermission("trip:read")`），返回原始字节流 + RFC5987 `Content-Disposition`；元数据接口 `downloadUrl` 全部改指该端点
  - 前端新增 `utils/download.ts`：带 token 的 axios 请求取二进制 → `URL.createObjectURL` + `<a download>` 触发保存（绕开弹窗拦截与 data: 限制），`data:` URL 走 fetch 兜底；`ExportPanel.vue` 的 `window.open` 替换为该下载
  - ICS 重写时间合成：完整 ISO 时间直接解析；`"HH:mm"` 纯时间用 `trip.timeStart` 日期 + `day-1` 偏移合成；DTEND 缺失/不晚于 DTSTART 时取 start+2h；字段名兼容 snake/camel 双键；转义先处理反斜杠
  - JSON/Template null 安全（LinkedHashMap + `mapOrEmpty`/`listOrEmpty`），全部 `getBytes(StandardCharsets.UTF_8)` 显式 UTF-8
  - PDF 真实渲染：iText7（pom 已有 `itext7-core:7.2.5`）+ 系统中文字体候选链（微软雅黑/黑体/宋体/等线，Linux/Noto 备选），标题/元信息/按天分组活动/地址/可选统计；无可用字体时抛业务错误而非输出乱码
- **设计要点**：PDF「包含地图」勾选暂未嵌入高德静态图（markers 语法未确认，按 fail-open 不做半成品），不影响导出本身；`exportTrip` 与 `renderFile` 共用 `resolve()` 保证权限/版本解析一致
- 涉及文件：
  - `trip-service/.../controller/TripExportController.java`（新增 `/export/file`）
  - `trip-service/.../service/TripExportService.java`（重写）
  - `trip-service/src/test/.../TripExportServiceTest.java`（新增 9 条单测）
  - `frontend-new/src/utils/download.ts`（新增）
  - `frontend-new/src/components/trip/ExportPanel.vue`

### 验证方式与结果（2026-09-30）

| 项 | 结果 |
|---|---|
| `mvn -o -q -pl trip-service -am test`（含新增 TripExportServiceTest） | 9/9 PASS ✓（全量 0 fail） |
| `npm run build`（vue-tsc + vite） | 构建通过 ✓ |
| API 端到端：登录后 `GET /export?format=json` → 带 Bearer 请求 `downloadUrl` | 200，UTF-8 中文保留、JSON 可解析、Content-Disposition 文件名正确 ✓ |
| API 端到端：PDF | 200，`%PDF` 头，174 KB 真实内容 ✓ |
| API 端到端：ICS | 200，21 个 VEVENT，`DTSTART` 全部为合法 `yyyyMMddTHHmmss`，day1/day3 日期合成正确（20261015/20261017），无旧版多拼 00 ✓ |
| `node verify-rest.js` 回归 | pass=28 fail=0 ✓ |
| 部署 | stop → build → robocopy → `mvn -o package -DskipTests` → 6/6 端口 UP ✓ |

## [1.26.0] - 2026-09-29

### 安全修复（公开仓库发布前脱敏）

- **问题**：仓库将以公开形式推送到 GitHub，但多处代码硬编码/回退了本地开发数据库密码，克隆即泄露
- **修复**（HEAD 起代码中该密码出现 0 处）：
  - 5 个服务 `application.yml`（auth/trip/plan/planning-worker/notification）：`password` 移除硬编码回退值，改为 `${MYSQL_PASSWORD:}`
  - `docker-compose.yml`：`MYSQL_ROOT_PASSWORD`/`MYSQL_PASSWORD` 默认值移除，改为 `${VAR:?...}` 强制从 `.env` 读取（compose 自动加载项目根 `.env`，模板 `.env.example` 已含占位值）
  - `start-all.ps1`：`.env` 读取白名单新增 `MYSQL_PASSWORD` 并注入子进程；缺失时启动阶段明确告警
  - 4 个 JS 脚本（`check-time.js`/`showtrip.js`/`e2e-pace.js`/`e2e-diversity.js`）硬编码 `-p…` → 统一经新增 `db-env.js` 从 `.env` 读取
  - 删除 4 个硬编码密码的 `start-*-simple.bat`（合并入 `start-all.ps1`，见 CHANGELOG 1.26.0）
- **设计要点**：按 AGENTS §10.1 环境变量注入规范，配置文件不留敏感回退值；`.env` 保持 gitignore 不入库
- **影响**：不经 `start-all.ps1` 裸启动（直接 `java -jar`/`mvn spring-boot:run`）时需自行提供 `MYSQL_PASSWORD` 环境变量，否则数据源连接失败
- **残留说明**：`docs/BUGFIX.md` 历史条目（1.23.0）与既有 8 个历史提交中仍保留该本地开发密码文本——按「只增不改历史条目」规则不做改写；该密码仅对应本机 localhost MySQL，真实密钥（LLM/高德/JWT/.env/私钥）经扫描在代码与全部历史中均为 0 处

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| 脱敏后全量单测 `mvn -o test` | 82/82 PASS ✓ |
| 脱敏后冷启动 | `start-all.ps1 -Action start` 6/6 UP，业务链路可用 ✓ |
| `node check-time.js`（新 db-env.js 读 .env 实连） | ALL_PASS ✓ |
| `node verify-rest.js` | pass=28 fail=0 ✓ |
| HEAD 代码密钥扫描 | 本地开发密码代码 0 处、API 密钥 0 处、密钥文件 0 处 ✓ |

## [1.24.0] - 2026-09-29

### 修复

- **晚餐·青岛老城海鲜馆 被入库去重误杀（repro-meal 青岛1日 dinner=N）**
  - **现象**：plan-service 输出 6 活动含正餐，trip-service 落库日志 `入库去重剔除: day=1, type=meal, name=晚餐·青岛老城海鲜馆`、`入库二次去重: 6 -> 5`，最终行程缺晚餐
  - **根因**：`dedupeActivitiesForPersist` 餐食分支对 `seenVisit` 用 `nameContains`（归一化后互含即重复，min≥3 即触发），餐厅名「青岛老城海鲜馆」天然包含景点名「青岛老城」→ 被判与景点重复剔除；文档口径为「餐食名与景点名**等价**视为重复」，包含判定过宽
  - **修复**：meal-vs-visit 双向跨类型判定改为归一化后**精确相等**（`key.equals(normalizeActivityName(prev))`），同名/等价去重、同餐次唯一、meal-vs-meal 与 visit-vs-visit 的包含判定均保持原口径
  - 文件：`trip-service/src/main/java/com/tripplanner/trip/service/TripService.java`

- **e2e-pace moderate day1 visits 2 < 3（21:00 裁剪后数量下限失守）**
  - **现象**：`活动频率预算 day1: 游览 510 分钟 / 3 个景点` 达标后，`时间顺延裁剪超出 21:00 的活动: 1 个` 把 day1 裁到 360 分钟 / 2 个景点，`refillStep` 未补，最终 band 断言 FAIL
  - **根因**：`belowPaceFloor`（refillStep 闸门）只比较 `visitMinutes < minHours*60`，时长已达下限即返回 false，不检查景点数；而 `fillDayGaps` 本身支持按 `visitCount < minVisits` 在 21:00 窗口内插入——闸门挡住了它
  - **修复**：`belowPaceFloor` 补入 `visitCount(dayActs) < pace.getMinVisits()`；补入活动受 fillDayGaps 原有约束（窗口 lead/trail、dur≤120、guard 轮次、候选耗尽即停），后续 `applyPaceBudget` 到数量下限即停裁不会反噬
  - 文件：`plan-service/src/main/java/com/tripplanner/plan/agent/TripPlanningAgent.java`

- **行程 LLM 偶发不合法 JSON 直落 inline 回退**
  - **现象**：`LLM生成详细行程失败，使用本地回退: Unexpected character (':')... was expecting comma`（缺逗号/引号的输出毛刺），回退路径不走 postPipeline，正餐保底/水合/补时均缺失
  - **修复**：抽出 `parseItineraryJson`（清理 markdown → 截取 `{...}` 两级解析），`planDetailedItinerary` 解析失败重调 LLM 一次（attempt=2），仍失败才回退；验证中实测触发并成功恢复
  - 文件：`TripPlanningAgent.java`

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| `repro-meal.js` 青岛1日 | lunch=Y dinner=Y ✓（青岛老城海鲜馆保留，无入库去重剔除日志）✓ |
| `repro-meal.js` 苏州3日 | d1/d2/d3 全部 lunch=Y dinner=Y ✓ |
| `e2e-pace.js` | PASS，moderate day1 恢复 3 景点 ✓ |
| plan-service / trip-service 单测 | 48/48、19/19 PASS ✓ |
| JSON 重试实战 | 日志 `JSON解析失败(第1次)` → `attempt=2` 成功（18.6s），未落回退 ✓ |

## [1.23.0] - 2026-09-29

### 修复

- **午餐晚餐丢失（缺餐/整日无餐）——根因链修复 + 每日正餐保底**
  - **现象**：青岛1日 d1 仅早餐缺午晚（`7d4fec4b...` 复现）、苏州3日 d3 全天无餐（`9fa58754...` 复现）；日志实锤 `活动去重: 8 -> 6, 剔除: [meal:青岛啤酒街海鲜大排档·午餐/·晚餐]`（跨天同名剔除）与 `20 -> 17, 剔除: [..., meal:午餐·松鹤楼, meal:晚餐·得月楼]`（ensureComplete 补餐被 step10 二次剔除）
  - **根因 1（收集缺陷）**：ensureComplete `usedRestaurants` 按名称取餐厅名，后缀式 `X·午餐` 只取到「午餐」→ `pickRestaurant` 漏防同餐厅 → 生成餐名与既有活动同名 → 持久层 `dedupeActivitiesForPersist` 同名全程去重剔除。修复：改用 `mealBareName`（兼容前缀/后缀/裸名）遍历**全部**活动收集（含景点名，防 meal-vs-visit 撞名）
  - **根因 2（餐次口径）**：`hasLunch/hasDinner` 此前用名称子串「午/晚」判断，裸餐厅名（如「楼外楼」18:00）误判缺餐造成补餐反复。修复：改 `mealSlotOf` 槽位口径（type=breakfast→b，名称餐次字 早/晚/午 优先，否则按开始时间推断），与 `normalizeDailyMeals`/保底步同口径
  - **根因 3（升级撞名）**：`enrichMealNames` 泛化升级时 `usedRestaurants` 未预置既有餐名，可能升级出同名餐厅。修复：预置全程 `mealBareName` 种子
  - **根因 4（兜底位置过早）**：原兜底在最后一次 dedupe 之前，补餐被二次剔除。修复：新增 `ensureDailyMealsStep` 置于 postPipeline 最后一次 dedupe 之后（`applyPaceBudget` 与 `annotateMealRestaurants` 之间），其后所有步骤均不删餐
  - **已知限制**：inline 回退路径（`InlinePlanningService`）不走 postPipeline、自带窄窗午餐/晚餐逻辑，本轮未覆盖；存量缺餐行程需重新规划一次才生效
  - 文件：`plan-service/.../agent/TripPlanningAgent.java`、`plan-service/.../agent/DailyMealPlanner.java`（新增）

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| `DailyMealPlannerTest` | 12/12 PASS ✓ |
| plan-service 全套单测 | 48/48 PASS ✓ |
| `repro-meal.js`（青岛1日 + 苏州3日 新建） | 全部天 lunch+dinner 齐全并成功落库 ✓ |
| `verify-rest.js` / `verify-d.js` / `verify-variant.js` | 28/28、22/22、16/16 PASS ✓ |
| `e2e-diversity.js` / `e2e-pace.js`(PACE=compact 复跑) | PASS / PASS ✓ |
| `e2e-pace.js` 首轮 compact day2 410min<450 | 既有 `applyPaceBudget` 按景点数上限（7>6）裁剪 60 分钟未校验时长下限所致，LLM 方差触发的 flake；机制上与本改动无关（该轮保底步未触发、budget 逻辑未改），复跑 PASS；**未在本轮修复**（已知问题） |

---

## [1.22.1] - 2026-09-29

### 修复

- **休息节点重排错位：同日第二次休息被排到下一活动之后（travel 归零不变式破坏）**
  - 现象：`verify-rest.js` REST-5 连续两轮报「休息前一活动(清河坊步行街)travel=0 :: travel=11」；插入取证日志却显示「day=3 after=杭州博物馆(13:29) travel 0.5km/7min→0, 休息接管=7」——插入邻接与落库邻接不一致
  - 原因：`RestSchedulePolicy.planDay` 计算 `start = item.endMin() + shift`，`shift` 为当日已接受休息累计时长（rest1=20min）。rest2 钟点含 +20 偏移（15:59）而清河坊仍持未偏移钟点（15:46）；`insertRestNodes` 输出后由 `fixTimeOverlaps` 按 startTime 字符串排序，shift(20) > 清河坊段 travel(7) → 清河坊排到 rest2 前，落库成「博物馆(travel=0) → 清河坊(travel=11) → rest2(接管7)」，REST-5 的「前一活动归零」断言必挂（仅同日 ≥2 次插入且 shift > travel 时触发）
  - 修复：`startMin` 改为**不含已接受偏移**（= 前一活动当前钟点；step20 收口后恒 ≤ 下一活动现有钟点，排序邻接稳定），标签仍按 `endMin+shift` 预估最终钟点取名（时段命名不受影响）；`Insertion` javadoc 同步；`RestSchedulePolicyTest` 双插入用例期望 840→820
  - 文件：`plan-service/src/main/java/com/tripplanner/plan/agent/RestSchedulePolicy.java`、`plan-service/src/test/java/com/tripplanner/plan/agent/RestSchedulePolicyTest.java`

- **rest 活动被强生成泛化 slogan（「探索精彩旅程」）**
  - 现象：API 查询 rest 活动 `slogan=探索精彩旅程`；休息节点设计上不写 notes/slogan，展示为无签名语更符合语义
  - 原因：`TripVersionService.toResponse` 对缺失或泛化 slogan 的活动一律 `generateSlogan`，rest 类型落到默认 visit 分支
  - 修复：`!"rest".equals(actType)` 前置豁免，rest 保持无 slogan（前端 `v-if="activity.slogan"` 已兼容空值）
  - 文件：`trip-service/src/main/java/com/tripplanner/trip/service/TripVersionService.java`

- **版本对比同名活动误报新增/移除**
  - 现象：含多个「中场休息」的版本对比，除首个外的同名活动被第三轮差异合并判为 added/removed
  - 原因：第一轮按 `normName(poiName)` 匹配用 `Map<String, Activity>`，同名仅首个入表
  - 修复：改 `Map<String, Activity[]>` 队列配对——`findIndex` 优先同天、无同天取队首、`splice` 取出即消
  - 文件：`frontend-new/src/components/version/VersionCompareView.vue`

- **地图线路 hover 光标不恢复**
  - 原因：`TripMap.addPolylines` `mouseout` 调 `map.setCursor('')`，空串非合法光标值
  - 修复：改 `setCursor('default')`
  - 文件：`frontend-new/src/components/map/TripMap.vue`

- **导航按钮内嵌 div 违反 HTML 语义**
  - 原因：1.22.0 将 pill 升级为 `<button>` 时图标容器仍为 `<div>`（button 仅允许 phrasing content），且开标签改 `<span>` 后闭合标签残留 `</div>`
  - 修复：容器统一 `<span>` 并修正闭合
  - 文件：`frontend-new/src/components/timeline/TransitConnector.vue`

- **E2E 测试资产口径**
  - `verify-variant.js` 景点名提取不排除 `rest` →「午后小憩」等结构性名称既在基准版又在新版且不在 rawInput，误报「排除生效：旧景点再现」；提取过滤补 `t !== 'rest'`（rest 非 POI，亦使 Jaccard/新增等换版断言更贴合景点语义）
  - `verify-rest.js` 此前仅存临时目录，已固化到项目根 `D:\agent-trip-planner\verify-rest.js`
  - 文件：`opencode/verify-variant.js`（临时 E2E 脚本）、`verify-rest.js`（项目根）

### 验证方式与结果（2026-09-29）

| 项 | 结果 |
|---|---|
| `mvn -o test -pl plan-service -Dtest=RestSchedulePolicyTest` | 9/9 PASS ✅ |
| `verify-rest.js` 连跑两轮（trip 22 活动/3 休息、20 活动/1 休息） | 28/28、18/18 PASS ✅ |
| `verify-variant.js`（rest 排除后复跑） | 16/16 PASS ✅ |
| `verify-d.js` 回归 | 22/22 PASS ✅ |
| `npm run build` → `robocopy /MIR` → `mvn -o -q package -DskipTests` → `start-all.ps1` | BUILD OK、PACKAGE OK，8081-8086 TCP 探测 6/6 UP ✅ |
| 导航按钮人工点击（ShareView/时间轴/地图） | **未人工验证** ⚠️ |

---

## [1.18.0] - 2026-09-28

### 修复

- **`isExcludedName` 双向 contains 误伤长名称景点**：
  - 现象：换版排除「西湖」时，`西湖文化广场`/`西湖银泰` 等因包含「西湖」被一并排除——排除清单命中范围超出语义预期；反之排除「南宋德寿宫遗址博物馆」时短词表不易命中全称
  - 原因：旧实现为双向 `String.contains`（排除词 in 候选名 或 候选名 in 排除词），2 字核心词（西湖/灵隐）作为子串可命中大量无关地点
  - 修复：`isExcludedName` 重写为「等值 + 有条件前缀」——① 全名等值；② `stripDecorations` 剥 `（）()【】[]` 括号装饰后 bare 名等值；③ 双向**前缀**匹配且**短名 ≥3 字**才生效（「西湖」不足 3 字不触发前缀，「楼外楼(湖滨店)」bare=楼外楼可命中）；逻辑随 `VariantExclusions` 静态工具类抽取并新增 14 用例单测覆盖边界
  - 文件：`plan-service/.../agent/VariantExclusions.java`（自 TripPlanningAgent 抽取）、`plan-service/src/test/.../VariantExclusionsTest.java`

- **换版排除未贯穿 `ensureCompleteItinerary` 候选源**：
  - 现象：1.17.0 已将 `removeExcludedFromPool` 接入 `fillPaceGaps`，但 `ensureCompleteItinerary` 的 `cityPool`（静态城市景点库）仍可能插入基准版本已用景点——排除存在漏网路径
  - 修复：`ensureCompleteItinerary` 增 `excludePois`/`rawInput` 参数（`ensureCompleteStep` 同步传参），`cityPool` 构建后同样过滤——**所有候选源（fillPaceGaps/refillPaceGaps/ensureComplete）统一贯穿**
  - 文件：`plan-service/.../agent/TripPlanningAgent.java`

- **E2E `verify-d.js` D-2 断言硬编码「楼外楼」与换版后数据失配**：
  - 现象：1.18.0 回归跑 D-2 四项全 FAIL（`meal=undefined`）——T3 当前版本已是换版产物（v6/v7 午餐=牛New寿喜烧），断言按名称 `includes('楼外楼')` 找餐次必落空
  - 修复：断言数据无关化——取「首个 `rating>0` 的餐次，兜底首个餐次」，rating 断言改为 `0<rating≤5` 区间（原硬编码 4.5）；地址/坐标断言随取到的餐次生效
  - 文件：`C:\Users\meng\AppData\Local\Temp\opencode\verify-d.js`（临时 E2E 脚本）

- **E2E 测试数据无清理机制，版本无限累积**：
  - 现象：每轮 E2E 都使测试行程版本 +1（回滚行程已累积至 12 个版本），历史断言依赖的版本号漂移、库表膨胀
  - 修复：后端无版本删除 API，改为 E2E 脚本开跑前直接清库——`verify-variant.js` 保留最近 6 个版本（current 永不删）；`verify-rollback.js` 保留 8 个且**永保 v1**（回滚断言依赖）；mysql.exe 经 `child_process.execFileSync` 调用，清理结果打印剩余版本数
  - 文件：`opencode/verify-variant.js`、`opencode/verify-rollback.js`（临时 E2E 脚本）

### 验证方式与结果（2026-09-28）

| 项 | 结果 |
|---|---|
| `VariantExclusionsTest` 14 用例（等值/装饰剥离/短名前缀边界/池过滤/元数据剥离） | **14/14 PASS** ✅ |
| `mvn -o -q test -pl plan-service,trip-service,common-module -am` | **全绿** ✅ |
| `verify-d.js`（D-2 数据无关化后复跑） | **22/22 PASS** ✅ |
| `verify-variant.js`（清理机制生效：清理后剩 6 版本；Jaccard 断言过） | **16/16 PASS** ✅ |
| `verify-rollback.js`（清理生效：剩 7 版本、v1 保护；fork 正常创建并删除） | **17/17 PASS** ✅ |
| `start-all.ps1 -Action tail -Service plan-service`（UTF-8 日志读取入口） | 语法 0 错、输出正常 ✅ |

---

## [1.17.0] - 2026-09-27

### 修复

- **换版规划排除不生效：基准版本旧景点在新版本再现**：
  - 现象：`verify-variant.js` 断言「rawInput 未提到的旧景点不再出现」连续失败——v4 与 v3 景点集合 4/4 完全相同（雷峰塔再现）；日志显示 `enforceVariantExclusions` 已执行但「替换景点 0 个」
  - 原因（三重）：① LLM 按自身知识补齐景点，绕过提示词软约束（`day1 缺口填充` 日志证实另有来源）；② `fillPaceGaps`/`refillPaceGaps` 用未过滤的 `buildCityPool(city)`（静态城市景点库，含雷峰塔），且在**清洗之后**执行，直接插回排除项；③ LLM 异常回退路径（catch 块）完全没有换版清洗
  - 修复（三层加固）：① **候选池层**：`augmentVariantPlaces` 候选上探至 `totalDays*maxVisits+8` 并留存 `variantRepairPool`；② **提示词层**：variantBlock 增加「系统会在输出后强制校验并替换」告知；③ **输出层**：`enforceVariantExclusions` 硬清洗——违规 visit 换候选池合规地点（清 lat/lng/address/rating/cost 残留，交后续真实路网重新地理编码）、违规餐段 `searchNearby` 换餐厅（失败退化为纯「午餐/晚餐」前缀）；另新增 `removeExcludedFromPool` 进 `fillPaceGaps` 候选池（rawInput 提到的主题地点保留），回退路径补调清洗
  - 文件：`plan-service/.../agent/TripPlanningAgent.java`

- **版本对比视图活动名称全部空白**：
  - 现象：`VersionCompareView` 中两列活动名均为空，diff 状态与统计全错
  - 原因：trip-service `TripVersionResponse` 直接返回存储 JSON 原样（**snake_case** `poi_name`），组件直接读 `activity.poiName` 恒为 undefined
  - 修复：组件内统一经 `normalizeActivities`（`utils/activity.ts`）转换为 camelCase 后再渲染，与时间轴等既有消费方一致
  - 文件：`frontend-new/src/components/version/VersionCompareView.vue`

- **`SloganService.isGeneric` 漏判「动词+景点名+后缀」泛化文案**：
  - 现象：`verify-d.js` D-3 失败——LLM 在新版本存入「游览西湖美景」，读路径判定为非泛化未替换，E2E 泛化检查不通过
  - 原因：`isGeneric` 剥离动词后剩「西湖美景」≠ 核心名「西湖」，等值判定不命中；随后 `s.contains(core)` 规则将其判为非泛化
  - 修复：动词剥离标记 `verbStripped`，剥离后以核心名开头（`游览西湖美景`/`逛河坊街夜市`）判泛化；**未带动词的点题句**（「张生记，一城风味落座」）不受影响（避免误伤词库外好文案）；读时自动替换为词库签名（西湖→「一半湖山一半城」）
  - 文件：`trip-service/.../service/SloganService.java`、`trip-service/src/test/.../SloganServiceTest.java`（新增 1 用例）

### 验证方式与结果（2026-09-27）

| 项 | 结果 |
|---|---|
| `verify-variant.js`（换版全链路 14 断言，历经 3 轮修复迭代 13/14→12/14→**14/14**） | **14/14 PASS** ✅ |
| 关键断言：景点集合≠基准（v5{西湖,灵隐寺,龙井村,河坊街}→v6{西湖,断桥,灵隐寺,雷峰塔,河坊街}，龙井村被排除替换、主题三景保留） | ✅ |
| `verify-d.js` D 类回归（复跑） | **22/22 PASS**（D-3 西湖→「一半湖山一半城」）✅ |
| `verify-rollback.js` 回归（复跑） | **17/17 PASS** ✅ |
| `mvn -o test -pl common-module,trip-service,plan-service -am` | **全绿**（Slogan 20 + CityOwnership 15）✅ |
| 非变体路径影响面 | `removeExcludedFromPool` 对空排除列表直接 no-op ✅ |

---

## [1.16.3] - 2026-09-27

### 修复

- **时间轴卡片悬浮按钮（定位/交换位置）与时长徽标重叠**：
  - 现象：`ActivityCard` 悬停时右上角浮现的「在地图中查看」「交换位置」两个按钮（`absolute top-3 right-3`，h-7×2+gap≈64px 宽）直接盖住右列顶部的时长徽标（如「1小时」只露出末字），并紧压下方「必去/推荐」优先级徽标
  - 原因：操作按钮与右列徽标（`durationText`、priority badge，`items-end` 顶对齐，占 top 16~70px）共用右上角同一区域，两者无布局隔离
  - 修复：按钮容器移至 `bottom-3 right-3`（右下角）——右列徽标仅占卡片顶部，右下恒为空白区，最矮卡片（≈150px+）下按钮顶部 ≈114px 仍高于徽标底边 ≈70px，无重叠可能；左侧文本列在 flex 行中不越过右列宽度，运输提示行也不受干扰
  - 文件：`frontend-new/src/components/timeline/ActivityCard.vue`（仅 class 调整）
  - 说明：`TripCard` 同款 `top-3 right-3` 覆盖的是封面图（状态徽标/菜单无文本冲突），不属本问题，未改动

### 验证方式与结果（2026-09-27）

| 项 | 结果 |
|---|---|
| `npm run build`（vue-tsc 类型检查 + vite） | **通过** ✅ |
| `robocopy dist → gateway static /MIR` + `mvn -o package -DskipTests` + 重启 | 8081-8086 全 UP ✅ |
| 线上产物 `TripDetailPage-DDdwwQVj.js`：`bottom-3 right-3` ×2、`top-3 right-3` 残留 ×0 | ✅ |

---

## [1.16.2] - 2026-09-27

### 修复

- **fork 分支新行程被标记 `draft`，规划页会误触发重新规划**：
  - 现象：`POST /api/trips/{id}/versions/{versionNum}/fork` 创建的新行程与新版本状态均为 `draft`；打开 `PlanningPage` 时第 51 行（仅认 `planned`/`completed`）不命中，逻辑落到第 96 行 `triggerNewPlan()`，对已带完整活动副本的分支行程**重新发起一次 AI 规划**，违背 fork 意图；Dashboard 亦显示为「草稿」
  - 原因：`TripVersionService.forkFromVersion` 硬编码 `setStatus("draft")`（行程、版本各一处），而同文件 `rollbackToVersion` 复制完整行程时设的是 `completed`——同一语义（副本即成品）两种状态，fork 属遗漏
  - 修复：`forkFromVersion` 行程与版本状态均改为 `completed`，与回滚路径对齐
  - 文件：`trip-service/src/main/java/com/tripplanner/trip/service/TripVersionService.java`

### 背景说明

- 本次修复由 1.16.1 修复 `getVersionByNum` 后**首次 E2E 走通** `rollback`/`fork` 两路径时发现（此前两接口被 404 挡死，逻辑从未实际执行过）

### 验证方式与结果（2026-09-27）

| 项 | 结果 |
|---|---|
| `verify-rollback.js`（版本详情 by num / 回滚到 v1 / fork→清理，共 17 断言） | **17/17 PASS** ✅ |
| 其中关键断言：回滚版 6 活动+slogan、currentVersionId 指向新版本、内部版本详情读回滚版、fork 新行程 `status=completed`、fork 版本 6 活动、404 兜底 | 全部通过 ✅ |
| `verify-d.js` D 类回归（重建后复跑） | **22/22 PASS** ✅ |
| `mvn -o test -pl common-module,trip-service` | **34/34 通过** ✅ |
| 数据清理：删除本轮重复回滚产物 v9（孤儿版本），测试行程保留 v1/v3/v6/v7/v8/v10 | ✅ |

---

## [1.16.1] - 2026-09-27

### 修复

- **按版本号获取版本详情永远 404（详情/回滚/分支三个接口全挂）**：
  - 现象：`GET /api/trips/{id}/versions/{versionNum}` 返回 `404 版本不存在: 1`，该 trip 明明存在 v1；`rollback`、`fork` 走同一查找逻辑，一并失效
  - 原因：`TripVersionController.findVersionIdByNum` 把版本号当主键查——`versionService.getVersion(tripId, versionNum + "")` 传入 `"1"` 当 versionId → `findById("1")` 命中不到 → 抛 notFound（代码注释自认「这里简化，实际需要按 versionNum 查询」）
  - 修复：`TripVersionService` 新增 `getVersionByNum(tripId, versionNum)`（走已有 `TripVersionRepository.findByTripIdAndVersionNum`，未命中抛 `notFound("版本", "v"+num)`）；控制器三处调用点（getVersion/rollback/fork）改为直调该方法，删除有缺陷的辅助方法，同时消除原来的二次查询
  - 文件：`trip-service/.../controller/TripVersionController.java`、`trip-service/.../service/TripVersionService.java`

### 同轮变更（测试补齐 + 数据清理）

- **补齐仓库缺失的单元测试**（此前 0 个测试）：
  - 新增 `SloganServiceTest`（19 用例）：词库精确/模糊/餐次前缀匹配、同 seed 稳定性、多签轮换、单签与无 seed 确定性、最长关键词特征模板（青岛老城区/小鱼山公园回归用例）、餐饮模板、类型兜底、`isGeneric` 九类判定
  - 新增 `CityOwnershipUtilsTest`（15 用例）：无城市词返回 null（禁默认北京回归）、路名误判（广州北京路）、景点反查、归属校验、跨城过滤 visitTotal 语义、overwhelmed 判定
  - `common-module/pom.xml` 补 `spring-boot-starter-test`（test scope）
- **清理测试产生的坏数据**：删除 trip `01bda8f8…` 的 3 个空活动版本（v2/v4/v5，历史轮次求解失败仍被标记 completed，会误导版本切换 UI）；全库 `JSON_LENGTH(activities)=0` 现为 0

### 验证方式与结果（2026-09-27）

| 项 | 结果 |
|---|---|
| `mvn -o test -pl common-module,trip-service` | **34/34 通过**（Slogan 19 + CityOwnership 15）✅ |
| `verify-d.js` D 类 API 全链路（摘要卡/评分人均地址/签名/交通/分享/版本详情） | **22/22 PASS** ✅ |
| 版本详情 `GET …/versions/1` | 由 404 → 200，v1 保留 6 活动且 slogan 全量生成 ✅ |
| 分享读路径 `GET /api/trips/shared/{token}` | 200，slogan 正常（读路径同走 `toResponse`）✅ |
| 卡死 `planning` 版本计数 | 0 ✅ |

---


（与 `docs/CHANGELOG.md` 同版本：以景点签名功能增强为主，同时修复其长期失效的缺陷。）

### 修复

- **景点词库签名从未生效，前端 slogan 全是 LLM 泛化 notes**：
  - 现象：行程详情里西湖显示「游览西湖」、灵隐寺显示「参观灵隐寺」、雷峰塔直接显示「雷峰塔」，词库中「淡妆浓抹总相宜」等约 185 条景点签名从未出现
  - 原因：`TripService` 创建行程时把 LLM 的 `notes` 拷贝为 `slogan`（TripService.java:163/:554），而 `TripVersionService.toResponse` 只在 slogan **为空**时才回填词库 —— 非空的泛化 notes 把词库永久挡住；且词库为单条静态映射，无个性化可言
  - 修复：`toResponse` 改为「缺失**或泛化**则按景点重新生成」，泛化判定 `SloganService.isGeneric` 区分「游览西湖/漫步/观海景」（换）与「骑行古城墙/张生记，一城风味落座」（保留）；多签轮换 seed 取 `tripId`
  - 文件：`trip-service/.../service/{SloganService,TripVersionService}.java`

- **特征模板关键词误判**：
  - 「青岛老城区」因含「岛」命中海岛组出「海风翻过青岛老城区」、「小鱼山公园」因含「山」命中山岳组出「山高人为峰」
  - 修复：改为**最长关键词优先**匹配（「老城」「公园」胜过「岛」「山」），海岛模板措辞改为「{name}，海风正好」避免「海风翻过海边」类病句
  - 文件：`trip-service/.../service/SloganService.java`

### 验证方式与结果（2026-09-26）

`node slogan-check.js`（打 gateway API）：4 个行程 21 条活动全部输出景点专属签名；同行程两次请求 100% 一致；跨行程同景点出不同签；原泛化文案（游览西湖/漫步/观海景/逛老街/了解啤酒文化）全部消除。详细表格见 CHANGELOG 同版本条目。

---


（与 `docs/CHANGELOG.md` 同版本：本轮以功能增强为主、同时修复实现过程中的缺陷，两文件条目对齐。）

### 修复

- **反馈重规划「求解全灭 / 版本空 activities / 卡 planning」系列缺陷**（核心根因链）：
  - `HeuristicSolver.tryInsertBest` 可行性判定双重减时长：`feasibleEnd = min(latestMin - preferred, nextStart - travel)`，精确贴合时间窗的活动被判不可行 → `scheduledCount=0` → 落库 0 活动。修复：`latestStart` 与 `endLimit` 分开判断
  - `loadBaseProblem`/`doPersist` 时间窗按 tripStart 时刻对齐会压缩可用窗口。修复：统一锚定 **day1 00:00**，`endMin = max(spanDays*1440, maxActivity.latestMin+60)`
  - 子问题矩阵按列表下标索引而基础版本 `seq` 为 1..n → `ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 5`。修复：`buildSubProblem` 排序后重编 `seq=0..n-1` 并按重编列表重建矩阵；`mergeSolutions` 改按 **id** 匹配固定活动（对象身份因重编 seq 不再成立）
  - `MODIFY_DURATION` 延长时长未放宽最新结束时刻/下游时间窗 → 延长超窗活动被丢弃。修复：`applyDurationChange` 按 delta 顺延本活动 `latestMin`，下游受影响活动 earliest/latest 同步顺延
  - `parseFeedback` 此前在 baseProblem 未加载时调用致 NPE。修复：先 `loadBaseProblem` 再解析；矩阵为 null 时按城市调用 `geocodeClient.getDistanceMatrix` 兜底
  - 持久化坐标顺序颠倒（`lat=经度`）与 `poiAddress` 默认值写成字符串 `"poiAddress"`。修复：`{lat,lng}` 顺序纠正、`pickStr(..., "")` 空默认
  - 文件：`planning-worker/.../solver/{HeuristicSolver,IncrementalReplanner}.java`、`service/{PlanningOrchestrator,ResultPersistService}.java`、`consumer/ReplanJobConsumer.java`

- **跨城污染：无城市词时默认按北京处理**：
  - 原 `extractCity` 匹配失败返回「北京」，导致海边/青岛文本生成北京系景点。修复：删除默认兜底返回空串，城市来源显式标注 `citySource`，`RouteService` 城市为空回退 `drive`
  - 文件：`plan-service/.../util/CityOwnershipUtils.java`、`plan-service/.../service/RouteService.java`

- **餐厅评分链路缺失**（评分/人均/地址全空）：`TripService` 未透传 `rating`/`cost`、活动行未写 `poi_address`。修复见 CHANGELOG A 条与 `ResultPersistService.toActivityMap`
- **测试数据遗留**：历史轮次失败留下的 `status=planning` 卡死版本（v3/v6）SQL 置为 `failed`，当前 `planning` 计数 0

### 验证方式与结果（2026-09-26）

全量 E2E `verify.js`（3 个新行程 + 1 次重规划）+ `verify2.js` 复测：重规划 `changes=REMOVE:act3,MODIFY_DURATION:act1`，最终版本 5 活动（灵隐寺已删、西湖 180 分钟），`visitTotal=3`、无坐标 0、trip `completed`；服务日志无 NPE/AIOOBE。部署链同 CHANGELOG。

---


### 修复

- **登录/注册错误横幅把页面撑坏（图标巨大、文字竖排、表单像「缺失」）**：
  - 现象：提交失败后错误横幅内出现一个占满宽度的红色感叹号圆图，右侧「邮箱或密码错误」被挤成一列竖字，横幅高达数百像素，把下方按钮/其它字段顶出可视区
  - 原因：横幅图标 class 写了 `h-4.5 w-4.5`，**Tailwind 3.4 默认间距刻度里没有 4.5**，类不生成 → `<svg>` 无尺寸约束，配合 `shrink-0` 占满容器
  - 修复：改为 `h-4 w-4 mt-0.5 shrink-0`，文字容器加 `flex-1 min-w-0 break-words`、横幅加 `leading-snug`（两处：登录/注册）
  - 文件：`frontend-new/src/views/AuthPage.vue`

- **注册页密码规则提示触发 vue-i18n 编译错误（文案可能显示异常）**：
  - 现象：控制台反复出现 `Message compilation error: Invalid linked format` / `Unexpected empty linked key`
  - 原因：`auth.passwordHint` 文案里直接写了 `(@$!%*?&)`，`@` 是 vue-i18n **linked message 语法**，`$!%*?&）` 又被当成非法 token，整条消息编译失败
  - 修复：用 i18n 字面量写法 `{'@$!%*?&'}` 包裹特殊字符（中英文两份都改）
  - 文件：`frontend-new/src/i18n/zh-CN.json`、`frontend-new/src/i18n/en-US.json`

- **过期 token 导致「注册页打不开 / 无法注册」**：
  - 现象：`/register` 打开后被立刻重定向到 `/dashboard`，或反之无法回到登录/注册页
  - 原因：路由守卫只用 `localStorage` 里 **token 是否存在** 判断登录态，不校验过期时间；残留的过期 token 会让 `meta.guest` 页面一律跳走（日志里可见浏览器曾用失效 token 请求 `/api/auth/refresh` → `REFRESH_TOKEN_REVOKED`）
  - 修复：新增 `isTokenAlive()` 解析 JWT `exp`（容错 5 秒，非 JWT 视为有效），过期即清除 `tf_token`/`tf_refresh`；守卫按「有效 token」判断 `requiresAuth` / `guest`
  - 文件：`frontend-new/src/router/index.ts`

### 验证结果（2026-09-26）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| Vue SSR 渲染 `/login` | PASS | 3 个 input（邮箱/密码/记住我）、提交按钮、邮箱 label 齐全 |
| Vue SSR 渲染 `/register` | PASS | **4 个 input**、用户名/邮箱/密码/确认密码 label 齐全、密码规则提示存在、1 个提交按钮；**无 `Message compilation error`**（修复前必现） |
| 构建产物 CSS | PASS | 含 `.h-4{}`、`.break-words`、`.bg-danger-50`，**不含 `h-4.5`** |
| `npm run typecheck` / `npm run build` | PASS | vue-tsc 0 错误；产物 `index-DPUia3nK.js` + `AuthPage-CjArL8EP.js` |
| `robocopy` + `start-all.ps1 -Action restart -Build` | PASS | exit=3 / 6 服务全部 UP |
| 网关首页 | 200 | 返回 `assets/index-DPUia3nK.js`（新包已进 jar） |
| 新包横幅类名 | PASS | 网关上的 `AuthPage-CjArL8EP.js` 含 `h-4 w-4 mt-0.5 shrink-0` |
| `POST /api/auth/register`（全新邮箱） | PASS | 200，返回 accessToken |
| 紧接着 `POST /api/auth/login`（新账号） | PASS | 200 |
| 弱密码注册 | PASS | 400 `details.password="密码必须包含大小写字母、数字和特殊字符"` |

## [1.14.4] - 2026-09-26

### 修复

- **无法注册账号（注册必返 500「服务器内部错误」）**：
  - 现象：`POST /api/auth/register` 只要通过参数校验就返回 `500 INTERNAL_ERROR`，新用户永远注册不上；登录 / 邮箱重复 / 密码不一致等失败场景返回正常
  - 原因：本机 `trip_planner` 库的 `user_preferences` 表是**旧版 DDL 建的**，只有 `transport_mode / budget_level / pace / created_at` 等老列，缺少 `intensity / dietary_tags / accessibility / home_location / work_location / preferred_transport_modes`（及实体的 `version`）。`UserService.register` 在事务内调用 `UserPreferenceService.initDefaultPreference`，MyBatis 插入带 `intensity` 报 `SQLSyntaxErrorException: Unknown column 'intensity' in 'field list'`，整事务回滚 → 500
  - 附带发现：同一漂移下 `planning_tasks` 表在本机库**从未创建**（plan-service 的 `GET /api/plan/tasks/{id}`、`GET /api/plan/trips/{id}/tasks` 会 1146 报错），`init-sql/01_schema.sql` 里有定义但线上库没有
  - 修复：
    1. 新增迁移脚本 `init-sql/04_migration_20260926.sql`（由 `01_schema.sql` 的 DDL 提取生成）：`CREATE TABLE IF NOT EXISTS planning_tasks`（3 个外键 + 4 个索引）+ 7 条 `ALTER TABLE user_preferences ADD COLUMN ...`
    2. 已对本机 `trip_planner` 执行该脚本（exit=0），列/表结构复核通过
    3. `pois / audit_logs / system_configs` 经全局检索**无任何 Java 代码引用**，本轮不建（避免引入未使用对象）
  - 文件：`init-sql/04_migration_20260926.sql`（新增）

- **登录/注册失败时提示丢失或显示异常**：
  - 现象：输错密码本应提示「邮箱或密码错误」，但页面可能整页刷新跳回 `/login`（Toast 被刷掉）；断网/超时显示英文 `Network Error` 或空白
  - 原因：
    1. `api/index.ts` 响应拦截器对**所有 401** 都走「刷新 Token → 失败则 `window.location.href='/login'`」，而登录/注册接口的 401 是业务失败（`INVALID_CREDENTIALS`、`EMAIL_ALREADY_EXISTS`、`PASSWORD_MISMATCH`）
    2. 错误只提取了 `error.message`，没提取字段级 `error.details`；网络错误、超时（`ECONNABORTED`）、无 body 的 4xx/5xx 没有兜底文案
    3. Token 刷新**成功**时，排队请求被 `processQueue(null)` 以 `null` reject，调用方拿到非 Error 对象
  - 修复（`frontend-new/src/api/index.ts`）：
    - 新增 `ApiError`（`message/code/status/details`）与 `toApiError()`，所有失败统一归一化：网络错误→「网络异常，请检查网络连接后重试」、超时→「请求超时，请重试」，400/401/403/404/409/429/5xx 均有中文兜底文案
    - 认证类请求（`/api/auth/(login|register|refresh|logout)`）的 401 **不再**触发 Token 刷新与强制跳转
    - `processQueue` 刷新成功改为 resolve 放行（排队请求按原逻辑重试），失败 reject 归一化后的 `ApiError`
  - 文件：`frontend-new/src/api/index.ts`

### 增强（同步登记于 `docs/CHANGELOG.md`，版本号一致）

- **登录/注册失败可见、可定位**（`frontend-new/src/views/AuthPage.vue`）：
  - 新增表单级错误横幅（提交按钮上方），显示后端 `message` 或网络/超时兜底文案，Toast 同步提示
  - 字段级校验错误：把 `error.details` 回填到对应输入框（邮箱格式、密码规则、两次密码不一致等直接标红在输入框下）
  - 注册密码前端校验对齐后端 `RegisterRequest` 规则（8-72 位、大小写+数字+特殊字符 `@$!%*?&`），输入框下方常显密码规则提示；登录不再用「至少6个字符」拦截（后端仅要求非空）
  - 输入即清除该字段错误与横幅，切 Tab 时重置
- **i18n 文案**：`zh-CN.json` / `en-US.json` 新增 `auth.passwordHint`、`auth.loginFailed`、`auth.registerFailed`

### 验证结果（2026-09-26）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| 执行 `init-sql/04_migration_20260926.sql` | PASS | exit=0；`user_preferences` 现 17 列（新增 `intensity` 等 6 列 + `version`）、`planning_tasks` 含 3 外键 + 4 索引 |
| `POST /api/auth/register`（新邮箱，密码 `Test123456@`） | PASS | **200**，返回 accessToken/userId（修复前 500） |
| 重复邮箱注册 | PASS | 401 `EMAIL_ALREADY_EXISTS`「邮箱已被注册」 |
| 非法邮箱注册 / 登录 | PASS | 400 `VALIDATION_ERROR`，`details.email="邮箱格式不正确"` |
| 弱密码注册 | PASS | 400，`details.password="密码必须包含大小写字母、数字和特殊字符"` |
| 两次密码不一致 | PASS | 401 `PASSWORD_MISMATCH`「两次输入的密码不一致」 |
| 密码错误登录 / 不存在邮箱登录 | PASS | 401 `INVALID_CREDENTIALS`「邮箱或密码错误」（归一化为 ApiError，不再触发刷新跳转） |
| 正常登录 | PASS | 200，`admin@tripplanner.com` 拿到 token |
| `GET /api/plan/trips/{id}/tasks`（经 8086） | PASS | 200 空数组（建表前会 1146） |
| `npm run typecheck` / `npm run build` | PASS | vue-tsc 0 错误；产物 `assets/index-4MXxMLi4.js` |
| `robocopy dist → gateway/static /MIR` | PASS | exit=3；static `index.html` 指向 `index-4MXxMLi4.js` |
| `start-all.ps1 -Action restart -Build -NoBrowser` | PASS | 停 → 打包 → 起 → 6 服务全部 UP |
| 网关首页 | 200 | 返回 `assets/index-4MXxMLi4.js`（新前端已进 gateway jar） |
| 重启后 auth-service 日志 | PASS | `ERROR` / `Unknown column` 均为 0 条 |

## [1.14.3] - 2026-09-26

### 修复

- **没有可靠的一键启动方式（旧 `run.bat` / `restart-all.ps1` 启动即坏）**：
  - 现象：想启动整个项目只能逐个拼命令，或用旧脚本启动后 AI 规划不可用、中文日志乱码、关掉控制台服务跟着被杀
  - 原因：
    1. `run.bat` 只设了 `AMAP_API_KEY`，**没传 LLM Key**（plan-service 规划必挂）、没设 UTF-8 编码、服务工作目录在项目根而非各自模块、jar 缺失时不提示构建方式
    2. `restart-all.ps1` 用 `Start-Process cmd /c …` 起进程，父控制台一关（或 Ctrl+C）子进程全灭；日志写死为模块内 `stdout.log`，且无 `-Dlogging.file.name`，stdout 被块缓冲经常是空的
    3. `stop.bat` 是 `taskkill /IM java.exe /F`，会把机器上**所有** Java 程序一起杀掉
  - 修复：新增统一启动脚本
    - `start-all.ps1`：`-Action start|stop|restart|status`，`-Build`（构建后端）、`-BuildFrontend`（前端构建并同步 gateway static）、`-NoBrowser`、`-SkipInfra`、`-WaitSeconds`
      - 基础设施自检：Windows 服务 `MySQL80`/`Redis` 未运行则拉起，检查 :3306/:6379；Docker 缺 `zookeeper`/`kafka` 时 `docker compose up -d mysql redis zookeeper kafka` 并等 :9092
      - jar 缺失自动构建；构建顺序固定 **先前端 → robocopy 同步 static → 再打包后端**（新静态资源才会进 gateway jar），且**构建前会先停掉本项目服务**（否则 jar 被 JVM 占用报 `Unable to rename *.jar`）；Maven 查找顺序：`MVN_CMD` → PATH `mvn.cmd` → 递归搜 `~/.m2/wrapper/dists/**/mvn.cmd` → `.mvn/wrapper/maven-wrapper.jar`
      - 启动：只杀**本项目**的 Java 进程（命令行含项目根路径且带 `-jar`），逐服务 `Start-Process -WorkingDirectory <模块> -WindowStyle Hidden` 独立进程（脱离当前控制台，关窗口服务不掉）
      - 每个服务注入 `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Damap.api-key=…`，plan-service 额外注入 `-DLLM_API_KEY`/`-DZHIPU_API_KEY`，并注入 `-Dlogging.file.name=logs\<服务名>-spring.log`（**必须在 `-jar` 之前**，否则被当成程序参数）
      - 健康轮询（`/actuator/health`，回退 `/`，最多 150s）后输出状态表与 URL，日志集中在 `logs\`
    - `start.bat`：双击即可启动（参数透传给 ps1）
    - `run.bat` 改为转发到 `start-all.ps1`（保留原入口，不再走旧的坏逻辑）；`stop.bat` 改为 `-Action stop`，只停本项目进程
    - **联调中修掉的 4 个实现坑**（均在首次实跑时暴露）：
      1. `robocopy /NJW` 在本机是**无效参数**（报 `无效参数 #8: "/NJW"` 并以 exit=16 失败）→ 去掉 `/NJW`
      2. Maven 路径探测只递归了 2 层，而真实结构是 `dists/<dist>/<hash>/apache-maven-x.y.z/bin/mvn.cmd`（3 层）→ 改为 `-Recurse -Filter mvn.cmd`
      3. PowerShell 调 wrapper 时 `-Dmaven.multiModuleProjectDirectory=$Root` 未加引号被拆参，报 `ClassNotFoundException: /multiModuleProjectDirectory=…` → 整体加引号
      4. `.bat` 里写中文 + 无 BOM 的 UTF-8 会被 cmd 按 GBK 解析，把中文字节里的 `|`/`&` 当命令分隔符（执行时冒出 `xxx 不是内部或外部命令`）→ 三个 `.bat` 改为纯英文，中文只留在带 BOM 的 `start-all.ps1` 里
  - 文件：`start-all.ps1`（新增）、`start.bat`（新增）、`run.bat`、`stop.bat`

- **planning-worker 启动后永远是 DOWN（历史遗留问题的根因）**：
  - 现象：每次 `restart-all.ps1` 都报 `planning-worker: DOWN`，端口 8084 有监听但 `/actuator/health` 一直失败
  - 原因：`planning-worker/src/main/resources/application.yml` 数据源默认值写成 `localhost:3307` + 用户 `trip_planner`（指向 Docker MySQL 且账号与 `.env` 不匹配），日志报 `Access denied for user 'trip_planner'@'172.19.0.1'`；其余 5 个服务默认都是本机 MySQL80 `3306` + `root`
  - 修复：数据源默认值改为 `3306` + `root` / `12345678mxy`，与其余服务一致（仍可用 `MYSQL_*` 环境变量覆盖）
  - 文件：`planning-worker/src/main/resources/application.yml`

### 验证结果（2026-09-26）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `start-all.ps1 -Action start -NoBrowser` | PASS | 全流程 41 秒，6 个服务全部 UP，exit=0 |
| `start-all.ps1 -Action stop` | PASS | 精确停止 6 个本项目 Java 进程（PID 来自命令行匹配），`java` 进程归零，8081-8086 全部释放，不动其他 Java 程序 |
| `start-all.ps1 -Action restart -NoBrowser` | PASS | 停→自检→起→健康检查 50 秒完成，exit=0 |
| `start-all.ps1 -Action status` | PASS | 输出服务/端口/PID/状态表，全 UP 时 exit=0 |
| `start-all.ps1 -Action restart -Build -BuildFrontend -NoBrowser`（完整部署链） | PASS | 停服务 → `npm run build` → `robocopy /MIR` → `mvn -o package -DskipTests`（3.9.16）→ 基础设施自检 → 启动 → 健康检查，65 秒，exit=0；验证了“构建前先停服务”与“前端先于后端打包” |
| `run.bat -Action status`（bat 转发链） | PASS | cmd → ps1 → 中文状态表正常输出，exit=0（.bat 已改纯英文，GBK/UTF-8 解析冲突消除） |
| `/actuator/health` ×6 | PASS | 8081-8086 全部 `200 {"status":"UP"}`，**planning-worker 的 db 组件 UP**（修复前一直 DOWN） |
| 网关首页 | 200 | `assets/index-DaE5TCaw.js` |
| 登录 + 行程列表 | PASS | `POST /api/auth/login` 200 拿到 token；`GET /api/trips` total=145 |
| plan-service 进程参数 | PASS | 命令行含 `-DLLM_API_KEY`、`-Damap.api-key`、`-Dlogging.file.name`（均位于 `-jar` 之前） |
| 中文输出编码 | PASS | 脚本存为 UTF-8 with BOM，PowerShell 5.1 解析中文正常（无乱码） |
| 日志 | PASS | 集中在 `logs\<服务名>-spring.log` / `.log` / `.err.log` |

---

## [1.14.2] - 2026-09-25

### 修复

- **地图上的地标（景点/POI marker）不全、有缺失**：
  - 现象：行程地图上部分景点没有定位点，列表里有、地图上看不到
  - 原因（三重叠加）：
    1. **高德 Key 没注入**：plan-service 当前由 `run.bat` 以 `java -jar …--server.port=808N` 启动，命令行**没有** `-Damap.api-key`（只有 `restart-all.ps1` 传了），`amap.api-key` 为空 → 所有高德调用返回 `INVALID_USER_KEY` → 批量地理编码全挂 → 活动 `lat/lng` 为空，`TripMap.addMarkers` 对空坐标直接跳过
    2. **并发打爆 QPS**：`GeocodeService.batchGeocode` 用 `parallelStream` 并发请求，高德免费版地理编码 QPS=3，整批返回 `CUQPS_HAS_EXCEEDED_LIMIT`（实测 15 词连查仅第 1 批部分成功）
    3. **地址型接口命中率低**：`/geocode/geo` 面向结构化地址，纯景点名常返回 `ENGINE_RESPONSE_DATA_ERROR`
  - 修复：
    1. `run.bat` 注入 `set AMAP_API_KEY=…`（已同时 `setx` 持久化环境变量），保证任何启动方式都带 Key
    2. `GeocodeService`：新增全局限速 `throttleAmap()`（350ms 间隔 ≈ 3 QPS），`callAmapGeocode` / `callAmapRegeocode` 发请求前排队；`batchGeocode` 改为**串行**并保持顺序，遇限流错误退避 1.2s 重试一次（失败不进缓存，可安全重查）
    3. `GeocodeService` 新增 **POI 搜索兜底** `callAmapPlace()`（`/place/text` + `citylimit=true`），地址型 geocode 失败时按景点名取精确坐标，位于「内置坐标表兜底」之前
    4. `RouteService`：`batchRoute` 改串行 + 200ms 限速，避免路径规划接口并发超限导致路程数据拿不到
    5. 前端兜底：`TripMap.vue` 新增 `fillMissingCoordsByPlace()`，对仍无坐标的活动用浏览器端 `AMap.PlaceSearch`（script plugin 补加载 `AMap.PlaceSearch`）补坐标后刷新 marker/连线；`onMounted`、`activities` watch、`city` watch 三处触发，聚焦期间不抢视野
  - 文件：`run.bat`、`plan-service/src/main/java/com/tripplanner/plan/service/GeocodeService.java`、`…/service/RouteService.java`、`frontend-new/src/components/map/TripMap.vue`

- **两个地点之间显示「0分钟」**：
  - 现象：同一天相邻活动之间的交通时长为 0（老数据 236 对里 13 对，模式集中在「餐次 → 下一个活动」）
  - 原因：
    1. LLM prompt 的示例自己写了「午餐 → 王府井 `travelTimeMin: 0`」，模型照抄 0
    2. 活动坐标缺失 → `correctActivitiesWithRealData` 的真实路网修正拿不到起终点被跳过
    3. 前端只透传 `travel_duration_min`，后端为 0 就显示 0
  - 修复：
    1. `TripPlanningAgent` prompt 新增硬规则：**同天相邻 `travelTimeMin` 必须 > 0，仅当天最后一个活动可为 0**；示例改为 `walk / 12min / 0.9km`、`walk / 8min / 0.6km`
    2. `TripPlanningAgent` 新增 `fillZeroTravelTimes()`：坐标齐时按直线距离 ×1.35 绕行 + 交通方式时速（drive 25 / transit 18 / bike 15 / walk 4.5 km/h，clamp 3~240min）回写 `travelTimeMin` / `transportToNext` / `travelDistanceKm`，跨天与当天末段保持 0
    3. 前端估算兜底：`utils/activity.ts` 新增 `activityCoord()` / `haversineMeters()` / `estimateTravelMinutes()` / `effectiveTravelMinutes()`，`DayTimeline.vue` 计算 `travelToNext()` 传给 `ActivityCard`（新增 `travelMin` prop）与 `TransitConnector`，后端为 0 时按直线距离估算展示；`normalizeActivity` 同步补 `travelDistanceMeters`
  - 文件：`plan-service/src/main/java/com/tripplanner/plan/agent/TripPlanningAgent.java`、`frontend-new/src/utils/activity.ts`、`frontend-new/src/components/timeline/DayTimeline.vue`、`frontend-new/src/components/timeline/ActivityCard.vue`

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `POST /api/plan/batch-geocode`（哈尔滨/杭州/大理 × 5 词，15 次） | PASS | 15/15 返回真实坐标，无 `INVALID_USER_KEY`、无 `CUQPS_HAS_EXCEEDED_LIMIT`（修复前：整批失败或超限） |
| `POST /api/plan/map/route`（步行 1.1km） | PASS | `distance=1092m`、`duration=874s`、polyline 正常返回 |
| E2E 中文规划（trip `3438eccb5bc14732a863ce1d101fdc3f`，杭州两日） | PASS | `city=杭州`、landmarks 正确；**14/14 活动有坐标**；同天相邻 12 段中 **0 段为 0 分钟**（0 值仅出现在当天最后一项，符合设计） |
| E2E 英文对照（trip `26e455b9ed73489aa6d0b34e6cf18da5`） | PASS | 12/12 有坐标；同天 10 段仅 2 段为 0 且均为当天末项 |
| `npm run typecheck`（vue-tsc） | PASS | EXIT=0 |
| `npm run build` + `robocopy dist → gateway/static /MIR` | PASS | 产物 `index-DaE5TCaw.js`；chunk 内含 `AMap.PlaceSearch`、`citylimit`（地图补坐标兜底已随包） |
| `mvn -o package -DskipTests` + `restart-all.ps1` | PASS | MVN_EXIT=0；auth/trip/plan/notification/gateway UP（worker DOWN 为已知遗留） |
| 网关 `/` | 200 | 引用 `assets/index-DaE5TCaw.js`，与本次构建一致 |

---

## [1.14.1] - 2026-09-25

### 修复

- **退出登录无效（点「退出登录」仍停在登录后的页面）**：
  - 现象：导航栏头像下拉菜单与移动端侧边栏的「退出登录」点击后无效果，刷新页面依然是登录态
  - 原因：两处退出逻辑删除的是 `localStorage` 的 `accessToken` / `refreshToken`，而本应用真实 token 键是 **`tf_token` / `tf_refresh`**；同时没有清 Pinia `auth` store。结果 `router.push('/login')` 触发路由守卫 `to.meta.guest && token` → 立刻被重定向回 `/dashboard`，表现为“退不出去”
  - 修复：改为调用 `authStore.logout()`（内部调用 `POST /api/auth/logout`、清空 store、删除 `tf_token`/`tf_refresh`），再 `router.push('/login')`，并同步关闭下拉/侧边栏
  - 文件：`frontend-new/src/components/layout/AppNavbar.vue`（`handleLogout`）、`frontend-new/src/components/layout/AppSidebar.vue`（`handleLogout`）

- **个人中心 Tab 指示条（下划线）跑到页面左侧，出现版式偏移**：
  - 现象：`/profile` 页 Tab 行下方的蓝色渐变指示条脱离卡片，悬浮在页面左侧空白处（约 x≈100、y≈Tab 行高度），卡片内容整体观感错位
  - 原因：`UITabs.vue` 指示条是 `absolute`，但组件根节点 `<div class="border-b …">` **没有 `relative`**，其定位上下文逃逸到更外层祖先；而 `left` 是按 `<nav>`（真正 `position: relative` 的元素）算的相对偏移，两套坐标系不一致 → 指示条以 `left≈90px` 落在页面级包含块上；`top` 未设置走静态位置，正好贴在 Tab 行下边框高度
  - 修复：
    1. 根节点加 `relative`，并新增 `rootRef`，`left` 改为相对**组件根节点**计算（`elRect.left - rootRect.left`）
    2. 指示条补 `bottom-0`，稳定贴合 Tab 行下边框
    3. 监听 `window.resize` 并在 `onUnmounted` 移除，避免窗口缩放后指示条错位
  - 文件：`frontend-new/src/components/ui/UITabs.vue`

### 验证结果（2026-09-25）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `npm run typecheck`（vue-tsc） | PASS | EXIT=0 |
| `npm run build` | PASS | 16.00s，`index-mjsKU74v.js` |
| `robocopy dist → gateway/static /MIR` | PASS | exit=3 |
| `mvn -o package -DskipTests` | PASS | MVN_EXIT=0（重打包 gateway 静态资源） |
| `restart-all.ps1` | PASS | auth/trip/plan/notification/gateway UP（worker DOWN 为已知遗留） |
| 网关 `/` | 200 | 引用 `assets/index-mjsKU74v.js`，与本次构建产物一致 |
| 旧退出逻辑已消失 | PASS | 全量静态 chunk 中 `removeItem("accessToken")` 出现 **0** 次 |
| Tab 指示条新逻辑随包可见 | PASS | `ProfilePage-CYdi-xXo.js` 含 `bottom-0 h-0.5` 指示条类 |
| 后端退出接口 | PASS | `POST /api/auth/logout`（带有效 token）→ 200 `{"success":true}` |
| 封面图功能未回归 | PASS | chunk 中仍含 `/api/trips/covers` |

---

## [1.12.1] - 2026-09-25

### 修复

- **页面无法选择活动频率（创建页看不到频率选择器）**：
  - 问题：活动频率功能（1.12.0）后端与前端源码均已就绪，但用户在创建页“无法选择频率”，页面上根本没有该区块
  - 原因：`frontend-new/dist` 与 `gateway/src/main/resources/static` 仍是 09:56 的旧构建产物，而频率选择器是此后才写入源码；gateway 从 jar 内 `classpath:/static` 提供静态资源，源码改动不会自动生效
  - 修复：
    1. `cd frontend-new && npm run build`（`vue-tsc --noEmit` + vite）
    2. `robocopy frontend-new\dist gateway\src\main\resources\static /MIR`（镜像同步，剔除旧 hash 文件）
    3. `mvn -o package -DskipTests` 重打包 gateway
    4. `powershell -ExecutionPolicy Bypass -File restart-all.ps1` 重启服务
  - 文件：`frontend-new/dist/**`、`gateway/src/main/resources/static/**`
  - 验证：网关 `GET /` 返回 200 且引用 `assets/index-Cy0ib_yl.js`；index chunk 含 i18n 文案 `活动频率`、`8~10 小时/天`；`GET /assets/TripCreatePage-CYzmv36z.js` 含三档枚举与 `pace` 提交字段
  - 预防：见 `AGENTS.md §8.3`——功能改动完成后必须同步重建并部署前端产物，且当场登记版本号

---

## [1.12.0] - 2026-09-25

### 修复

- **紧凑档每日游览时长达不到下限（E2E 反复 FAIL，实测 335~439 分钟 / 目标 480 分钟）**：
  - 现象：`pace=compact` 行程每日游览时长仅 5.6~7.3h，且 `visits` 常低于 4
  - 原因（叠加 4 条）：
    1. 缺口填充按“空窗 − 固定缓冲”插入，未计入**真实路程**，插入后被 `fixTimeOverlaps` 顺延推过 21:00，整条活动被裁掉（日志 `时间顺延裁剪超出 21:00 的活动: N 个`）
    2. 顺延只会**向后推**，LLM 原本留下的空隙无法回收，导致尾部溢出
    3. 真实时间定稿后仍留有空隙，但**没有第二轮补时**
    4. 缺口填充放在 `dedupeVisitActivities` 之前时，补入的点会被同名/近似名去重吃掉（须置于去重之后）
  - 修复：
    - `fillDayGaps` 插入起点取 `max(缓冲, 上一活动 travelTimeMin + 5)`（trailing 窗 35 分钟），左右预留 30 分钟，宁可少补也不整条被裁
    - 新增**阶段二「缺口延展」**：窗口装不下新活动时延长其前面的游览活动，预留 `max(30, travelTimeMin + 5)`，不新增交通、不推后结束时间
    - 新增 **`pullDayLeft()`**：`fixTimeOverlaps` 处理溢出前先按真实路程整体前移当日活动（用餐不早于 7:30/11:30/17:30），把空隙挤出来
    - 新增 **`refillPaceGaps()` 二次补时**（真实路网 + 人性化 + 21:00 顺延之后执行），以 `belowPaceFloor()` 判停、最多 3 轮，且在 `refillPaceGaps` 内部先 `fillPaceGaps` 再 `applyPaceBudget` → 再路网校正 → 再顺延
    - `fillDayGaps` 明确放在 `dedupeVisitActivities` / `normalizeDailyMeals` 之后
  - 文件：`plan-service/.../agent/TripPlanningAgent.java`
  - 验证：`e2e-pace.js` 连续 3 轮全量 PASS（紧凑 7.6~8.3h、景点 4~6 个）

- **LLM 收尾占位「结束(57)」被计为游览活动，虚增每日时长与景点数**：
  - 问题：紧凑/适中档行程以 `19:33 结束(57)` 作为 visit 收尾，`visitMinutes`/`visitCount` 把它算进节奏指标，导致指标虚高且用户看到无意义活动
  - 原因：`NON_PLACE_PHRASES` 未覆盖裸「结束」/「行程结束」
  - 修复：列表扩充 `行程结束` / `结束行程` / `结束`，由既有 `replaceNonPlaceVisits()` 换成候选池真实地点或剔除
  - 文件：`TripPlanningAgent.java` `NON_PLACE_PHRASES`

- **宽松档某日游览时长超出上限（330 分钟 > 300 分钟）**：
  - 问题：`relaxed` 行程 day2 = 4 景点 / 5.5h，超过 5 小时上限，景点数也超出 2~3 的标称区间
  - 原因：`applyPaceBudget` 只裁“时长超限”，不裁“景点数超限”；且它在链路中段执行，后续补时轮次可能再次推高
  - 修复：`applyPaceBudget` 循环条件改为 `(visitMinutes > maxMinutes || visitCount > maxVisits) && visitCount > minVisits`（仍不裁午晚餐、优先裁非 `must`）；主/回退路径**最终收口**再跑一次 `applyPaceBudget`
  - 验证：日志 `活动频率预算 dayN: 游览 X 分钟 / Y 个景点, 上限 A 分钟 / B 个景点`；三档 E2E 景点数全部落在标称区间

- **缺口填充反复“补了又被裁”的隐性死循环风险**：
  - 问题：插入 → 路网校正 → 顺延 → 裁剪 → 再补，轮次之间可能互相抵消
  - 修复：补时轮次以 `before/after` 对比判停（无提升即返回原列表），最多 3 轮；`fillDayGaps` 内部 `guard=12`、候选耗尽即 `return`；插入前先判断 `visitCount >= maxVisits` 转入延展分支
  - 验证：`二次节奏补时: 每日游览 X -> Y 分钟` 日志仅出现 1~3 次/次规划，规划耗时仍为 52~96s

---

## [1.11.0] - 2026-09-25

### 修复

- **替换景点后时间不按真实路程顺延**：
  - 问题：`POST /api/trips/{id}/replace` 把「灵隐寺」换成「雷峰塔」后，雷峰塔起点仍为 13:30，而上一活动 13:00 结束、真实路程 35 分钟，应为 13:35
  - 原因：`AlternativeService.replaceActivity()` 只调用 `recomputeAdjacentTravel()` 更新路程字段，未把 `travelDuration` 加到后续活动的 `scheduled_start` 上
  - 修复：新增 `resequenceDayByTravel(activities, activityIndex)`，替换后按天重排，`start_i ≥ end_{i-1} + travel_{i-1}`，并回写 `scheduled_start` / `scheduled_end`（存在 `startTime` / `endTime` 时同步）
  - 文件：`trip-service/.../service/AlternativeService.java`
  - 验证：日志 `替换后顺延: 1 雷峰塔 -> 13:35`；`e2e-distance.js` 输出 `RECALC_OK`

- **行程时间未反映真实路程时间**：
  - 问题：下一起点仅按 LLM 给的 `startTime` 排列，`start_i` 可早于 `end_{i-1} + travel_{i-1}`（实测 5 分钟违例）
  - 原因：`fixTimeOverlaps()` 只做排序与 21:00 规则，未把上一活动的 `travelTimeMin` 计入最早可开始时间
  - 修复：按天分组排序后推进，`earliest = prevEnd + max(0, prevTravel)`，`prevTravel` 取上一活动 `travelTimeMin`，只后推不前拉
  - 文件：`plan-service/.../agent/TripPlanningAgent.java` `fixTimeOverlaps()`

- **回退路径时间规则失效**：
  - 问题：LLM 未生成全部天数时走回退路径，时间与去重规则表现不一致
  - 原因：回退路径中 `fixTimeOverlaps` 排在清洗/去重之前，后续改写会覆盖已修正的时间
  - 修复：回退路径顺序改为主路径一致，`fixTimeOverlaps` 固定为**最后一步**
  - 同步修正：`replaceNonPlaceVisits` 挂在 `sanitizeActivityNames` 之后

- **行程结束时间越过 21:00**：
  - 问题：大理 4 日行程 day1 结束于 22:17（晚餐被推到 21:17-22:17）
  - 原因：原逻辑只截断非用餐活动，用餐仅做 `latest = 21:00 - min(dur,60)` 尝试，当 `latest < prevEnd` 时直接放行，导致整条链被推向深夜
  - 修复：`fixTimeOverlaps` 改为**迭代裁剪**——某活动结束晚于 21:00 时：先截断该活动（剩余 ≥15 分钟）；若是**用餐**被挤出 21:00，则剔除它前面最近的**非用餐**活动腾出时间保住正餐；仍装不下才剔除该活动。每轮必有进展（截断使 `end == 21:00`，或列表变短），循环必然终止
  - 验证：日志 `时间顺延裁剪超出 21:00 的活动: 1 个`；4 日行程各天均 ≤21:00

- **占位文本活动混入正式行程**：
  - 问题：行程中出现「自由活动」「市区漫步」「返回酒店」「待定景点」等活动，既不可执行也无法地理编码
  - 原因：LLM 在地点不足时用描述性文本兜底，后处理未清洗
  - 修复：新增 `replaceNonPlaceVisits()`，命中 `NON_PLACE_PHRASES`（返回酒店/返程/去机场/休息/自由活动/市区漫步/待定景点/`(n-m)` 后缀）的 visit 活动替换为候选池中未用真实地点，无候选则剔除；并在提示词中明令禁止
  - 验证：近期行程 0 占位

- **餐厅坐标被高德泛匹配带偏（18km 误差）**：
  - 问题：`晚餐·南京大牌档(杭州)` 地理编码为 `(120.031913, 30.242111)`，导致「雷峰塔→晚餐」算成 18.8km / 80min，实际应约 5km
  - 原因：`resolveNodeCoord()` 优先调高德 API，带 `(杭州)` 后缀的查询被泛匹配到错误地点；内置校准坐标表仅作为 API 失败后的兜底，永远不生效
  - 修复：`resolveNodeCoord()` 改为**内置餐厅坐标优先**（`lookupRestaurantCoord()` 命中即返回），并新增 `stripPlaceSuffix()` 去掉尾部城市消歧括号后再调 API
  - 验证：坐标变为 `(120.155, 30.251)`，路段 2.5km / 31min；日志 `地理编码命中内置坐标`

- **县城 POI 混入城市行程**：
  - 问题：大理行程中出现「宾川中心1号商业步行街」（距市中心约 25km 的县城地点）
  - 原因：POI 半径过滤阈值 35km 过大，县城也在半径内
  - 修复：`PoiSearchService.MAX_DISTANCE_KM` 35 → 20；同时限制 POI 名称长度 2~20 字符过滤噪声
  - 验证：大理行程地点全部落在市区及近郊（大理古城/喜洲/双廊/蝴蝶泉）

- **通用餐名跨天被误去重**：
  - 问题：`dedupeVisitActivities()` 按名称去重，导致次日的「午餐·XX」因同名被删除，出现「当天无午餐/无晚餐」
  - 原因：去重逻辑对用餐活动未区分「通用餐名」与「具体餐厅」
  - 修复：新增 `isGenericMealName(city, name)`，双方均为通用餐名（`isKnownRestaurant` 为 false、或命中 `isPlaceholderText`、以「美食」结尾、等于城市名+「美食」、等于 `FOOD_KEYWORDS` 单项）时 `continue` 不去重；具体餐厅名仍正常去重
  - 验证：4 日行程每天午餐、晚餐齐全

### 已知遗留（未修复）

- `InlinePlanningService`（trip-service 故障兜底）城市池未补 museum 类条目
- `RouteService` 偶发 `高德公交规划失败: Index 0 out of bounds for length 0`（空路线段解析，已有驾车兜底，不影响结果）
- `planning-worker`（8084）服务 DOWN，为既有遗留状态
- rawInput 描述天数与前端日期区间计算结果不一致时（如「玩两天」但日期跨 4 天），以日期区间为准，需与用户确认

---

## 常见问题排查指南

### 服务与端口

| 服务 | 端口 | 日志 |
|------|------|------|
| auth-service | 8081 | `auth-service/stdout.log` |
| trip-service | 8082 | `trip-service/stdout.log` |
| plan-service | 8083 | `plan-service/stdout.log` |
| planning-worker | 8084 | `planning-worker/stdout.log`（当前 DOWN） |
| notification-service | 8085 | `notification-service/stdout.log` |
| gateway | 8086 | `gateway/stdout.log` |

> 重启：`powershell -ExecutionPolicy Bypass -File D:\agent-trip-planner\restart-all.ps1`
> 构建：先 `Get-Process java | Stop-Process -Force`，再根目录 `mvn.cmd -q package -DskipTests`（存在 jar 锁时必须先停 Java）
> 编译检查：`mvn.cmd -q -pl plan-service -am compile -o`

### 环境

1. **MySQL**：宿主机 `127.0.0.1:3306`，库 `trip_planner`，账号 `root`；Docker `3307` 仅供 planning-worker
2. **Redis**：`127.0.0.1:6379`
3. **中文乱码**：确认 JVM 启动参数包含 `-Dfile.encoding=UTF-8`
4. **Kafka 报错**：可忽略，系统设计为不依赖 Kafka

### 行程数据

- 活动数据存放在 `trip_versions.activities`（非 `activities` 表）
- 关键字段：`poi_name`、`activity_type`、`scheduled_start`、`scheduled_end`、`duration_min`、`travel_duration_min`、`travel_distance_km`
- 查看某行程：`node D:\agent-trip-planner\showtrip.js <tripId>`
- 校验时间/餐次约束：`node D:\agent-trip-planner\check-time.js`（扫描近 2 小时行程版本，输出 `RESULT`/`FAILURES`）
- 多元化校验：`node D:\agent-trip-planner\e2e-diversity.js`（大理 4 日，校验类别数、餐次、时间约束、21:00）

### API

1. **401 未授权** → 检查 Token 是否过期
2. **403 禁止访问** → 检查用户权限
3. **500 服务器错误** → 查看对应服务 `stdout.log`
4. **E2E 走网关** → `http://localhost:8086`，测试账号 `admin@tripplanner.com` / `Admin@123456`
