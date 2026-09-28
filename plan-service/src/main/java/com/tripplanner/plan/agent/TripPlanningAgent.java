package com.tripplanner.plan.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.util.CityOwnershipUtils;
import com.tripplanner.plan.constant.TripPace;
import com.tripplanner.plan.dto.response.GeocodeResponse;
import com.tripplanner.plan.dto.response.RouteResponse;
import com.tripplanner.plan.service.GeocodeService;
import com.tripplanner.plan.service.PoiSearchService;
import com.tripplanner.plan.service.RestaurantSearchService;
import com.tripplanner.plan.service.RouteService;
import com.tripplanner.plan.service.WebSearchService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.tripplanner.plan.agent.VariantExclusions.isExcludedName;
import static com.tripplanner.plan.agent.VariantExclusions.pickVisitRepair;
import static com.tripplanner.plan.agent.VariantExclusions.removeExcludedFromPool;
import static com.tripplanner.plan.agent.VariantExclusions.stripStalePoiMeta;
import static com.tripplanner.plan.agent.VariantExclusions.variantNorm;

/**
 * ReAct Agent Executor
 * 实现推理-行动-观察循环的旅行规划智能体
 */
@Service
public class TripPlanningAgent {

    private static final Logger log = LoggerFactory.getLogger(TripPlanningAgent.class);

    private final WebSearchService webSearchService;
    private final RouteService routeService;
    private final GeocodeService geocodeService;
    private final PoiSearchService poiSearchService;
    private final RestaurantSearchService restaurantSearchService;

    /** 规划并行池：地理编码 / 距离矩阵 / 行程修正 / 多天补全并发执行，缩短整体规划耗时 */
    private final ExecutorService planExecutor = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "plan-parallel");
        t.setDaemon(true);
        return t;
    });

    /** 城市餐厅库: name, lat, lng, tags(逗号分隔口味), type=restaurant */
    private static final Map<String, List<Map<String, Object>>> CITY_RESTAURANTS = new HashMap<>();

    /** 用户可能表达的口味/菜系关键词 */
    private static final List<String> FOOD_KEYWORDS = List.of(
            "火锅", "川菜", "湘菜", "粤菜", "杭帮菜", "苏帮菜", "鲁菜", "闽菜", "徽菜",
            "北京烤鸭", "烤鸭", "涮羊肉", "麻辣烫", "串串", "烧烤", "烤肉", "烤鱼",
            "小龙虾", "生煎", "小笼包", "拉面", "牛肉面", "米线", "螺蛳粉", "酸菜鱼",
            "麻婆豆腐", "东坡肉", "西湖醋鱼", "叫花鸡", "龙井虾仁", "佛跳墙",
            "披萨", "pizza", "意大利面", " pasta", "汉堡", "steak", "牛排",
            "日料", "寿司", "刺身", "韩餐", "炸鸡", "西餐", "东南亚菜", "泰国菜",
            "素食", "清真", "早茶", "小吃", "夜宵", "海鲜", "粥", "茶餐厅"
    );

    static {
        putRestaurants("杭州",
                r("楼外楼", 30.2485, 120.1425, "杭帮菜,西湖醋鱼,东坡肉"),
                r("知味观", 30.2470, 120.1640, "杭帮菜,小吃,早餐"),
                r("新白鹿", 30.2560, 120.1620, "杭帮菜,性价比"),
                r("外婆家", 30.2430, 120.1570, "杭帮菜,家常菜"),
                r("绿茶餐厅", 30.2680, 120.1450, "杭帮菜,绿茶饼"),
                r("弄堂里", 30.2410, 120.1680, "杭帮菜,小吃"),
                r("奎元馆", 30.2450, 120.1660, "面食,杭帮菜"),
                r("南京大牌档(杭州)", 30.2510, 120.1550, "小吃,淮扬菜"));
        putRestaurants("北京",
                r("全聚德", 39.9090, 116.4050, "北京烤鸭,烤鸭"),
                r("便宜坊", 39.8910, 116.3970, "北京烤鸭,烤鸭"),
                r("四季民福", 39.9160, 116.4100, "北京烤鸭,烤鸭"),
                r("东来顺", 39.9370, 116.4030, "火锅,涮羊肉"),
                r("南门涮肉", 39.8700, 116.4000, "火锅,涮羊肉"),
                r("海底捞(北京)", 39.9080, 116.4120, "火锅"),
                r("护国寺小吃", 39.9380, 116.3780, "小吃,早餐"),
                r("局气", 39.9200, 116.4000, "京菜,小吃"));
        putRestaurants("上海",
                r("南翔馒头店", 31.2270, 121.4920, "小笼包,小吃"),
                r("绿波廊", 31.2280, 121.4930, "本帮菜,小吃"),
                r("上海老饭店", 31.2300, 121.4900, "本帮菜,红烧肉"),
                r("海底捞(上海)", 31.2350, 121.4700, "火锅"),
                r("鼎泰丰", 31.2180, 121.4600, "小笼包,点心"),
                r("新荣记", 31.1950, 121.5000, "海鲜,台州菜"));
        putRestaurants("成都",
                r("蜀大侠", 30.6550, 104.0720, "火锅"),
                r("小龙坎", 30.6500, 104.0800, "火锅"),
                r("大龙燚", 30.6650, 104.0750, "火锅"),
                r("陈麻婆豆腐", 30.6600, 104.0600, "川菜,麻婆豆腐"),
                r("马旺子", 30.6480, 104.0680, "川菜"),
                r("明婷饭店", 30.6700, 104.0820, "川菜,家常菜"),
                r("龙抄手", 30.6580, 104.0700, "小吃,早餐"),
                r("廖老妈蹄花", 30.6620, 104.0650, "小吃,蹄花"));
        putRestaurants("西安",
                r("老孙家泡馍", 34.2580, 108.9400, "泡馍,清真"),
                r("春发生", 34.2560, 108.9380, "葫芦头,陕菜"),
                r("贾三灌汤包", 34.2570, 108.9390, "小吃,灌汤包"),
                r("biangbiang面(西安)", 34.2600, 108.9420, "面食,拉面"),
                r("长安大牌档", 34.2520, 108.9500, "陕菜,小吃"),
                r("海底捞(西安)", 34.2450, 108.9550, "火锅"));
        putRestaurants("苏州",
                r("松鹤楼", 31.3100, 120.6250, "苏帮菜,松鼠桂鱼"),
                r("得月楼", 31.3120, 120.6230, "苏帮菜"),
                r("同得兴", 31.3050, 120.6180, "面食,苏式面"),
                r("朱鸿兴", 31.3080, 120.6200, "面食,小吃"));
        putRestaurants("南京",
                r("南京大牌档", 32.0400, 118.7900, "淮扬菜,小吃"),
                r("鸭血粉丝汤(南京)", 32.0350, 118.7850, "小吃,鸭血粉丝"),
                r("韩复兴", 32.0380, 118.7880, "鸭血粉丝,盐水鸭"),
                r("绿柳居", 32.0420, 118.7920, "素食,小吃"));
        putRestaurants("重庆",
                r("珮姐老火锅", 29.5600, 106.5750, "火锅"),
                r("赵二火锅", 29.5550, 106.5800, "火锅"),
                r("山城小汤圆", 29.5620, 106.5780, "小吃,汤圆"),
                r("花市豌杂面", 29.5580, 106.5720, "面食,小吃"));
        putRestaurants("长沙",
                r("火宫殿", 28.1900, 112.9750, "小吃,湘菜"),
                r("文和友", 28.1880, 112.9780, "小龙虾,小吃"),
                r("炊烟时代", 28.1920, 112.9800, "湘菜,小炒黄牛肉"),
                r("茶颜悦色", 28.1910, 112.9760, "奶茶,小吃"));
        putRestaurants("厦门",
                r("沙茶面(厦门)", 24.4550, 118.0820, "沙茶面,小吃"),
                r("姜母鸭(厦门)", 24.4580, 118.0850, "闽菜,姜母鸭"),
                r("海蛎煎(厦门)", 24.4540, 118.0800, "小吃,海鲜"),
                r("临家闽南菜", 24.4600, 118.0880, "闽菜"));
        putRestaurants("广州",
                r("陶陶居", 23.1250, 113.2600, "粤菜,早茶"),
                r("点都德", 23.1280, 113.2650, "粤菜,早茶"),
                r("广州酒家", 23.1200, 113.2700, "粤菜,早茶"),
                r("炳胜", 23.1150, 113.2800, "粤菜"));
        putRestaurants("武汉",
                r("蔡林记", 30.5800, 114.2900, "热干面,小吃"),
                r("老通城", 30.5780, 114.2880, "豆皮,小吃"),
                r("小桃园", 30.5820, 114.2920, "煨汤,小吃"));
        putRestaurants("三亚",
                r("火车头万人海鲜广场", 18.2500, 109.5000, "海鲜"),
                r("第一市场海鲜", 18.2520, 109.5050, "海鲜,小吃"),
                r("海南菜馆(三亚)", 18.2480, 109.5100, "琼菜"));
        putRestaurants("大理",
                r("段公子餐厅", 25.6060, 100.2290, "滇菜,白族菜"),
                r("喜洲粑粑(大理)", 25.6100, 100.1400, "小吃"),
                r("梅子酒馆", 25.6050, 100.2300, "滇菜,梅子"),
                r("杨记饵丝", 25.6070, 100.2250, "小吃,饵丝"),
                r("益恒饭店", 25.6030, 100.2310, "滇菜,老字号"),
                r("大理海稍鱼", 25.6900, 100.1600, "白族菜,鱼"),
                r("古城乳扇小铺", 25.6910, 100.1620, "小吃,乳扇"),
                r("喜洲稻田餐厅", 25.6860, 100.1650, "白族菜"));
        putRestaurants("丽江",
                r("阿妈腊排骨", 26.8700, 100.2340, "腊排骨,火锅"),
                r("滇西小哥", 26.8720, 100.2360, "滇菜"),
                r("纳西烤肉(丽江)", 26.8680, 100.2320, "烧烤,小吃"),
                r("束河粗茶淡饭", 26.9050, 100.2160, "纳西菜"),
                r("丽江粑粑(古城)", 26.8690, 100.2310, "小吃"),
                r("四方街小锅饭", 26.8705, 100.2330, "滇菜,米饭"));
        putRestaurants("哈尔滨",
                r("老厨家", 45.7700, 126.6250, "东北菜,锅包肉"),
                r("东方饺子王", 45.7720, 126.6280, "饺子"),
                r("薛府一品酱骨", 45.7680, 126.6300, "东北菜"));
    }

    private static Map<String, Object> r(String name, double lat, double lng, String tags) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("lat", lat);
        m.put("lng", lng);
        m.put("tags", tags);
        m.put("type", "restaurant");
        return m;
    }

    private static void putRestaurants(String city, Map<String, Object>... items) {
        CITY_RESTAURANTS.put(city, List.of(items));
    }

    public TripPlanningAgent(WebSearchService webSearchService, RouteService routeService,
                              GeocodeService geocodeService, PoiSearchService poiSearchService,
                              RestaurantSearchService restaurantSearchService) {
        this.webSearchService = webSearchService;
        this.routeService = routeService;
        this.geocodeService = geocodeService;
        this.poiSearchService = poiSearchService;
        this.restaurantSearchService = restaurantSearchService;
    }

    @Value("${zhipu.api-key:${llm.api-key:}}")
    private String apiKey;

    @Value("${zhipu.model:${llm.model:glm-4-flash}}")
    private String model;

    @Value("${zhipu.base-url:${llm.base-url:https://open.bigmodel.cn/api/paas/v4}}")
    private String baseUrl;

    private ChatLanguageModel chatModel;
    private TripPlanningService tripPlanningService;

    @PostConstruct
    public void init() {
        chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .baseUrl(baseUrl)
                .temperature(0.3)
                .timeout(java.time.Duration.ofSeconds(180))
                .maxTokens(4096)
                .logRequests(true)
                .logResponses(true)
                .build();

        tripPlanningService = AiServices.builder(TripPlanningService.class)
                .chatLanguageModel(chatModel)
                .build();

        log.info("旅行规划Agent初始化完成，模型: {}, base_url: {}", model, baseUrl);
    }

    /**
     * 执行旅行规划
     * @param userRequest 用户的旅行需求描述
     * @return 规划结果
     */
    public Map<String, Object> planTrip(String userRequest) {
        log.info("开始规划旅行，用户需求: {}", userRequest);

        Map<String, Object> result = new HashMap<>();
        try {
            // 使用Agent进行规划
            String plan = tripPlanningService.planTrip(userRequest);
            
            result.put("success", true);
            result.put("plan", plan);
            result.put("userRequest", userRequest);
            result.put("model", model);
            
            log.info("旅行规划完成");
        } catch (Exception e) {
            log.warn("LLM 规划失败，使用本地回退规划: {}", e.getMessage());
            // 回退到本地规划逻辑
            result = fallbackLocalPlan(userRequest);
        }
        
        return result;
    }

    /**
     * 本地回退规划 - 当 LLM 不可用时使用
     */
    private Map<String, Object> fallbackLocalPlan(String userRequest) {
        log.info("使用本地回退规划: {}", userRequest);
        
        String city = extractCity(userRequest);
        List<String> mentionedPlaces = extractPlaces(userRequest);
        
        // 计算天数（默认3天）
        int totalDays = 3;
        try {
            if (userRequest.contains("天")) {
                String[] parts = userRequest.split("玩|天");
                if (parts.length > 1) {
                    String dayPart = parts[0].substring(Math.max(0, parts[0].length() - 2));
                    totalDays = Integer.parseInt(dayPart.replaceAll("\\D", ""));
                    totalDays = Math.max(1, Math.min(7, totalDays));
                }
            }
        } catch (Exception ignored) {}
        
        // 获取城市景点
        List<Map<String, Object>> attractions = CITY_ATTRACTIONS.getOrDefault(city, List.of());
        if (attractions.isEmpty()) {
            attractions = List.of(
                Map.of("name", "市中心地标", "type", "scenic", "duration", 120),
                Map.of("name", "当地美食街", "type", "shopping", "duration", 90),
                Map.of("name", "城市公园", "type", "scenic", "duration", 60)
            );
        }
        
        // 生成活动
        List<Map<String, Object>> activities = generateActivities(city, attractions, mentionedPlaces, totalDays,
                inferMealsFromKeywords(userRequest));
        List<Map<String, Object>> routes = generateRoutes(activities);
        
        Map<String, Object> stats = Map.of(
                "totalActivities", activities.size(),
                "totalDays", totalDays,
                "city", city == null ? "" : city,
                "planner", "agent-fallback"
        );
        
        String planText = String.format("""
            # %s %d日游 智能规划方案
            
            ## 基本信息
            - 目标城市: %s
            - 行程天数: %d天
            - 交通方式: 综合交通
            - 规划模式: Agent本地回退
            
            ## 每日行程安排
            %s
            
            ## 统计信息
            - 总活动数: %d
            - 预计游玩天数: %d天
            """, city, totalDays, city, totalDays, formatActivitiesForDisplay(activities), activities.size(), totalDays);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("plan", planText);
        result.put("userRequest", userRequest);
        result.put("model", "local-fallback");
        result.put("activities", activities);
        result.put("routes", routes);
        result.put("stats", stats);
        result.put("fallback", true);
        
        log.info("本地回退规划完成: city={}, activities={}, days={}", city, activities.size(), totalDays);
        return result;
    }
    
    private String formatActivitiesForDisplay(List<Map<String, Object>> activities) {
        StringBuilder sb = new StringBuilder();
        int currentDay = -1;
        for (Map<String, Object> act : activities) {
            int day = (int) act.getOrDefault("day", 1);
            if (day != currentDay) {
                currentDay = day;
                sb.append(String.format("\n### 第%d天\n", day));
            }
            String time = String.format("%s-%s", act.get("scheduled_start"), act.get("scheduled_end"));
            String typeIcon = switch ((String) act.get("activity_type")) {
                case "transit" -> "🚌";
                case "meal" -> "🍽️";
                case "shopping" -> "🛍️";
                default -> "🏛️";
            };
            sb.append(String.format("- %s %s %s (%d分钟)\n", typeIcon, time, act.get("poi_name"), act.get("duration_min")));
        }
        return sb.toString();
    }

    /**
     * 解析用户原始输入为结构化数据（默认「适中」节奏）
     * @param rawInput 用户原始描述
     * @param timeStart 行程开始时间
     * @param timeEnd 行程结束时间
     * @param transportMode 交通方式
     * @return 结构化的地点、餐饮、时间范围等
     */
    public Map<String, Object> parseInput(String rawInput, String timeStart, String timeEnd, String transportMode) {
        return parseInput(rawInput, timeStart, timeEnd, transportMode, TripPace.MODERATE.getCode(), null);
    }

    /**
     * 解析用户原始输入为结构化数据
     * @param rawInput 用户原始描述
     * @param timeStart 行程开始时间
     * @param timeEnd 行程结束时间
     * @param transportMode 交通方式
     * @param paceCode 活动频率：compact(紧凑 8~10小时/天)、moderate(适中 6~8小时/天)、relaxed(宽松 3~5小时/天)
     * @param cityHint 表单显式目的地城市（优先级最高，可为 null）
     * @return 结构化的地点、餐饮、时间范围等（含 city/summary 与每项原文引用 quote）
     */
    public Map<String, Object> parseInput(String rawInput, String timeStart, String timeEnd,
                                          String transportMode, String paceCode, String cityHint) {
        TripPace pace = TripPace.of(paceCode);
        log.info("开始解析用户输入: pace={} ({}), cityHint={}, {}", pace.getCode(), pace.getHoursLabel(), cityHint, rawInput);
        Map<String, Object> result = new HashMap<>();
        String llmCity = "";
        String llmSummary = "";
        try {
            String prompt = buildParsePrompt(rawInput, timeStart, timeEnd, transportMode, pace);
            long llmStart = System.currentTimeMillis();
            String response = tripPlanningService.parseInput(prompt);
            log.info("解析LLM完成: 耗时{}ms, prompt={}字符, 响应={}字符",
                    System.currentTimeMillis() - llmStart, prompt.length(), response.length());
            log.debug("LLM解析响应: {}", response);

            // Parse JSON response - try multiple cleanup strategies
            ObjectMapper mapper = new ObjectMapper();
            String cleaned = response.trim()
                    .replaceAll("```json\\s*", "").replaceAll("```\\s*", "")
                    .replaceAll("^[^{\\[]*", "").replaceAll("[^}\\]]*$", "")
                    .trim();
            
            JsonNode root;
            try {
                root = mapper.readTree(cleaned);
            } catch (Exception e) {
                // Try to find JSON block in the response
                int start = response.indexOf('{');
                int end = response.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    root = mapper.readTree(response.substring(start, end + 1));
                } else {
                    throw e;
                }
            }

            if (root.hasNonNull("city")) {
                llmCity = root.get("city").asText("").trim().replace("市", "");
            }
            if (root.hasNonNull("summary")) {
                llmSummary = root.get("summary").asText("").trim();
            }

            // Extract places
            List<Map<String, Object>> places = new ArrayList<>();
            if (root.has("places")) {
                for (JsonNode place : root.get("places")) {
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", place.has("name") ? place.get("name").asText() : "");
                    p.put("type", place.has("type") ? place.get("type").asText() : "scenic");
                    p.put("priority", place.has("priority") ? place.get("priority").asText() : "recommended");
                    p.put("preferredDurationMin", place.has("preferredDurationMin") ? place.get("preferredDurationMin").asInt() : 120);
                    if (place.has("notes")) p.put("notes", place.get("notes").asText());
                    if (place.hasNonNull("quote")) {
                        String q = place.get("quote").asText("").trim();
                        if (!q.isEmpty()) p.put("quote", q);
                    }
                    places.add(p);
                }
            }

            // Extract meals (保留 preference 口味偏好 + 用户指定餐厅)
            List<Map<String, Object>> meals = new ArrayList<>();
            if (root.has("meals")) {
                for (JsonNode meal : root.get("meals")) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("type", meal.has("type") ? meal.get("type").asText() : "lunch");
                    m.put("durationMin", meal.has("durationMin") ? meal.get("durationMin").asInt() : 90);
                    if (meal.has("preference") && !meal.get("preference").isNull()) {
                        String pref = meal.hasNonNull("preference") ? meal.get("preference").asText("").trim() : "";
                        if (!pref.isEmpty() && !isPlaceholderText(pref)) m.put("preference", pref);
                    }
                    if (meal.has("restaurant") && !meal.get("restaurant").isNull()) {
                        String rest = meal.get("restaurant").asText("").trim();
                        if (!rest.isEmpty() && !isPlaceholderText(rest)) m.put("restaurant", rest);
                    }
                    if (meal.hasNonNull("quote")) {
                        String q = meal.get("quote").asText("").trim();
                        if (!q.isEmpty()) m.put("quote", q);
                    }
                    meals.add(m);
                }
            }

            // 占位值被 LLM 照抄时：从 rawInput 重新提取「中午吃XX」等指定餐厅
            rehydrateMealsFromRawInput(rawInput, meals);

            // LLM 若仍把餐厅放进 places，强制合并进 meals（不独立成 place）
            places = mergeRestaurantPlacesIntoMeals(places, meals);

            // 从原文提取口味并写入 meals（LLM 未返回 preference 时兜底）
            applyFoodPreferences(rawInput, meals);

            // 景点去重（按名称）
            places = dedupePlaces(places);

            // If LLM returned very few places, supplement with keyword extraction
            if (places.size() < 2) {
                places = supplementPlacesFromKeywords(rawInput, places);
                places = mergeRestaurantPlacesIntoMeals(places, meals);
            }
            
            // 季节过滤：移除不在当季开放的景点
            String currentSeason = getCurrentSeason();
            places = filterBySeason(places, currentSeason);
            String parsedCity = resolveCity(cityHint, llmCity, rawInput);
            places = filterPlacesByCity(places, parsedCity);
            meals = filterMealsByCity(meals, parsedCity);
            places = dedupePlaces(places);
            log.info("季节过滤后: {} 景点 (当前季节: {}, city={})", places.size(), currentSeason, parsedCity);
            // 地点不足时按类型补充真实 POI（博物馆/商圈/夜市/公园），保证多元且限制总量
            int totalDaysForSupplement = calcTotalDays(timeStart, timeEnd);
            Set<String> namesBeforeSupplement = new HashSet<>();
            places.forEach(p -> namesBeforeSupplement.add(String.valueOf(p.get("name"))));
            int beforeSupplement = places.size();
            places = supplementPlacesFromPoi(places, parsedCity, totalDaysForSupplement, pace);
            if (places.size() > beforeSupplement) {
                places = filterPlacesByCity(places, parsedCity);
                places = dedupePlaces(places);
                List<String> added = new ArrayList<>();
                for (Map<String, Object> p : places) {
                    String n = String.valueOf(p.get("name"));
                    if (!namesBeforeSupplement.contains(n)) added.add(n);
                }
                log.info("POI 补充后: {} 景点 (补前 {}), 新增: {}", places.size(), beforeSupplement, added);
            }
            // If no meals extracted, infer from keywords
            if (meals.isEmpty()) {
                meals = inferMealsFromKeywords(rawInput);
            }
            applyFoodPreferences(rawInput, meals);
            rehydrateMealsFromRawInput(rawInput, meals);
            meals = normalizeMealList(meals);
            meals = ensureMealCoverage(meals, rawInput, timeStart, timeEnd);

            result.put("success", true);
            result.put("places", places);
            result.put("meals", meals);
            result.put("rawInput", rawInput);
            result.put("timeStart", timeStart);
            result.put("timeEnd", timeEnd);
            result.put("transportMode", transportMode);
            result.put("pace", pace.getCode());
            result.put("city", parsedCity == null ? "" : parsedCity);
            result.put("summary", llmSummary);
            result.put("citySource", !isBlank(cityHint) ? "form"
                    : !isBlank(llmCity) ? "llm" : parsedCity != null ? "keyword" : "unknown");

            log.info("解析完成: city={}, summary={}, places={}, meals={}, pace={}",
                    parsedCity, llmSummary, places.size(), meals.size(), pace.getCode());
        } catch (Exception e) {
            log.error("解析输入失败", e);
            // Smart fallback: extract places and meals from raw input keywords
            List<Map<String, Object>> places = supplementPlacesFromKeywords(rawInput, List.of());
            List<Map<String, Object>> meals = inferMealsFromKeywords(rawInput);
            applyFoodPreferences(rawInput, meals);
            rehydrateMealsFromRawInput(rawInput, meals);
            places = mergeRestaurantPlacesIntoMeals(places, meals);
            places = dedupePlaces(places);
            meals = normalizeMealList(meals);
            meals = ensureMealCoverage(meals, rawInput, timeStart, timeEnd);

            String fallbackCity = resolveCity(cityHint, llmCity, rawInput);
            result.put("success", !places.isEmpty());
            places = filterPlacesByCity(places, fallbackCity);
            meals = filterMealsByCity(meals, fallbackCity);
            places = dedupePlaces(places);
            result.put("places", places);
            result.put("meals", meals);
            result.put("rawInput", rawInput);
            result.put("timeStart", timeStart);
            result.put("timeEnd", timeEnd);
            result.put("transportMode", transportMode);
            result.put("pace", pace.getCode());
            result.put("city", fallbackCity == null ? "" : fallbackCity);
            result.put("summary", llmSummary);
            result.put("citySource", !isBlank(cityHint) ? "form"
                    : !isBlank(llmCity) ? "llm" : fallbackCity != null ? "keyword" : "unknown");

            log.info("使用关键词回退: places={}, meals={}, city={}", places.size(), meals.size(), fallbackCity);
        }
        return result;
    }

    /**
     * 城市解析优先级：表单显式城市 &gt; LLM 提取 &gt; 正则/景点名反查；均无返回 null（禁止默认北京）
     */
    private String resolveCity(String formCity, String llmCity, String rawInput) {
        if (!isBlank(formCity)) return formCity.trim().replace("市", "");
        if (!isBlank(llmCity)) return llmCity.trim().replace("市", "");
        String regexCity = CityOwnershipUtils.extractCity(rawInput);
        return isBlank(regexCity) ? null : regexCity;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 过滤明确属于其他城市的指定餐厅（meal.restaurant 字段）。
     * 城市未知原样返回；全部被滤空时返回空列表以触发关键词兜底重新推断。
     */
    private List<Map<String, Object>> filterMealsByCity(List<Map<String, Object>> meals, String city) {
        if (meals == null || meals.isEmpty() || city == null || city.isBlank()) {
            return meals;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> m : meals) {
            String restaurant = String.valueOf(m.getOrDefault("restaurant", "")).trim();
            if (!restaurant.isEmpty() && !CityOwnershipUtils.belongsToCity(restaurant, city)) {
                log.warn("过滤跨城指定餐厅: '{}' 归属={} city={}",
                        restaurant, CityOwnershipUtils.ownerCityOfPlace(restaurant), city);
                continue;
            }
            kept.add(m);
        }
        return kept;
    }

    /**
     * 从用户输入的关键词中提取景点
     */
    /**
     * 地点不足时从高德 POI 补充真实地点，保证类型多元（人文/商圈/夜市/公园）
     *
     * <p>目标数量按天数估算并设上限，避免距离矩阵（O(n²) 路线调用）规模过大。</p>
     *
     * @param existing   已有地点
     * @param city       城市
     * @param totalDays  行程天数
     * @return 补充后的地点列表
     */
    private List<Map<String, Object>> supplementPlacesFromPoi(List<Map<String, Object>> existing,
                                                              String city, int totalDays, TripPace pace) {
        if (city == null || city.isBlank()) {
            return existing;
        }
        // 候选池规模随节奏缩放：紧凑多备选、宽松少备选
        int target = switch (pace) {
            case COMPACT -> Math.min(8, Math.max(6, totalDays + 3));
            case RELAXED -> Math.min(6, Math.max(4, totalDays + 1));
            default -> Math.min(8, Math.max(5, totalDays + 3));
        };
        if (existing.size() >= target) {
            return existing;
        }
        List<Map<String, Object>> pois;
        try {
            pois = poiSearchService.searchCityDiverse(city, 16);
        } catch (Exception e) {
            log.warn("POI 补充地点失败: {}", e.getMessage());
            return existing;
        }
        if (pois.isEmpty()) {
            return existing;
        }

        List<Map<String, Object>> out = new ArrayList<>(existing);
        Set<String> names = new HashSet<>();
        for (Map<String, Object> p : out) {
            names.add(String.valueOf(p.get("name")));
        }
        Set<String> types = new HashSet<>();
        for (Map<String, Object> p : out) {
            types.add(String.valueOf(p.getOrDefault("type", "")));
        }

        // 优先补齐缺失类型：museum > shopping > park > temple > scenic > other
        List<String> typeOrder = List.of("museum", "shopping", "park", "temple", "scenic", "other");
        for (String wantType : typeOrder) {
            if (out.size() >= target) break;
            if (types.contains(wantType)) continue;
            for (Map<String, Object> poi : pois) {
                if (out.size() >= target) break;
                if (!wantType.equals(String.valueOf(poi.get("type")))) continue;
                if (addIfAbsent(out, names, poi)) {
                    types.add(wantType);
                    break;
                }
            }
        }
        // 仍不足则按 POI 顺序补足
        for (Map<String, Object> poi : pois) {
            if (out.size() >= target) break;
            addIfAbsent(out, names, poi);
        }
        return out;
    }

    private boolean addIfAbsent(List<Map<String, Object>> out, Set<String> names, Map<String, Object> poi) {
        String name = String.valueOf(poi.get("name"));
        for (String used : names) {
            if (samePlace(name, used)) {
                return false;
            }
        }
        out.add(poi);
        names.add(name);
        return true;
    }

    private List<Map<String, Object>> supplementPlacesFromKeywords(String rawInput, List<Map<String, Object>> existing) {
        List<Map<String, Object>> places = new ArrayList<>(existing);
        Set<String> existingNames = existing.stream()
                .map(p -> (String) p.get("name"))
                .collect(java.util.stream.Collectors.toSet());
        
        // Common Chinese tourist attraction keywords
        String[] attractionPatterns = {
            "故宫", "天安门", "长城", "颐和园", "天坛", "圆明园", "北海",
            "西湖", "灵隐寺", "雷峰塔", "断桥", "河坊街", "龙井", "千岛湖",
            "外滩", "东方明珠", "豫园", "城隍庙", "南京路", "田子坊", "迪士尼",
            "拙政园", "虎丘", "留园", "平江路", "寒山寺", "周庄",
            "兵马俑", "大雁塔", "华清宫", "钟楼", "鼓楼", "回民街",
            "宽窄巷子", "锦里", "武侯祠", "杜甫草堂", "大熊猫基地", "春熙路",
            "黄山", "泰山", "华山", "峨眉山", "九寨沟", "张家界",
            "鼓浪屿", "武夷山", "土楼",
            "丽江古城", "大理古城", "玉龙雪山", "洱海", "束河",
            "布达拉宫", "大昭寺", "八廓街", "纳木错",
            "天山", "喀纳斯", "吐鲁番", "葡萄沟",
            "三亚", "亚龙湾", "天涯海角", "蜈支洲岛",
            "广州塔", "沙面", "北京路", "白云山",
            "洪崖洞", "解放碑", "磁器口", "长江索道", "南山",
        };
        
        String lower = rawInput.toLowerCase();
        for (String name : attractionPatterns) {
            if (lower.contains(name) && !existingNames.contains(name)) {
                Map<String, Object> place = new HashMap<>();
                place.put("name", name);
                place.put("type", guessType(name));
                place.put("priority", "recommended");
                place.put("preferredDurationMin", 120);
                places.add(place);
                existingNames.add(name);
            }
        }

        // rawInput 乱码时直接跳过本地景点库（infer 会默认北京）
        if (isGarbled(rawInput)) {
            log.warn("rawInput 乱码，跳过本地景点库推荐");
            places = filterPlacesByCity(places, CityOwnershipUtils.extractCity(rawInput));
            return places;
        }

        // 如果未匹配到具体景点，根据城市从本地景点库推荐（不使用网络搜索，避免垃圾数据）
        // rawInput 明显乱码（大量?）时不默认北京，避免跨城垃圾景点
        String cityForRec = CityOwnershipUtils.extractCity(rawInput);
        if (places.size() < 3 && cityForRec != null && !isGarbled(rawInput)) {
            log.info("景点不足，从本地景点库推荐: places={}, city={}", places.size(), cityForRec);
            places = inferPopularAttractions(rawInput, existingNames);
        } else if (places.size() < 3 && (cityForRec == null || isGarbled(rawInput))) {
            log.warn("rawInput 乱码或城市未知，跳过本地景点库默认推荐: city={}, garbled={}",
                    cityForRec, isGarbled(rawInput));
        }

        places = filterPlacesByCity(places, cityForRec);
        return places;
    }

    /** 中文被转成大量 ? 视为乱码（常见于非 UTF-8 请求体） */
    private boolean isGarbled(String s) {
        if (s == null || s.isEmpty()) return true;
        long q = s.chars().filter(c -> c == '?').count();
        long cjk = s.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        return q > 0 && (cjk == 0 || q >= cjk);
    }

    /**
     * 根据城市名推断热门景点（用户未指定具体景点时使用）
     * 优先使用网络搜索获取最新景点和餐厅推荐，失败时回退到本地列表
     * 会根据当前季节过滤不开放的景点
     */
    private List<Map<String, Object>> inferPopularAttractions(String rawInput, Set<String> existingNames) {
        List<Map<String, Object>> places = new ArrayList<>();
        
        // 提取城市名
        String city = extractCity(rawInput);
        String currentSeason = getCurrentSeason();
        log.info("当前季节: {}", currentSeason);
        
        // 季节性景点黑名单（非当季不推荐）
        Map<String, List<String>> seasonalBlacklist = new HashMap<>();
        seasonalBlacklist.put("winter", List.of("武汉大学", "鼋头渚", "婺源", "油菜花", "牡丹", "樱花", "红叶", "香山", "避暑"));
        seasonalBlacklist.put("spring", List.of("冰雪大世界", "滑雪", "雪乡", "冰雪"));
        seasonalBlacklist.put("summer", List.of("冰雪大世界", "滑雪", "雪乡", "冰雪"));
        seasonalBlacklist.put("autumn", List.of("冰雪大世界", "滑雪", "雪乡", "冰雪", "油菜花", "樱花"));
        
        // 跳过网络搜索（返回垃圾数据），直接使用本地硬编码列表（带季节标签）
        // 回退：使用本地硬编码列表（带季节标签）
        // 格式: {name, type, seasons}  seasons=逗号分隔的季节，空=全季节
        Map<String, List<String[]>> cityAttractions = new HashMap<>();
        cityAttractions.put("北京", List.of(
            new String[]{"故宫", "scenic", ""}, new String[]{"天安门", "scenic", ""}, new String[]{"长城", "scenic", ""},
            new String[]{"颐和园", "scenic", ""}, new String[]{"天坛", "temple", ""}, new String[]{"圆明园", "scenic", ""},
            new String[]{"南锣鼓巷", "shopping", ""}, new String[]{"王府井", "shopping", ""},
            new String[]{"香山", "scenic", "autumn"}, new String[]{"北海公园", "scenic", "spring,summer,autumn"}
        ));
        cityAttractions.put("上海", List.of(
            new String[]{"外滩", "scenic", ""}, new String[]{"东方明珠", "scenic", ""}, new String[]{"豫园", "scenic", ""},
            new String[]{"城隍庙", "temple", ""}, new String[]{"南京路", "shopping", ""}, new String[]{"田子坊", "shopping", ""}
        ));
        cityAttractions.put("杭州", List.of(
            new String[]{"西湖", "scenic", ""}, new String[]{"灵隐寺", "temple", ""}, new String[]{"雷峰塔", "scenic", ""},
            new String[]{"河坊街", "shopping", ""}, new String[]{"龙井", "scenic", "spring,summer,autumn"}, new String[]{"断桥", "scenic", ""}
        ));
        cityAttractions.put("成都", List.of(
            new String[]{"宽窄巷子", "shopping", ""}, new String[]{"锦里", "shopping", ""}, new String[]{"武侯祠", "temple", ""},
            new String[]{"大熊猫基地", "scenic", ""}, new String[]{"杜甫草堂", "temple", ""}, new String[]{"春熙路", "shopping", ""}
        ));
        cityAttractions.put("西安", List.of(
            new String[]{"兵马俑", "museum", ""}, new String[]{"大雁塔", "temple", ""}, new String[]{"华清宫", "scenic", ""},
            new String[]{"钟楼", "scenic", ""}, new String[]{"回民街", "shopping", ""}, new String[]{"城墙", "scenic", ""}
        ));
        cityAttractions.put("苏州", List.of(
            new String[]{"拙政园", "scenic", "spring,summer,autumn"}, new String[]{"虎丘", "scenic", ""}, new String[]{"留园", "scenic", ""},
            new String[]{"平江路", "shopping", ""}, new String[]{"寒山寺", "temple", ""}
        ));
        cityAttractions.put("南京", List.of(
            new String[]{"中山陵", "scenic", ""}, new String[]{"夫子庙", "temple", ""}, new String[]{"总统府", "museum", ""},
            new String[]{"玄武湖", "scenic", "spring,summer,autumn"}, new String[]{"明孝陵", "scenic", ""},
            new String[]{"老门东", "shopping", ""}, new String[]{"鸡鸣寺", "temple", "spring"}
        ));
        cityAttractions.put("重庆", List.of(
            new String[]{"洪崖洞", "scenic", ""}, new String[]{"解放碑", "shopping", ""}, new String[]{"磁器口", "shopping", ""},
            new String[]{"长江索道", "scenic", ""}, new String[]{"南山", "scenic", ""}, new String[]{"朝天门", "scenic", ""}
        ));
        cityAttractions.put("长沙", List.of(
            new String[]{"橘子洲", "scenic", ""}, new String[]{"岳麓山", "scenic", ""}, new String[]{"太平街", "shopping", ""},
            new String[]{"湖南省博物馆", "museum", ""}, new String[]{"天心阁", "scenic", ""}
        ));
        cityAttractions.put("厦门", List.of(
            new String[]{"鼓浪屿", "scenic", ""}, new String[]{"南普陀寺", "temple", ""}, new String[]{"曾厝垵", "shopping", ""},
            new String[]{"环岛路", "scenic", ""}, new String[]{"中山路", "shopping", ""}
        ));
        cityAttractions.put("三亚", List.of(
            new String[]{"亚龙湾", "scenic", ""}, new String[]{"天涯海角", "scenic", ""}, new String[]{"蜈支洲岛", "scenic", ""},
            new String[]{"南山寺", "temple", ""}, new String[]{"大东海", "scenic", ""}
        ));
        cityAttractions.put("大理", List.of(
            new String[]{"大理古城", "shopping", ""}, new String[]{"洱海", "scenic", ""}, new String[]{"苍山", "scenic", ""},
            new String[]{"双廊", "scenic", ""}, new String[]{"崇圣寺三塔", "temple", ""}
        ));
        cityAttractions.put("丽江", List.of(
            new String[]{"丽江古城", "shopping", ""}, new String[]{"玉龙雪山", "scenic", "winter,spring"}, new String[]{"束河古镇", "shopping", ""},
            new String[]{"泸沽湖", "scenic", ""}, new String[]{"蓝月谷", "scenic", "spring,summer,autumn"}
        ));
        cityAttractions.put("广州", List.of(
            new String[]{"广州塔", "scenic", ""}, new String[]{"沙面", "scenic", ""}, new String[]{"北京路", "shopping", ""},
            new String[]{"白云山", "scenic", ""}, new String[]{"陈家祠", "temple", ""}
        ));
        cityAttractions.put("武汉", List.of(
            new String[]{"黄鹤楼", "scenic", ""}, new String[]{"东湖", "scenic", ""}, new String[]{"户部巷", "shopping", ""},
            new String[]{"武汉大学", "scenic", "spring"}, new String[]{"江汉路", "shopping", ""},
            new String[]{"武汉植物园", "scenic", "spring,summer,autumn"}, new String[]{"东湖樱园", "scenic", "spring"}
        ));
        cityAttractions.put("哈尔滨", List.of(
            new String[]{"中央大街", "shopping", ""}, new String[]{"冰雪大世界", "scenic", "winter"},
            new String[]{"圣索菲亚教堂", "temple", ""}, new String[]{"松花江", "scenic", "summer,autumn"},
            new String[]{"太阳岛", "scenic", "summer,autumn"}, new String[]{"东北虎林园", "scenic", ""},
            new String[]{"哈尔滨极地馆", "scenic", ""}, new String[]{"伏尔加庄园", "scenic", "winter"}
        ));
        cityAttractions.put("青岛", List.of(
            new String[]{"栈桥", "scenic", ""}, new String[]{"八大关", "scenic", "spring,summer,autumn"},
            new String[]{"崂山", "scenic", ""}, new String[]{"五四广场", "scenic", ""},
            new String[]{"啤酒博物馆", "museum", ""}
        ));
        cityAttractions.put("昆明", List.of(
            new String[]{"滇池", "scenic", ""}, new String[]{"石林", "scenic", ""}, new String[]{"翠湖", "scenic", ""},
            new String[]{"西山", "scenic", ""}, new String[]{"金殿", "temple", ""}
        ));
        cityAttractions.put("洛阳", List.of(
            new String[]{"龙门石窟", "scenic", ""}, new String[]{"白马寺", "temple", ""}, new String[]{"洛阳博物馆", "museum", ""},
            new String[]{"老君山", "scenic", ""}, new String[]{"丽景门", "scenic", ""},
            new String[]{"洛阳牡丹园", "scenic", "spring"}
        ));
        
        // 根据识别出的城市精确取景点（避免 HashMap 乱序 + rawInput 子串误判）
        String matchedCity = city;
        if (matchedCity == null || matchedCity.isBlank() || !cityAttractions.containsKey(matchedCity)) {
            matchedCity = CityOwnershipUtils.extractCity(rawInput);
        }
        List<String[]> allAttractions = matchedCity != null ? cityAttractions.get(matchedCity) : null;
        if (allAttractions != null) {
            List<String[]> filtered = new ArrayList<>();
            for (String[] attr : allAttractions) {
                String seasons = attr.length > 2 ? attr[2] : "";
                if (seasons.isEmpty() || seasons.contains(currentSeason)) {
                    filtered.add(attr);
                } else {
                    log.debug("季节过滤: {} (需要 {}, 当前 {})", attr[0], seasons, currentSeason);
                }
            }

            int count = Math.min(filtered.size(), rawInput.length() < 10 ? 3 : 4);
            for (int i = 0; i < count; i++) {
                String[] attr = filtered.get(i);
                if (!existingNames.contains(attr[0])) {
                    Map<String, Object> place = new HashMap<>();
                    place.put("name", attr[0]);
                    place.put("type", attr[1]);
                    place.put("priority", i < 2 ? "must" : "recommended");
                    place.put("preferredDurationMin", "temple".equals(attr[1]) ? 60 : "shopping".equals(attr[1]) ? 90 : 120);
                    places.add(place);
                    existingNames.add(attr[0]);
                }
            }
        }

        return places;
    }

    /**
     * 获取当前季节
     */
    private String getCurrentSeason() {
        int month = java.time.LocalDate.now().getMonthValue();
        if (month >= 3 && month <= 5) return "spring";
        if (month >= 6 && month <= 8) return "summer";
        if (month >= 9 && month <= 11) return "autumn";
        return "winter";
    }

    /**
     * 根据季节过滤景点列表
     * 移除不在当季开放或适合游玩的景点
     */
    private List<Map<String, Object>> filterBySeason(List<Map<String, Object>> places, String season) {
        // 季节性黑名单：关键词 -> 不适合的季节
        Map<String, List<String>> blacklist = new HashMap<>();
        blacklist.put("winter", List.of("武汉大学", "鼋头渚", "婺源", "油菜花", "牡丹", "樱花", "红叶", "香山", "避暑"));
        blacklist.put("spring", List.of("冰雪大世界", "滑雪", "雪乡", "冰雪"));
        blacklist.put("summer", List.of("冰雪大世界", "滑雪", "雪乡", "冰雪"));
        blacklist.put("autumn", List.of("冰雪大世界", "滑雪", "雪乡", "冰雪", "油菜花", "樱花"));
        
        List<String> keywords = blacklist.getOrDefault(season, List.of());
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        for (Map<String, Object> place : places) {
            String name = (String) place.getOrDefault("name", "");
            boolean blocked = false;
            for (String keyword : keywords) {
                if (name.contains(keyword)) {
                    log.info("季节过滤: '{}' (包含关键词 '{}', 当前季节 {})", name, keyword, season);
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                filtered.add(place);
            }
        }
        return filtered;
    }

    /**
     * 从输入中提取城市名（排除「北京路」等路名误判）
     */
    private String extractCity(String rawInput) {
        if (isGarbled(rawInput)) {
            log.warn("rawInput 乱码，无法识别城市");
            return null;
        }
        String city = CityOwnershipUtils.extractCity(rawInput);
        if (city != null && !city.isBlank()) {
            return city;
        }
        // 城市未知时返回 null（上层按显式城市/LLM城市优先）；禁止默认北京造成跨城污染
        log.warn("无法从输入中提取城市，返回 null（不默认任何城市）");
        return null;
    }

    /**
     * 丢弃明确属于其他城市的景点，防止跨城混入
     */
    private List<Map<String, Object>> filterPlacesByCity(List<Map<String, Object>> places, String city) {
        if (places == null || places.isEmpty()) {
            return places == null ? new ArrayList<>() : places;
        }
        if (city == null || city.isBlank()) {
            return places;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> p : places) {
            String name = String.valueOf(p.getOrDefault("name", ""));
            if (isPlaceholderText(name)) {
                log.warn("过滤占位景点: '{}'", name);
                continue;
            }
            if (CityOwnershipUtils.belongsToCity(name, city)) {
                kept.add(p);
            } else {
                log.warn("过滤跨城景点: '{}' 归属={} 目标城市={}",
                        name, CityOwnershipUtils.ownerCityOfPlace(name), city);
            }
        }
        return kept;
    }

    /**
     * 过滤 LLM 生成的跨城 visit/meal 活动（交通/缓冲不拦；归属未知放行）
     */
    private List<Map<String, Object>> filterActivitiesByCity(List<Map<String, Object>> activities, String city) {
        if (activities == null || activities.isEmpty() || city == null || city.isBlank()) {
            return activities;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> a : activities) {
            String type = String.valueOf(a.getOrDefault("type", "visit"));
            boolean isTransport = "transit".equals(type) || "buffer".equals(type)
                    || "transport".equals(type);
            if (isTransport) {
                kept.add(a);
                continue;
            }
            String name = String.valueOf(a.getOrDefault("name", ""));
            if (!CityOwnershipUtils.belongsToCity(name, city)) {
                log.warn("过滤跨城活动: day={} '{}' 归属={} city={}",
                        a.get("day"), name, CityOwnershipUtils.ownerCityOfPlace(name), city);
                continue;
            }
            kept.add(a);
        }
        return kept.isEmpty() ? activities : kept;
    }

    /**
     * 直接从 rawInput 提取景点名称（不依赖LLM，避免编码问题）
     */
    private List<Map<String, Object>> extractPlacesDirectly(String rawInput) {
        List<Map<String, Object>> places = new ArrayList<>();
        
        // 已知景点数据库（常见旅游景点）
        Map<String, String> knownPlaces = new HashMap<>();
        // 北京
        knownPlaces.put("故宫", "scenic");
        knownPlaces.put("故宫博物院", "scenic");
        knownPlaces.put("天安门", "scenic");
        knownPlaces.put("天安门广场", "scenic");
        knownPlaces.put("八达岭长城", "scenic");
        knownPlaces.put("长城", "scenic");
        knownPlaces.put("颐和园", "scenic");
        knownPlaces.put("天坛", "temple");
        knownPlaces.put("圆明园", "scenic");
        knownPlaces.put("南锣鼓巷", "shopping");
        knownPlaces.put("王府井", "shopping");
        knownPlaces.put("什刹海", "scenic");
        knownPlaces.put("鸟巢", "scenic");
        knownPlaces.put("水立方", "scenic");
        knownPlaces.put("景山公园", "scenic");
        knownPlaces.put("北海公园", "scenic");
        // 上海
        knownPlaces.put("东方明珠", "scenic");
        knownPlaces.put("外滩", "scenic");
        knownPlaces.put("迪士尼", "scenic");
        knownPlaces.put("豫园", "scenic");
        knownPlaces.put("南京路", "shopping");
        // 杭州
        knownPlaces.put("西湖", "scenic");
        knownPlaces.put("灵隐寺", "temple");
        knownPlaces.put("雷峰塔", "scenic");
        knownPlaces.put("断桥", "scenic");
        // 西安
        knownPlaces.put("兵马俑", "museum");
        knownPlaces.put("大雁塔", "scenic");
        knownPlaces.put("华清池", "scenic");
        knownPlaces.put("城墙", "scenic");
        // 成都
        knownPlaces.put("大熊猫基地", "scenic");
        knownPlaces.put("宽窄巷子", "shopping");
        knownPlaces.put("锦里", "shopping");
        knownPlaces.put("都江堰", "scenic");
        // 其他热门景点
        knownPlaces.put("黄山", "scenic");
        knownPlaces.put("张家界", "scenic");
        knownPlaces.put("九寨沟", "scenic");
        knownPlaces.put("丽江古城", "shopping");
        knownPlaces.put("大理古城", "shopping");
        knownPlaces.put("鼓浪屿", "scenic");
        
        // 从 rawInput 中匹配已知景点
        for (Map.Entry<String, String> entry : knownPlaces.entrySet()) {
            if (rawInput.contains(entry.getKey())) {
                Map<String, Object> place = new HashMap<>();
                place.put("name", entry.getKey());
                place.put("type", entry.getValue());
                place.put("priority", "must");
                place.put("preferredDurationMin", estimateDuration(entry.getKey()));
                places.add(place);
                log.info("直接提取景点: {}", entry.getKey());
            }
        }
        
        // 如果没有匹配到已知景点，尝试用分隔符提取
        if (places.isEmpty()) {
            // 常见分隔符：逗号、顿号、空格
            String[] parts = rawInput.split("[,，、\\s]+");
            for (String part : parts) {
                String trimmed = part.trim();
                // 过滤掉明显不是景点的词
                if (trimmed.length() >= 2 && trimmed.length() <= 10
                        && !trimmed.contains("日") && !trimmed.contains("游")
                        && !trimmed.contains("去") && !trimmed.contains("到")
                        && !trimmed.contains("点") && !trimmed.contains("钟")
                        && !trimmed.matches(".*\\d+.*")) {
                    Map<String, Object> place = new HashMap<>();
                    place.put("name", trimmed);
                    place.put("type", guessType(trimmed));
                    place.put("priority", "recommended");
                    place.put("preferredDurationMin", 120);
                    places.add(place);
                    log.info("分隔符提取景点: {}", trimmed);
                }
            }
        }
        
        return places;
    }

    /**
     * 估计景点游玩时长
     */
    private int estimateDuration(String name) {
        if (name.contains("长城")) return 300;  // 长城需要5小时
        if (name.contains("故宫")) return 240;  // 故宫需要4小时
        if (name.contains("颐和园")) return 180;  // 颐和园需要3小时
        if (name.contains("天坛")) return 120;  // 天坛需要2小时
        if (name.contains("广场")) return 60;  // 广场1小时
        if (name.contains("公园")) return 120;  // 公园2小时
        if (name.contains("街") || name.contains("巷")) return 90;  // 街区1.5小时
        return 120;  // 默认2小时
    }

    /**
     * 直接从 rawInput 提取餐饮信息
     */
    private List<Map<String, Object>> extractMealsDirectly(String rawInput) {
        List<Map<String, Object>> meals = new ArrayList<>();
        String lower = rawInput.toLowerCase();
        String pref = extractFoodPreference(rawInput);
        java.util.function.BiFunction<String, Integer, Map<String, Object>> meal =
                (type, dur) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("type", type);
                    m.put("durationMin", dur);
                    if (!pref.isEmpty()) m.put("preference", pref);
                    return m;
                };

        // 检测午餐
        if (lower.contains("午餐") || lower.contains("中饭") || lower.contains("午饭") || lower.contains("中午")
                || !pref.isEmpty()) {
            meals.add(meal.apply("lunch", 60));
        }
        // 检测晚餐
        if (lower.contains("晚餐") || lower.contains("晚饭") || lower.contains("晚上吃") || !pref.isEmpty()) {
            meals.add(meal.apply("dinner", 60));
        }
        // 检测早餐
        if (lower.contains("早餐") || lower.contains("早饭")) {
            meals.add(meal.apply("breakfast", 45));
        }

        // 如果没有检测到具体餐次，根据时间范围推断
        if (meals.isEmpty()) {
            meals.add(meal.apply("lunch", 60));
            meals.add(meal.apply("dinner", 60));
        }

        return meals;
    }

    private String guessType(String name) {
        if (name.contains("寺") || name.contains("庙") || name.contains("宫")) return "temple";
        if (name.contains("园") || name.contains("山") || name.contains("湖") || name.contains("岛")) return "scenic";
        if (name.contains("街") || name.contains("路") || name.contains("城")) return "shopping";
        if (name.contains("博物馆") || name.contains("馆")) return "museum";
        if (name.contains("公园")) return "park";
        return "scenic";
    }

    private List<Map<String, Object>> inferMealsFromKeywords(String rawInput) {
        List<Map<String, Object>> meals = new ArrayList<>();
        String lower = rawInput.toLowerCase();
        String pref = extractFoodPreference(rawInput);
        if (lower.contains("早餐") || lower.contains("早饭") || lower.contains("包子") || lower.contains("豆浆")) {
            meals.add(MutableMeal("breakfast", 60, lower.contains("包子") ? "包子" : (lower.contains("豆浆") ? "豆浆" : "")));
        }
        boolean wantLunch = lower.contains("午餐") || lower.contains("中饭") || lower.contains("午饭") || lower.contains("中午吃");
        boolean wantDinner = lower.contains("晚餐") || lower.contains("晚饭") || lower.contains("晚上吃") || lower.contains("夜宵")
                || lower.contains("火锅") || lower.contains("烤鸭") || lower.contains("海鲜") || !pref.isEmpty();
        if (wantLunch) {
            meals.add(MutableMeal("lunch", 90, pref));
        }
        if (wantDinner) {
            meals.add(MutableMeal("dinner", 90, pref));
        }
        if (meals.isEmpty()) {
            meals.add(MutableMeal("lunch", 90, pref));
            meals.add(MutableMeal("dinner", 90, pref));
        }
        // 若只有早餐但用户表达了口味，仍补午/晚
        if (!pref.isEmpty() && meals.stream().noneMatch(m -> "lunch".equals(m.get("type")) || "dinner".equals(m.get("type")))) {
            meals.add(MutableMeal("dinner", 90, pref));
        }
        return meals;
    }

    private Map<String, Object> MutableMeal(String type, int durationMin, String preference) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("durationMin", durationMin);
        if (preference != null && !preference.isBlank()) {
            m.put("preference", preference);
        }
        return m;
    }

    /** 按名称去重景点/餐厅 place */
    private List<Map<String, Object>> dedupePlaces(List<Map<String, Object>> places) {
        return dedupePlaces(places, null);
    }

    private List<Map<String, Object>> dedupePlaces(List<Map<String, Object>> places, String city) {
        if (places == null) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> p : places) {
            String n = String.valueOf(p.getOrDefault("name", "")).trim();
            if (n.isEmpty()) continue;
            boolean dup = false;
            for (String prev : seen) {
                if (samePlace(normalizePlaceKey(n, city), normalizePlaceKey(prev, city))
                        || samePlace(n, prev)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                seen.add(n);
                out.add(p);
            }
        }
        return out;
    }

    /** 从用户原文提取想吃的东西（口味/菜系/具体菜名） */
    private String extractFoodPreference(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) return "";
        if (isGarbled(rawInput)) return "";
        String lower = rawInput.toLowerCase();
        // 优先匹配「想吃/爱吃/吃 + 具体词」
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:想吃|爱吃|喜欢吃|要吃|去吃|品尝|吃一顿)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})")
                .matcher(rawInput);
        List<String> hits = new ArrayList<>();
        while (m.find()) {
            String seg = m.group(1);
            for (String kw : FOOD_KEYWORDS) {
                if (seg.contains(kw) || kw.equalsIgnoreCase(seg)) {
                    hits.add(kw.trim());
                    break;
                }
            }
            // 整段作为偏好（如「西湖醋鱼」）
            if (hits.isEmpty() || !hits.get(hits.size() - 1).equals(seg)) {
                if (FOOD_KEYWORDS.stream().anyMatch(k -> seg.contains(k))) {
                    // 已由关键词覆盖
                } else if (seg.length() >= 2 && !seg.contains("玩") && !seg.contains("去") && !seg.contains("游")) {
                    hits.add(seg);
                }
            }
        }
        for (String kw : FOOD_KEYWORDS) {
            if (lower.contains(kw.toLowerCase()) && !hits.contains(kw)) {
                hits.add(kw);
            }
        }
        if (hits.isEmpty()) return "";
        // 去重并优先短而具体的关键词
        LinkedHashSet<String> uniq = new LinkedHashSet<>(hits);
        return String.join("/", uniq);
    }

    /** 将口味偏好写入各正餐 meals（若尚无 preference） */
    private void applyFoodPreferences(String rawInput, List<Map<String, Object>> meals) {
        String pref = extractFoodPreference(rawInput);
        if (pref.isEmpty() || meals == null) return;
        boolean any = false;
        for (Map<String, Object> m : meals) {
            Object existing = m.get("preference");
            if (existing != null && !String.valueOf(existing).isBlank()) {
                any = true;
            }
        }
        if (!any) {
            for (Map<String, Object> m : meals) {
                String type = String.valueOf(m.getOrDefault("type", ""));
                if ("lunch".equals(type) || "dinner".equals(type)) {
                    m.put("preference", pref);
                }
            }
        } else {
            // 只补缺失的正餐
            for (Map<String, Object> m : meals) {
                String type = String.valueOf(m.getOrDefault("type", ""));
                if (("lunch".equals(type) || "dinner".equals(type))
                        && (m.get("preference") == null || String.valueOf(m.get("preference")).isBlank())) {
                    m.put("preference", pref);
                }
            }
        }
    }

    private boolean preferenceBlank(List<Map<String, Object>> meals, String mealType) {
        return mealPreference(meals, mealType).isBlank();
    }

    private String mealPreference(List<Map<String, Object>> meals, String mealType) {
        if (meals == null) return "";
        for (Map<String, Object> m : meals) {
            if (mealType.equals(String.valueOf(m.getOrDefault("type", "")))) {
                Object rest = m.get("restaurant");
                if (rest != null && !isPlaceholderText(String.valueOf(rest))) return String.valueOf(rest);
                Object p = m.get("preference");
                if (p != null && !isPlaceholderText(String.valueOf(p))) return String.valueOf(p);
            }
        }
        // 任意正餐 restaurant/preference
        for (Map<String, Object> m : meals) {
            String t = String.valueOf(m.getOrDefault("type", ""));
            if ("lunch".equals(t) || "dinner".equals(t)) {
                Object rest = m.get("restaurant");
                if (rest != null && !isPlaceholderText(String.valueOf(rest))) return String.valueOf(rest);
                Object p = m.get("preference");
                if (p != null && !isPlaceholderText(String.valueOf(p))) return String.valueOf(p);
            }
        }
        return "";
    }

    /** 用户指定的该餐次餐厅名（无则空；过滤 LLM 占位文案） */
    private String mealSpecifiedRestaurant(List<Map<String, Object>> meals, String mealType) {
        if (meals == null) return "";
        for (Map<String, Object> m : meals) {
            if (mealType.equals(String.valueOf(m.getOrDefault("type", "")))) {
                Object rest = m.get("restaurant");
                if (rest != null && !isPlaceholderText(String.valueOf(rest))) return String.valueOf(rest);
            }
        }
        return "";
    }

    /**
     * 按口味 + 与上一地点距离挑选具体餐厅。
     * @param prevLat 上一活动纬度（可 null）
     * @param prevLng 上一活动经度（可 null）
     */
    private Map<String, Object> pickRestaurant(String city, String preference, Double prevLat, Double prevLng,
                                               Set<String> usedRestaurantNames) {
        List<Map<String, Object>> pool = CITY_RESTAURANTS.getOrDefault(city, List.of());
        if (pool.isEmpty()) return null;
        String pref = preference == null ? "" : preference.toLowerCase();
        List<String> prefs = pref.isEmpty() ? List.of() : Arrays.asList(pref.split("[/,、]"));

        Map<String, Object> best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map<String, Object> rest : pool) {
            String name = String.valueOf(rest.get("name"));
            if (usedRestaurantNames != null && usedRestaurantNames.contains(name)) continue;
            String tags = String.valueOf(rest.getOrDefault("tags", "")).toLowerCase();
            boolean tagMatch = false;
            for (String p : prefs) {
                if (p.isBlank()) continue;
                if (tags.contains(p) || name.toLowerCase().contains(p)) {
                    tagMatch = true;
                    break;
                }
            }
            double dist = 0;
            if (prevLat != null && prevLng != null && rest.get("lat") != null) {
                dist = geoDistance(prevLat, prevLng,
                        ((Number) rest.get("lat")).doubleValue(),
                        ((Number) rest.get("lng")).doubleValue());
            }
            // 口味匹配大幅加分；距离越近分越低
            double score = dist + (tagMatch ? 0 : 50000);
            if (score < bestScore) {
                bestScore = score;
                best = rest;
            }
        }
        if (best != null && usedRestaurantNames != null) {
            usedRestaurantNames.add(String.valueOf(best.get("name")));
        }
        return best;
    }

    /** 生成午/晚餐 poi 名称：具体餐厅优先，其次 午餐-口味；过滤 LLM 占位文案 */
    private String mealDisplayName(String mealType, String city, String preference,
                                   Double prevLat, Double prevLng, Set<String> usedRestaurants) {
        String pref = (preference == null || isPlaceholderText(preference)) ? "" : preference;
        Map<String, Object> rest = pickRestaurant(city, pref, prevLat, prevLng, usedRestaurants);
        if (rest != null) {
            String name = String.valueOf(rest.get("name"));
            return mealType.equals("lunch") ? "午餐·" + name : "晚餐·" + name;
        }
        String label = pref.isEmpty() ? (city == null || city.isBlank() ? "本地美食" : city + "美食") : pref;
        if (isPlaceholderText(label)) label = city == null || city.isBlank() ? "本地美食" : city + "美食";
        return mealType.equals("lunch") ? ("午餐·" + label) : ("晚餐·" + label);
    }

    /**
     * 餐段注解（v1.15.0 餐厅推荐增强）：
     * <ul>
     *   <li>静态优先：17 城内置餐厅库命中 → 取坐标（手工校准，权威）</li>
     *   <li>在线补全：lookupExact 补 rating/cost/poi_address（评分 &lt; 4.0 被服务层排除，缺失保留）</li>
     *   <li>泛化餐名（本地美食/城市美食/纯口味）→ searchNearby 升级为附近真实餐厅并改名</li>
     *   <li>坐标预置：写入 lat/lng 供 correctActivitiesWithRealData 的 coordMap 复用，省地理编码配额</li>
     * </ul>
     * 仅在目标城市已知时发起在线查询（防跨城检索）。失败静默降级，不影响主流程。
     *
     * @param activities 已定稿活动列表（结构不再变化）
     * @param city       目标城市
     */
    private List<Map<String, Object>> annotateMealRestaurants(List<Map<String, Object>> activities, String city) {
        if (activities == null || activities.isEmpty()) {
            return activities;
        }
        if (city == null || city.isBlank()) {
            log.warn("餐段注解跳过: 目标城市未知");
            return activities;
        }
        Set<String> usedRestaurantNames = new HashSet<>();
        Double prevLat = null;
        Double prevLng = null;
        int annotated = 0;
        int upgraded = 0;
        for (Map<String, Object> act : activities) {
            String type = String.valueOf(act.getOrDefault("type", act.getOrDefault("activity_type", "visit")));
            if ("meal".equals(type)) {
                String rawName = String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", "")));
                String bare = stripMealPrefix(rawName);
                String prefix = mealPrefixOf(rawName);
                Map<String, Object> staticRest = findStaticRestaurant(city, bare);

                RestaurantSearchService.RestaurantInfo online = null;
                try {
                    online = restaurantSearchService.lookupExact(bare, city, prevLat, prevLng);
                } catch (Exception e) {
                    log.debug("餐厅在线补全失败: {}, {}", bare, e.getMessage());
                }

                if (isGenericMealLabel(bare, city)) {
                    // 泛化餐名 → 附近真实餐厅升级
                    String keyword = FOOD_KEYWORDS.stream()
                            .anyMatch(k -> bare.contains(k)) ? bare : "美食";
                    RestaurantSearchService.RestaurantInfo nearby = null;
                    try {
                        nearby = restaurantSearchService.searchNearby(keyword, city, prevLat, prevLng);
                    } catch (Exception e) {
                        log.debug("附近餐厅检索失败: {}, {}", keyword, e.getMessage());
                    }
                    if (nearby != null && usedRestaurantNames.add(nearby.name())) {
                        String newName = prefix.isEmpty() ? nearby.name() : prefix + "·" + nearby.name();
                        act.put("name", newName);
                        if (act.containsKey("poi_name")) {
                            act.put("poi_name", newName);
                        }
                        applyRestaurantInfo(act, nearby, false);
                        upgraded++;
                        annotated++;
                        log.info("泛化餐名升级: '{}' -> '{}' (rating={}, cost={})",
                                rawName, newName, nearby.rating(), nearby.cost());
                    } else if (staticRest != null) {
                        applyStaticCoord(act, staticRest);
                        annotated++;
                    }
                } else if (staticRest != null || online != null) {
                    if (staticRest != null) {
                        applyStaticCoord(act, staticRest);
                        usedRestaurantNames.add(String.valueOf(staticRest.get("name")));
                    }
                    if (online != null) {
                        // 静态坐标优先，在线结果补评分/人均/地址（静态无坐标时才取在线坐标）
                        applyRestaurantInfo(act, online, staticRest != null);
                        annotated++;
                    }
                }
            }
            // 记录当前活动坐标作为下一段参考点
            if (act.get("lat") != null && act.get("lng") != null) {
                prevLat = ((Number) act.get("lat")).doubleValue();
                prevLng = ((Number) act.get("lng")).doubleValue();
            }
        }
        log.info("餐段注解完成: city={}, 餐段注解 {} 个, 泛化升级 {} 个", city, annotated, upgraded);
        return activities;
    }

    /** 取餐次前缀（带 ·）：「午餐·楼外楼」→「午餐」；无前缀返回原名的空串 */
    private String mealPrefixOf(String name) {
        if (name == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)")
                .matcher(name.trim());
        return m.find() ? m.group(1) : "";
    }

    /** 静态餐厅库精确命中（本城） */
    private Map<String, Object> findStaticRestaurant(String city, String bare) {
        if (bare == null || bare.isEmpty()) {
            return null;
        }
        for (Map<String, Object> r : CITY_RESTAURANTS.getOrDefault(city, List.of())) {
            String rn = String.valueOf(r.getOrDefault("name", ""));
            if (bare.equals(rn) || stripMealPrefix(bare).equals(rn)) {
                return r;
            }
        }
        return null;
    }

    /** 是否泛化餐名（占位/城市美食/纯口味词），需升级为真实餐厅 */
    private boolean isGenericMealLabel(String bare, String city) {
        if (bare == null || bare.isEmpty() || isPlaceholderText(bare)) {
            return true;
        }
        if (bare.equals("本地美食") || bare.equals(city + "美食")) {
            return true;
        }
        if (bare.endsWith("美食") && bare.length() <= 5) {
            return true;
        }
        return FOOD_KEYWORDS.contains(bare) || FOOD_KEYWORDS.contains(bare.toLowerCase());
    }

    /** 写入在线餐厅信息：评分/人均/地址；坐标仅在静态库无坐标时写入（keepStaticCoord=true 跳过） */
    private void applyRestaurantInfo(Map<String, Object> act,
                                     RestaurantSearchService.RestaurantInfo info,
                                     boolean keepStaticCoord) {
        if (info.rating() != null) {
            act.put("rating", info.rating());
        }
        if (info.cost() != null && !info.cost().isBlank()) {
            act.put("cost", info.cost());
        }
        if (info.address() != null && !info.address().isBlank()) {
            act.put("poi_address", info.address());
        }
        if (!keepStaticCoord && info.lat() != null && info.lng() != null) {
            act.put("lat", info.lat());
            act.put("lng", info.lng());
        }
    }

    /** 写入静态坐标（手工校准优先于在线坐标） */
    private void applyStaticCoord(Map<String, Object> act, Map<String, Object> staticRest) {
        if (staticRest.get("lat") != null && staticRest.get("lng") != null) {
            act.put("lat", ((Number) staticRest.get("lat")).doubleValue());
            act.put("lng", ((Number) staticRest.get("lng")).doubleValue());
        }
    }

    /**
     * 把 type=restaurant 的 place 合并进 meals（按 meal 字段或名称推断时段），
     * 并从 places 中剔除——餐厅不作为独立景点栏目。
     */
    private List<Map<String, Object>> mergeRestaurantPlacesIntoMeals(
            List<Map<String, Object>> places, List<Map<String, Object>> meals) {
        if (places == null || places.isEmpty()) return places == null ? new ArrayList<>() : places;
        if (meals == null) meals = new ArrayList<>();
        List<Map<String, Object>> nonRestaurant = new ArrayList<>();
        for (Map<String, Object> p : places) {
            String type = String.valueOf(p.getOrDefault("type", ""));
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (!"restaurant".equalsIgnoreCase(type) && !"food".equalsIgnoreCase(type)) {
                nonRestaurant.add(p);
                continue;
            }
            if (name.isEmpty()) continue;
            String slot = String.valueOf(p.getOrDefault("meal", "")).trim();
            if (slot.isEmpty()) slot = inferMealSlotFromName(name);
            if (slot.isEmpty()) slot = "lunch";
            if (!"breakfast".equals(slot) && !"lunch".equals(slot) && !"dinner".equals(slot)) {
                slot = "lunch";
            }
            String finalSlot = slot;
            Map<String, Object> target = null;
            for (Map<String, Object> m : meals) {
                if (finalSlot.equals(String.valueOf(m.get("type")))) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                target = new HashMap<>();
                target.put("type", finalSlot);
                target.put("durationMin", 90);
                meals.add(target);
            }
            if (!target.containsKey("restaurant") && !isPlaceholderText(name)) {
                target.put("restaurant", name);
            }
            // 名称/菜系作为口味偏好兜底
            if (!target.containsKey("preference") || isPlaceholderText(String.valueOf(target.get("preference")))) {
                String pref = p.get("notes") != null && !isPlaceholderText(String.valueOf(p.get("notes")))
                        ? String.valueOf(p.get("notes")) : name;
                if (!isPlaceholderText(pref)) target.put("preference", pref);
            }
        }
        return nonRestaurant;
    }

    /** LLM 把 prompt 占位说明原样写出的值（如「具体餐厅名，无则省略」） */
    private boolean isPlaceholderText(String s) {
        if (s == null || s.isBlank()) return true;
        String t = s.trim();
        return t.contains("无则省略") || t.contains("具体餐厅名") || t.contains("口味或菜系")
                || t.contains("偏好描述") || t.contains("景点名称") || t.contains("仅写真实")
                || t.contains("placeholder") || t.contains("无则") || t.contains("省略");
    }

    /**
     * LLM 占位/漏提时，从用户原文重新提取指定餐厅。
     * 匹配：中午吃楼外楼 / 午餐去全聚德 / 晚餐：知味观 等。
     */
    private void rehydrateMealsFromRawInput(String rawInput, List<Map<String, Object>> meals) {
        if (rawInput == null || rawInput.isBlank() || meals == null) return;
        // 已有真实餐厅名则跳过
        for (Map<String, Object> m : meals) {
            Object rest = m.get("restaurant");
            if (rest != null && !isPlaceholderText(String.valueOf(rest))) return;
        }
        List<String> lines = Arrays.asList(
                rawInput.split("[，,。.;；\n]"));
        for (String line : lines) {
            String slot = "";
            if (line.contains("中午") || line.contains("午餐") || line.contains("午饭")) slot = "lunch";
            else if (line.contains("晚餐") || line.contains("晚饭") || line.contains("晚上吃") || line.contains("夜宵")) slot = "dinner";
            else if (line.contains("早餐") || line.contains("早饭")) slot = "breakfast";
            if (slot.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:吃|去|到|品尝|来)\\s*([\\u4e00-\\u9fa5A-Za-z0-9（）()·]{2,20})")
                    .matcher(line);
            if (m.find()) {
                String name = m.group(1).trim()
                        .replaceAll("[，,。.;；\\s]+$", "");
                if (name.isEmpty() || isPlaceholderText(name)) continue;
                // 排除纯菜系/口味词
                if (FOOD_KEYWORDS.stream().anyMatch(k -> name.equals(k))) continue;
                upsertMealRestaurant(meals, slot, name);
            }
        }
        // 清掉仍是占位的 restaurant
        for (Map<String, Object> m : meals) {
            Object rest = m.get("restaurant");
            if (rest != null && isPlaceholderText(String.valueOf(rest))) m.remove("restaurant");
        }
    }

    private void upsertMealRestaurant(List<Map<String, Object>> meals, String slot, String restaurant) {
        Map<String, Object> target = null;
        for (Map<String, Object> m : meals) {
            if (slot.equals(String.valueOf(m.getOrDefault("type", "")))) {
                target = m;
                break;
            }
        }
        if (target == null) {
            target = new HashMap<>();
            target.put("type", slot);
            target.put("durationMin", 90);
            meals.add(target);
        }
        target.put("restaurant", restaurant);
        if (!target.containsKey("preference") || isPlaceholderText(String.valueOf(target.get("preference")))) {
            target.remove("preference");
        }
    }

    /** 从餐厅名/备注推断用餐时段 */
    private String inferMealSlotFromName(String name) {
        if (name == null || name.isBlank()) return "";
        String n = name;
        if (n.contains("早") || n.contains("晨")) return "breakfast";
        if (n.contains("晚") || n.contains("夜")) return "dinner";
        if (n.contains("午") || n.contains("中")) return "lunch";
        return "";
    }

    /** meals 列表归一：同 slot 只保留一条；过滤占位 preference/restaurant；restaurant 写入 preference 兜底 */
    /**
     * 按行程时间窗补齐缺失的正餐餐次：覆盖 11:00-14:00 未解析出 lunch 则补 lunch，
     * 覆盖 17:00-21:00 未解析出 dinner 则补 dinner（LLM 偶发漏输出时兜底）。
     */
    private List<Map<String, Object>> ensureMealCoverage(List<Map<String, Object>> meals,
                                                         String rawInput, String timeStart, String timeEnd) {
        List<Map<String, Object>> out = meals == null ? new ArrayList<>() : new ArrayList<>(meals);
        int startHour = 8;
        int endHour = 21;
        try {
            startHour = java.time.LocalDateTime.parse(timeStart).getHour();
        } catch (Exception ignored) { }
        try {
            endHour = java.time.LocalDateTime.parse(timeEnd).getHour();
        } catch (Exception ignored) { }
        boolean hasLunch = out.stream().anyMatch(m -> "lunch".equals(String.valueOf(m.getOrDefault("type", ""))));
        boolean hasDinner = out.stream().anyMatch(m -> "dinner".equals(String.valueOf(m.getOrDefault("type", ""))));
        String pref = extractFoodPreference(rawInput == null ? "" : rawInput);
        if (!hasLunch && startHour <= 11 && endHour >= 13) {
            out.add(MutableMeal("lunch", 90, pref));
            log.info("补齐缺失餐次: lunch");
        }
        if (!hasDinner && startHour <= 17 && endHour >= 18) {
            out.add(MutableMeal("dinner", 90, pref));
            log.info("补齐缺失餐次: dinner");
        }
        return out;
    }

    private List<Map<String, Object>> normalizeMealList(List<Map<String, Object>> meals) {
        if (meals == null || meals.isEmpty()) return meals == null ? new ArrayList<>() : meals;
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> bySlot = new LinkedHashMap<>();
        for (Map<String, Object> m : meals) {
            String type = String.valueOf(m.getOrDefault("type", "lunch"));
            if (!"breakfast".equals(type) && !"lunch".equals(type) && !"dinner".equals(type)) {
                type = "lunch";
            }
            Object pref = m.get("preference");
            if (pref != null && isPlaceholderText(String.valueOf(pref))) m.remove("preference");
            Object rest = m.get("restaurant");
            if (rest != null && isPlaceholderText(String.valueOf(rest))) m.remove("restaurant");
            Map<String, Object> existing = bySlot.get(type);
            if (existing == null) {
                bySlot.put(type, m);
                continue;
            }
            // 合并 restaurant/preference/duration
            if (!existing.containsKey("restaurant") && m.containsKey("restaurant")) {
                existing.put("restaurant", m.get("restaurant"));
            }
            if ((!existing.containsKey("preference") || String.valueOf(existing.get("preference")).isBlank())
                    && m.containsKey("preference")) {
                existing.put("preference", m.get("preference"));
            }
            if (m.get("durationMin") != null) existing.put("durationMin", m.get("durationMin"));
        }
        out.addAll(bySlot.values());
        return out;
    }

    /**
     * 就近排序：用户提到的优先，其余按与上一地点直线距离升序（贪心）。
     * 无坐标时保持原相对顺序中的未去重项。
     */
    private List<Map<String, Object>> sortPlacesByProximity(List<Map<String, Object>> places, String city,
                                                             List<String> mentionedPlaces) {
        if (places == null || places.size() <= 1) return places == null ? new ArrayList<>() : places;
        List<Map<String, Object>> remaining = dedupePlaces(places);
        List<Map<String, Object>> ordered = new ArrayList<>();

        // 坐标缓存
        Map<String, double[]> coordCache = new HashMap<>();
        for (Map<String, Object> p : remaining) {
            String n = String.valueOf(p.get("name"));
            double[] c = resolveCoord(n, city, p);
            if (c != null) coordCache.put(n, c);
        }

        // 种子：第一个用户提到的景点，否则第一个 place
        Map<String, Object> seed = null;
        if (mentionedPlaces != null && !mentionedPlaces.isEmpty()) {
            for (Map<String, Object> p : remaining) {
                String n = String.valueOf(p.get("name"));
                if (mentionedPlaces.stream().anyMatch(m -> n.contains(m) || m.contains(n))) {
                    seed = p;
                    break;
                }
            }
        }
        if (seed == null) seed = remaining.get(0);
        ordered.add(seed);
        remaining.remove(seed);
        double[] last = coordCache.get(String.valueOf(seed.get("name")));

        while (!remaining.isEmpty()) {
            Map<String, Object> nearest = null;
            double best = Double.MAX_VALUE;
            // 用户提到的优先作为下一跳候选（仍比较距离）
            for (Map<String, Object> p : remaining) {
                String n = String.valueOf(p.get("name"));
                boolean mentioned = mentionedPlaces != null
                        && mentionedPlaces.stream().anyMatch(m -> n.contains(m) || m.contains(n));
                double[] c = coordCache.get(n);
                double d;
                if (last == null || c == null) {
                    d = mentioned ? -1 : remaining.indexOf(p);
                } else {
                    d = geoDistance(last[0], last[1], c[0], c[1]) - (mentioned ? 10000 : 0);
                }
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest == null) nearest = remaining.get(0);
            ordered.add(nearest);
            remaining.remove(nearest);
            double[] c = coordCache.get(String.valueOf(nearest.get("name")));
            if (c != null) last = c;
        }
        return ordered;
    }

    /** 解析 place 坐标：自带 lat/lng → 本城静态餐厅库 → 高德 geocode（key 含 city） */
    private final Map<String, double[]> geoNameCache = new HashMap<>();

    private double[] resolveCoord(String name, String city, Map<String, Object> place) {
        if (place != null && place.get("lat") != null && place.get("lng") != null) {
            return new double[]{((Number) place.get("lat")).doubleValue(),
                    ((Number) place.get("lng")).doubleValue()};
        }
        String cacheKey = (city == null ? "" : city) + "|" + name;
        if (geoNameCache.containsKey(cacheKey)) return geoNameCache.get(cacheKey);
        // 优先本城餐厅库
        List<Map<String, Object>> cityRests = CITY_RESTAURANTS.getOrDefault(city, List.of());
        for (Map<String, Object> rest : cityRests) {
            if (name.equals(String.valueOf(rest.get("name")))) {
                double[] c = {((Number) rest.get("lat")).doubleValue(),
                        ((Number) rest.get("lng")).doubleValue()};
                geoNameCache.put(cacheKey, c);
                return c;
            }
        }
        // 仅当名称权威归属 == 目标城市时，才允许其他城市库命中（防跨城坐标串味）
        // 归属未知 → 不再放行，交由 geocode(name, city) 限定城市查询
        String owner = CityOwnershipUtils.ownerCityOfPlace(name);
        if (owner != null && (city == null || city.isBlank() || owner.equals(city))) {
            for (Map.Entry<String, List<Map<String, Object>>> e : CITY_RESTAURANTS.entrySet()) {
                if (city != null && !city.isBlank() && e.getKey().equals(city)) {
                    continue; // 本城库已在上方精确匹配过
                }
                for (Map<String, Object> rest : e.getValue()) {
                    if (name.equals(String.valueOf(rest.get("name")))) {
                        double[] c = {((Number) rest.get("lat")).doubleValue(),
                                ((Number) rest.get("lng")).doubleValue()};
                        geoNameCache.put(cacheKey, c);
                        return c;
                    }
                }
            }
        }
        try {
            GeocodeResponse geo = geocodeService.geocode(name, city, "amap");
            if (geo != null && geo.isSuccess() && geo.getLat() != null && geo.getLng() != null) {
                double[] c = {geo.getLat(), geo.getLng()};
                geoNameCache.put(cacheKey, c);
                return c;
            }
        } catch (Exception ignored) {
        }
        geoNameCache.put(cacheKey, null);
        return null;
    }

    private String buildParsePrompt(String rawInput, String timeStart, String timeEnd,
                                    String transportMode, TripPace pace) {
        String currentSeason = getCurrentSeason();
        int currentMonth = java.time.LocalDate.now().getMonthValue();
        String seasonDesc = switch (currentSeason) {
            case "spring" -> "春季(3-5月)";
            case "summer" -> "夏季(6-8月)";
            case "autumn" -> "秋季(9-11月)";
            default -> "冬季(12-2月)";
        };
        int minPlaces = pace.getMinVisits() + 2;
        int maxPlaces = Math.min(8, pace.getMaxVisits() + 3);

        return String.format("""
                你是一个专业的旅行需求解析助手。请从用户描述中提取景点与餐饮，直接输出严格 JSON，禁止任何其他文字。

                === 当前时间 ===
                月份: %d月，季节: %s

                === 用户描述 ===
                %s

                === 时间范围 ===
                开始: %s，结束: %s

                === 交通方式 ===
                %s

                === 活动频率（用户已选择，硬约束） ===
                %s
                解析出的景点候选数量须匹配该节奏：%d-%d 个真实地点。

                === 解析规则 ===
                1. places 只放景点类地点，type 仅限 scenic/museum/park/temple/shopping/other（禁止 restaurant）；每项含 name/type/priority/preferredDurationMin
                2. priority: 必去/一定要去→must，想去/推荐→recommended，有时间→optional；每餐 durationMin=90
                3. 餐厅禁止独立成 place：「中午吃XX」「晚餐去XX」的 XX 写入对应 meals 的 restaurant，type 只能是 breakfast|lunch|dinner
                4. 口味偏好（火锅/川菜/西湖醋鱼等）写入该餐 preference；未提及时省略 preference/restaurant 字段，禁止输出占位说明
                5. 用户未提具体景点时（如「去杭州玩两天」），必须推荐该城市 %d-%d 个真实地点，禁止只给 1-2 个；类型必须多元：至少 1 个人文/文化类（博物馆/纪念馆/历史街区）+ 1 个特色商圈/夜市/步行街类，其余为知名景点；禁止输出「自由活动」「市区漫步」等占位名
                6. 景点禁止重复；推荐景点优先与上一个地理位置接近，餐厅优先安排在上一景点附近
                7. 季节过滤（当前%s）：只推荐当季景点——冬季不推荐冰雪大世界/赏樱，夏季不推荐滑雪，春季推荐赏樱赏花，秋季推荐红叶；室内景点全年可去
                8. city: 目的地城市（从描述提取，如「去青岛」→"青岛」；描述无任何城市线索时输出 ""，禁止瞎猜、禁止默认北京）
                9. summary: 一句话概括用户意图（目的地/天数/偏好），≤50字
                10. quote: 每个 place/meal 项对应的原文摘句（原样引用用户描述中支撑该提取的片段，≤30字；用户未提及而是你推荐的条目输出 ""）
                11. 【红线】所有 places/meals 必须位于 city 同城（city 为空时必须与描述指向的城市一致）；下方示例仅演示 JSON 格式，严禁把示例中的北京地点照抄到其他城市的输出里

                === 输出格式（严格JSON） ===
                {"city":"目的地城市","summary":"一句话概括","places":[{"name":"真实景点名","type":"scenic","priority":"must","preferredDurationMin":120,"quote":"原文摘句"}],"meals":[{"type":"lunch","durationMin":90,"preference":"口味","restaurant":"指定餐厅","quote":"原文摘句"}]}

                === 示例：模糊描述必须多推荐 ===
                输入："我想去北京玩两天"
                输出：
                {"city":"北京","summary":"北京两日游，重点打卡经典地标并品尝烤鸭","places":[{"name":"故宫","type":"scenic","priority":"must","preferredDurationMin":240,"quote":"去北京玩两天"},{"name":"八达岭长城","type":"scenic","priority":"must","preferredDurationMin":300,"quote":""},{"name":"颐和园","type":"scenic","priority":"recommended","preferredDurationMin":180,"quote":""},{"name":"天坛","type":"temple","priority":"recommended","preferredDurationMin":120,"quote":""},{"name":"南锣鼓巷","type":"shopping","priority":"recommended","preferredDurationMin":90,"quote":""}],"meals":[{"type":"lunch","durationMin":90,"preference":"北京烤鸭","restaurant":"全聚德","quote":"北京烤鸭"},{"type":"dinner","durationMin":90,"quote":""}]}
                """, currentMonth, seasonDesc, rawInput, timeStart, timeEnd, transportMode,
                pace.promptLine(), minPlaces, maxPlaces, minPlaces, maxPlaces, seasonDesc);
    }

    /**
     * LLM 直接生成完整行程规划
     * @param rawInput 用户原始输入
     * @param places 解析出的景点列表
     * @param meals 解析出的餐饮列表
     * @param timeStart 行程开始时间
     * @param timeEnd 行程结束时间
     * @param distanceMatrix 景点间距离矩阵 (JSON string)
     * @return 完整的行程规划 (activities 列表)
     */
    public Map<String, Object> planDetailedItinerary(String rawInput, List<Map<String, Object>> places,
                                                      List<Map<String, Object>> meals,
                                                      String timeStart, String timeEnd,
                                                      String distanceMatrix) {
        return planDetailedItinerary(rawInput, places, meals, timeStart, timeEnd, distanceMatrix, null,
                TripPace.MODERATE.getCode());
    }

    public Map<String, Object> planDetailedItinerary(String rawInput, List<Map<String, Object>> places,
                                                      List<Map<String, Object>> meals,
                                                      String timeStart, String timeEnd,
                                                      String distanceMatrix, String explicitCity) {
        return planDetailedItinerary(rawInput, places, meals, timeStart, timeEnd, distanceMatrix, explicitCity,
                TripPace.MODERATE.getCode());
    }

    /**
     * 生成完整行程
     *
     * @param paceCode 活动频率：compact(紧凑 8~10小时/天)、moderate(适中 6~8小时/天)、relaxed(宽松 3~5小时/天)
     */
    public Map<String, Object> planDetailedItinerary(String rawInput, List<Map<String, Object>> places,
                                                      List<Map<String, Object>> meals,
                                                      String timeStart, String timeEnd,
                                                      String distanceMatrix, String explicitCity,
                                                      String paceCode) {
        return planDetailedItinerary(rawInput, places, meals, timeStart, timeEnd, distanceMatrix,
                explicitCity, paceCode, VariantSpec.nonVariant());
    }

    /**
     * 生成完整行程（支持换版规划）
     *
     * @param variantSpec 换版规格：排除基准版本已用 POI + 基准行程摘要（非换版传 {@link VariantSpec#nonVariant()}）
     */
    public Map<String, Object> planDetailedItinerary(String rawInput, List<Map<String, Object>> places,
                                                      List<Map<String, Object>> meals,
                                                      String timeStart, String timeEnd,
                                                      String distanceMatrix, String explicitCity,
                                                      String paceCode, VariantSpec variantSpec) {
        if (variantSpec == null) {
            variantSpec = VariantSpec.nonVariant();
        }
        boolean variant = variantSpec.isActive();
        List<String> excludePois = variantSpec.getExcludePois();
        TripPace pace = TripPace.of(paceCode);
        log.info("规划节奏: {} {}，每日游览 {}，游览活动 {}~{} 个",
                pace.getLabel(), pace.getCode(), pace.getHoursLabel(), pace.getMinVisits(), pace.getMaxVisits());
        // 如果没有提供景点，直接从 rawInput 提取景点名称（不依赖LLM）
        if (places == null || places.isEmpty()) {
            log.info("未提供景点列表，从 rawInput 直接提取: {}", rawInput);
            places = extractPlacesDirectly(rawInput);
            meals = extractMealsDirectly(rawInput);
            log.info("直接提取完成: {} 景点, {} 餐饮", places.size(), meals.size());
        }
        if (places == null) places = new ArrayList<>();
        if (meals == null) meals = new ArrayList<>();
        // LLM 占位/漏提时，从原文补回指定餐厅（如「中午吃楼外楼」）
        rehydrateMealsFromRawInput(rawInput, meals);
        // 餐厅不作为独立景点：合并进 meals 后从 places 剔除
        places = mergeRestaurantPlacesIntoMeals(places, meals);
        meals = normalizeMealList(meals);

        String city = (explicitCity != null && !explicitCity.isBlank())
                ? explicitCity.replace("市", "")
                : extractCity(rawInput);
        // 显式城市为空且 rawInput 有效时，仍尝试从原文识别，避免 null 污染后续过滤
        if (city == null && !isGarbled(rawInput)) {
            city = CityOwnershipUtils.extractCity(rawInput);
        }

        // 景点去重 + 口味偏好兜底 + 跨城过滤
        places = dedupePlaces(places);
        places = filterPlacesByCity(places, city);
        applyFoodPreferences(rawInput, meals);

        // 换版规划：排除基准版本已用 POI（用户原文指定的景点/餐厅=主题，保留）；
        // 景点不足时用 Amap 多元真实地点补充候选（同时留存候选池，供 LLM 违规输出的强制替换）
        List<Map<String, Object>> variantRepairPool = new ArrayList<>();
        if (variant && excludePois != null && !excludePois.isEmpty()) {
            places = applyVariantExclusions(places, rawInput, excludePois);
            meals = applyVariantMealExclusions(meals, rawInput, excludePois);
            places = augmentVariantPlaces(places, city, rawInput, excludePois, timeStart, timeEnd, pace, variantRepairPool);
            log.info("换版规划准备完成: 剩余景点={}, 餐次={}, 排除POI={}个, 修复候选={}个",
                    places.size(), meals.size(), excludePois.size(), variantRepairPool.size());
        }

        // 就近排序：用户明确提到的景点优先作种子，其余贪心取最近
        List<String> mentioned = new ArrayList<>();
        for (Map<String, Object> p : places) {
            String n = String.valueOf(p.get("name"));
            if (!n.isEmpty() && rawInput.contains(n)) mentioned.add(n);
        }
        places = sortPlacesByProximity(places, city, mentioned);

        log.info("开始生成详细行程规划，景点数: {}, 餐次: {}", places.size(), meals.size());
        String realDistanceMatrix;
        try {
            realDistanceMatrix = buildRealDistanceMatrix(places, meals, city);
            log.info("=== 距离矩阵构建完成 ===\n{}", realDistanceMatrix);
        } catch (Exception e) {
            log.warn("构建真实距离矩阵失败，使用传入矩阵: {}", e.getMessage());
            realDistanceMatrix = distanceMatrix;
        }

        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> activities = new ArrayList<>();
        // 管道上下文：预处理定稿后创建，主路径与回退路径共用
        PlanningContext ctx = new PlanningContext(rawInput, places, meals, timeStart, timeEnd,
                city, pace, variant, excludePois, variantRepairPool);
        try {
            String prompt = buildItineraryPrompt(rawInput, places, meals, timeStart, timeEnd,
                    realDistanceMatrix, city, pace, variantSpec);
            long llmStart = System.currentTimeMillis();
            String response = tripPlanningService.planItinerary(prompt);
            log.info("行程LLM完成: 耗时{}ms, prompt={}字符, 响应={}字符",
                    System.currentTimeMillis() - llmStart, prompt.length(), response.length());
            log.debug("LLM行程规划响应: {}", response);

            // Parse JSON response
            ObjectMapper mapper = new ObjectMapper();
            String cleaned = response.trim()
                    .replaceAll("```json\\s*", "").replaceAll("```\\s*", "")
                    .replaceAll("^[^{\\[]*", "").replaceAll("[^}\\]]*$", "")
                    .trim();

            JsonNode root;
            try {
                root = mapper.readTree(cleaned);
            } catch (Exception e) {
                int start = response.indexOf('{');
                int end = response.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    root = mapper.readTree(response.substring(start, end + 1));
                } else {
                    throw e;
                }
            }

            // Extract activities
            activities.clear();
            if (root.has("activities")) {
                for (JsonNode act : root.get("activities")) {
                    Map<String, Object> a = new HashMap<>();
                    a.put("day", act.has("day") ? act.get("day").asInt() : 1);
                    a.put("name", act.has("name") ? act.get("name").asText() : "");
                    a.put("type", act.has("type") ? act.get("type").asText() : "visit");
                    a.put("startTime", act.has("startTime") ? act.get("startTime").asText() : "");
                    a.put("endTime", act.has("endTime") ? act.get("endTime").asText() : "");
                    a.put("durationMin", act.has("durationMin") ? act.get("durationMin").asInt() : 60);
                    a.put("transportToNext", act.has("transportToNext") ? act.get("transportToNext").asText() : "");
                    a.put("travelTimeMin", act.has("travelTimeMin") ? act.get("travelTimeMin").asInt() : 0);
                    a.put("travelDistanceKm", act.has("travelDistanceKm") ? act.get("travelDistanceKm").asDouble() : 0.0);
                    a.put("priority", act.has("priority") ? act.get("priority").asText() : "recommended");
                    a.put("notes", act.has("notes") ? act.get("notes").asText() : "");
                    activities.add(a);
                }
            }

            // 多日补全：LLM 可能只生成部分天数，对缺失天数逐天调用 LLM
            int totalDays = calcTotalDays(timeStart, timeEnd);
            if (totalDays > 1) {
                int maxDay = activities.stream()
                        .map(a -> (Integer) a.getOrDefault("day", 1))
                        .mapToInt(Integer::intValue).max().orElse(1);
                if (maxDay < totalDays) {
                    log.info("LLM 只生成到 day={}, 总天数={}, 并行补全缺失天数", maxDay, totalDays);
                    java.util.Set<String> usedNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
                    activities.forEach(a -> usedNames.add(String.valueOf(a.get("name"))));
                    final List<Map<String, Object>> backfillPlaces = places;
                    final List<Map<String, Object>> backfillMeals = meals;
                    final String backfillMatrix = realDistanceMatrix;
                    List<CompletableFuture<List<Map<String, Object>>>> dayFutures = new ArrayList<>();
                    for (int d = maxDay + 1; d <= totalDays; d++) {
                        final int dayNum = d;
                        dayFutures.add(CompletableFuture.supplyAsync(() -> {
                            try {
                                List<Map<String, Object>> dayActs = planSingleDay(
                                        rawInput, backfillPlaces, backfillMeals, timeStart, timeEnd,
                                        backfillMatrix, dayNum, totalDays, usedNames, pace);
                                if (dayActs != null && !dayActs.isEmpty()) {
                                    log.info("补全 day={} 完成: {} 个活动", dayNum, dayActs.size());
                                    return dayActs;
                                }
                            } catch (Exception dayEx) {
                                log.warn("补全 day={} 失败: {}", dayNum, dayEx.getMessage());
                            }
                            return List.<Map<String, Object>>of();
                        }, planExecutor));
                    }
                    for (CompletableFuture<List<Map<String, Object>>> dayFuture : dayFutures) {
                        List<Map<String, Object>> dayActs = dayFuture.join();
                        if (!dayActs.isEmpty()) {
                            activities.addAll(dayActs);
                            dayActs.forEach(a -> usedNames.add(String.valueOf(a.get("name"))));
                        }
                    }
                }
            }

            // 后处理管道：主路径与回退路径共用同一步骤序列（见 postPipeline()）
            activities = runPostPipeline(activities, ctx);
            fillResult(result, activities, ctx, false);

            log.info("详细行程规划完成: activities={}, pace={}", activities.size(), pace.getCode());
        } catch (Exception e) {
            log.error("LLM生成详细行程失败，使用本地回退: {}", e.getMessage());
            // 本地回退：基于景点列表生成基础行程，随后走同一条后处理管道
            activities = generateLocalItinerary(rawInput, places, meals, timeStart, timeEnd);
            try {
                activities = runPostPipeline(activities, ctx);
            } catch (Exception pipeEx) {
                log.warn("回退后处理管道失败，保留基础行程: {}", pipeEx.getMessage());
            }
            fillResult(result, activities, ctx, true);
        }
        return result;
    }

    /**
     * 执行后处理管道
     *
     * @param activities LLM 产物或本地回退基础行程
     * @param ctx        管道上下文
     * @return 定稿活动列表
     */
    private List<Map<String, Object>> runPostPipeline(List<Map<String, Object>> activities, PlanningContext ctx) {
        List<Map<String, Object>> acts = activities;
        for (ActivityStep step : postPipeline()) {
            acts = step.apply(acts, ctx);
        }
        return acts;
    }

    /**
     * 后处理管道步骤序列（canonical）
     *
     * 主路径（LLM 产物）与回退路径（本地基础行程）必须共用此序列——
     * 曾因两路径手工各写一遍导致换版排除清洗只加在主路径（见 BUGFIX 1.17.0）。
     * 新增/调整后处理只改这里；步骤内异常按语义选择吞掉或上抛。
     *
     * 顺序约束：enforceVariant 在真实路网修正前（改名后重新地理编码）；
     * fillPaceGaps 在预算裁剪前；refill 在收口裁剪前。
     */
    private List<ActivityStep> postPipeline() {
        return List.<ActivityStep>of(
                // 景点/餐食名归一 + 去重 + 跨城过滤 + 餐名具体化
                (acts, ctx) -> sanitizeActivityNames(acts),
                (acts, ctx) -> replaceNonPlaceVisits(acts, ctx.getPlaces(), ctx.getCity()),
                (acts, ctx) -> filterActivitiesByCity(acts, ctx.getCity()),
                (acts, ctx) -> dedupeVisitActivities(acts, ctx.getCity()),
                (acts, ctx) -> enrichMealNames(acts, ctx.getMeals(), ctx.getCity()),
                (acts, ctx) -> dedupeVisitActivities(acts, ctx.getCity()),
                // 完整性兜底（失败保留原活动），可能产出占位名需再洗一次
                this::ensureCompleteStep,
                (acts, ctx) -> replaceNonPlaceVisits(acts, ctx.getPlaces(), ctx.getCity()),
                // 每日餐次归一后结构定稿
                (acts, ctx) -> normalizeDailyMeals(acts),
                (acts, ctx) -> dedupeVisitActivities(acts, ctx.getCity()),
                // 节奏缺口填充（换版时候选池已剔除排除项）→ 活动频率预算
                (acts, ctx) -> fillPaceGaps(acts, ctx.getCity(), ctx.getPace(), ctx.getExcludePois(), ctx.getRawInput()),
                (acts, ctx) -> applyPaceBudget(acts, ctx.getPace()),
                // 餐厅注解（静态坐标 + 在线评分/人均/地址）
                (acts, ctx) -> annotateMealRestaurants(acts, ctx.getCity()),
                // 换版排除硬清洗（非换版 no-op；须在真实路网修正前）
                this::enforceVariantStep,
                // 真实路网修正 → 人性化校准 → 餐次兜底 → 时间重叠修复
                this::correctStep,
                this::humanizeStep,
                (acts, ctx) -> normalizeDailyMeals(acts),
                (acts, ctx) -> fixTimeOverlaps(acts),
                // 二次节奏补时（最多 3 轮，无提升即止）→ 收口裁剪
                this::refillStep,
                (acts, ctx) -> applyPaceBudget(acts, ctx.getPace())
        );
    }

    /** 完整性兜底步骤：保证每天都有足够的景点+餐饮+时间规划（失败保留原活动） */
    private List<Map<String, Object>> ensureCompleteStep(List<Map<String, Object>> acts, PlanningContext ctx) {
        try {
            return ensureCompleteItinerary(acts, ctx.getPlaces(), ctx.getMeals(),
                    ctx.getTimeStart(), ctx.getTimeEnd(), ctx.getCity(), ctx.getPace(),
                    ctx.getExcludePois(), ctx.getRawInput());
        } catch (Exception e) {
            log.warn("完整性兜底失败，保留原活动: {}", e.getMessage());
            return acts;
        }
    }

    /** 换版排除硬清洗步骤：清洗 LLM/兜底重新引入的排除项（非换版为 no-op） */
    private List<Map<String, Object>> enforceVariantStep(List<Map<String, Object>> acts, PlanningContext ctx) {
        if (!ctx.isVariant() || ctx.getExcludePois() == null || ctx.getExcludePois().isEmpty()) {
            return acts;
        }
        return enforceVariantExclusions(acts, ctx.getRawInput(), ctx.getExcludePois(),
                ctx.getCity(), ctx.getRepairPool());
    }

    /** 真实路网修正步骤（失败保留未修正结果，避免整段丢弃 LLM 产物） */
    private List<Map<String, Object>> correctStep(List<Map<String, Object>> acts, PlanningContext ctx) {
        try {
            return correctActivitiesWithRealData(acts, ctx.getPlaces(), ctx.getCity());
        } catch (Exception e) {
            log.warn("真实路网修正失败，保留未修正结果: {}", e.getMessage());
            return acts;
        }
    }

    /** 人性化校准步骤：用餐符合作息，每日 21:00 前结束（失败保留原时间） */
    private List<Map<String, Object>> humanizeStep(List<Map<String, Object>> acts, PlanningContext ctx) {
        try {
            return applyHumanizedTiming(acts);
        } catch (Exception e) {
            log.warn("人性化时间校准失败: {}", e.getMessage());
            return acts;
        }
    }

    /** 二次节奏补时步骤：以“每日是否达到节奏下限”为条件最多补 3 轮（无提升即止） */
    private List<Map<String, Object>> refillStep(List<Map<String, Object>> acts, PlanningContext ctx) {
        List<Map<String, Object>> current = acts;
        for (int round = 1; round <= 3 && belowPaceFloor(current, ctx.getPace()); round++) {
            List<Map<String, Object>> before = current;
            current = refillPaceGaps(current, ctx.getPlaces(), ctx.getCity(), ctx.getPace(),
                    ctx.getExcludePois(), ctx.getRawInput());
            if (current == before) {
                break;
            }
        }
        return current;
    }

    /** 组装返回结果（主路径与回退路径共用，fallback 标记仅回退置位） */
    private void fillResult(Map<String, Object> result, List<Map<String, Object>> activities,
                            PlanningContext ctx, boolean fallback) {
        result.put("success", !activities.isEmpty());
        result.put("activities", activities);
        result.put("rawInput", ctx.getRawInput());
        result.put("timeStart", ctx.getTimeStart());
        result.put("timeEnd", ctx.getTimeEnd());
        result.put("city", ctx.getCity());
        result.put("pace", ctx.getPace().getCode());
        if (fallback) {
            result.put("fallback", true);
        }
    }

    /** 按名称去掉重复的游览类活动（同名 visit/shopping 全程只保留首次） */
    /** 归一化景点/餐厅名：去城市前缀、餐次前缀（午餐·/晚餐·）、空白，便于跨 visit/meal 去重 */
    private String normalizePlaceKey(String name, String city) {
        if (name == null) return "";
        String k = name.trim();
        if (city != null && !city.isEmpty()) {
            if (k.startsWith(city)) k = k.substring(city.length());
            if (k.startsWith(city + "市")) k = k.substring(city.length() + 1);
            String cityFull = city.endsWith("市") ? city : city + "市";
            if (k.startsWith(cityFull)) k = k.substring(cityFull.length());
        }
        // 去餐次前缀：午餐·全聚德 / 晚餐·蜀大侠火锅 → 全聚德 / 蜀大侠火锅
        k = k.replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
        if (k.isEmpty()) k = name.trim();
        return k.replaceAll("\\s+", "");
    }

    /** 景点名等价判定：归一化后相等，或一方是另一方的前缀/包含（覆盖「成都宽窄巷子」vs「宽窄巷子」） */
    private boolean samePlace(String a, String b) {
        String na = normalizePlaceKey(a, null);
        String nb = normalizePlaceKey(b, null);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        int min = Math.min(na.length(), nb.length());
        if (min >= 3 && (na.contains(nb) || nb.contains(na))) return true;
        return false;
    }

    private List<Map<String, Object>> dedupeVisitActivities(List<Map<String, Object>> activities) {
        return dedupeVisitActivities(activities, null);
    }

    /** 剔除活动名中的 LLM 占位文案（如「午餐·具体餐厅名，无则省略」） */
    private List<Map<String, Object>> sanitizeActivityNames(List<Map<String, Object>> activities) {
        if (activities == null || activities.isEmpty()) return activities;
        for (Map<String, Object> a : activities) {
            String name = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")));
            if (isPlaceholderText(name)) {
                String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
                String fallback = "meal".equals(type) ? "午餐·本地美食" : "待定景点";
                log.warn("清洗占位活动名: '{}' -> '{}'", name, fallback);
                a.put("name", fallback);
                if (a.containsKey("poi_name")) a.put("poi_name", fallback);
            }
            Object notes = a.get("notes");
            if (notes != null && isPlaceholderText(String.valueOf(notes))) {
                a.remove("notes");
            }
        }
        return activities;
    }

    /** 非地点类游览名（返回酒店/返程/休息等 LLM 占位）判定 */
    private static final List<String> NON_PLACE_PHRASES = List.of(
            "返回酒店", "回酒店", "返程", "离城", "去机场", "回家", "休息", "自由活动",
            "市区漫步", "自由推荐", "待定景点", "自由行", "自由安排", "随意逛逛", "周边逛逛",
            "行程结束", "结束行程", "结束");

    private boolean isNonPlaceVisit(String name) {
        if (name == null || name.isBlank()) return true;
        String n = name.replaceAll("\\s+", "");
        if (n.matches(".*\\(\\d+-\\d+\\)$")) return true;
        for (String phrase : NON_PLACE_PHRASES) {
            if (n.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * 把 LLM 生成的非地点游览活动（返回酒店/自由活动等）替换为真实候选地点，
     * 无可用候选时剔除该活动
     */
    private List<Map<String, Object>> replaceNonPlaceVisits(List<Map<String, Object>> activities,
                                                            List<Map<String, Object>> places,
                                                            String city) {
        if (activities == null || activities.isEmpty()) return activities;
        List<Map<String, Object>> pool = new ArrayList<>(CITY_ATTRACTIONS.getOrDefault(city, List.of()));
        Set<String> usedNames = new HashSet<>();
        for (Map<String, Object> a : activities) {
            usedNames.add(String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", ""))));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        boolean changed = false;
        int guard = 0;
        for (Map<String, Object> a : activities) {
            String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "visit")));
            String name = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")));
            boolean visitLike = !"meal".equals(type) && !"restaurant".equals(type) && !"transit".equals(type);
            if (visitLike && isNonPlaceVisit(name)) {
                String replacement = pickNextPlace(pool, places, usedNames, guard++, city, null, null);
                if (replacement == null) {
                    log.warn("剔除非地点游览活动: '{}' (city={})", name, city);
                    changed = true;
                    continue;
                }
                Map<String, Object> na = new HashMap<>(a);
                na.put("name", replacement);
                na.put("poi_name", replacement);
                na.put("poiName", replacement);
                na.remove("lat");
                na.remove("lng");
                log.info("非地点游览活动替换: '{}' -> '{}'", name, replacement);
                out.add(na);
                changed = true;
                continue;
            }
            out.add(a);
        }
        return changed ? out : activities;
    }

    private List<Map<String, Object>> dedupeVisitActivities(List<Map<String, Object>> activities, String city) {
        if (activities == null || activities.isEmpty()) return activities == null ? new ArrayList<>() : activities;
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> seenVisit = new ArrayList<>();
        List<String> seenMeals = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Map<String, Object> a : activities) {
            String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "visit")));
            String name = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", ""))).trim();
            boolean mealLike = "meal".equals(type) || "restaurant".equals(type);
            boolean visitLike = !"meal".equals(type) && !"restaurant".equals(type) && !"transit".equals(type);
            if ((visitLike || mealLike) && !name.isEmpty()) {
                List<String> seen = mealLike ? seenMeals : seenVisit;
                List<String> other = mealLike ? seenVisit : seenMeals;
                boolean dup = false;
                for (String prev : seen) {
                    // 通用餐名（如「大理美食」）非具体餐厅，跨天不去重，避免餐次被整日误删
                    if (mealLike && isGenericMealName(city, name) && isGenericMealName(city, prev)) {
                        continue;
                    }
                    if (samePlace(normalizePlaceKey(name, city), normalizePlaceKey(prev, city))
                            || samePlace(name, prev)) {
                        dup = true;
                        break;
                    }
                }
                // 餐食名与景点名等价也视为重复（如「全聚德」既作 visit 又作 meal）
                if (!dup && mealLike) {
                    for (String prev : other) {
                        if (samePlace(normalizePlaceKey(name, city), normalizePlaceKey(prev, city))) {
                            dup = true;
                            break;
                        }
                    }
                }
                if (dup) {
                    dropped.add(type + ":" + name);
                    continue;
                }
                seen.add(name);
            }
            out.add(a);
        }
        if (out.size() != (activities == null ? 0 : activities.size())) {
            log.info("活动去重: {} -> {}, 剔除: {}", activities.size(), out.size(), dropped);
        }
        return out;
    }

    /**
     * 是否为通用餐名（非具体餐厅）
     *
     * <p>如「大理美食」「本地美食」「川菜」等泛化名跨天重复属正常，不应被全局去重剔除。</p>
     */
    private boolean isGenericMealName(String city, String name) {
        String key = normalizePlaceKey(name, city);
        if (key.isEmpty()) return true;
        if (isKnownRestaurant(city, key)) return false;
        if (isPlaceholderText(key)) return true;
        if (key.endsWith("美食") || key.endsWith("特色餐") || key.equals("本地美食")) return true;
        if (city != null && key.equals(city + "美食")) return true;
        for (String kw : FOOD_KEYWORDS) {
            if (key.equals(kw.trim())) return true;
        }
        return false;
    }

    /** 判断名称是否已是本地餐厅库中的具体餐厅 */
    private boolean isKnownRestaurant(String city, String name) {
        if (name == null || name.isBlank()) return false;
        List<Map<String, Object>> pool = CITY_RESTAURANTS.getOrDefault(city, List.of());
        for (Map<String, Object> r : pool) {
            if (name.equals(String.valueOf(r.get("name")))) return true;
        }
        // 兼容跨城同名库
        for (List<Map<String, Object>> list : CITY_RESTAURANTS.values()) {
            for (Map<String, Object> r : list) {
                if (name.equals(String.valueOf(r.get("name")))) return true;
            }
        }
        return false;
    }

    /** 把泛化的「午餐/晚餐」及 city+美食 占位名替换为具体餐厅名（按顺序就近 + 口味匹配） */
    private List<Map<String, Object>> enrichMealNames(List<Map<String, Object>> activities,
                                                      List<Map<String, Object>> meals, String city) {
        if (activities == null || activities.isEmpty()) return activities;
        Set<String> usedRestaurants = new HashSet<>();
        Double prevLat = null;
        Double prevLng = null;
        List<Map<String, Object>> sorted = new ArrayList<>(activities);
        sorted.sort(Comparator
                .comparingInt((Map<String, Object> a) -> ((Number) a.getOrDefault("day", 1)).intValue())
                .thenComparing(a -> String.valueOf(a.getOrDefault("startTime",
                        a.getOrDefault("scheduled_start", "00:00")))));
        for (Map<String, Object> a : sorted) {
            String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
            String name = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")));
            if ("meal".equals(type)) {
                LocalTime startT = parseTimeSafe(String.valueOf(
                        a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "00:00"))));
                boolean isBreakfast = name.contains("早") || startT.isBefore(LocalTime.of(10, 0));
                boolean hasSlotPrefix = name.contains("早") || name.contains("午") || name.contains("晚");
                boolean alreadySpecific = name.contains("·");
                // 已是具体餐厅但无餐次前缀（如「蜀大侠火锅」）→ 按时间补前缀
                boolean bareRestaurant = !hasSlotPrefix && !name.isBlank()
                        && (isKnownRestaurant(city, name) || (alreadySpecific
                            && isKnownRestaurant(city, name.substring(name.indexOf('·') + 1).trim())));
                boolean placeholder = name.isBlank() || name.contains("午餐") || name.contains("晚餐")
                        || name.contains("午饭") || name.contains("晚饭") || name.contains("美食")
                        || name.contains("meal")
                        || isPlaceholderText(name)
                        || (isBreakfast && !name.contains("早"));
                // 「午餐·川菜」等有 · 但 · 后不是具体餐厅 → 也要升级为餐厅名
                boolean needsRestaurantUpgrade = false;
                if (alreadySpecific && !isBreakfast && hasSlotPrefix) {
                    String after = name.substring(name.indexOf('·') + 1).trim();
                    needsRestaurantUpgrade = !isKnownRestaurant(city, after);
                }
                if ((!alreadySpecific && placeholder) || needsRestaurantUpgrade || bareRestaurant) {
                    String mealType;
                    if (isBreakfast) {
                        mealType = "breakfast";
                    } else {
                        mealType = name.contains("晚") || startT.isAfter(LocalTime.of(16, 0)) ? "dinner"
                                : startT.isBefore(LocalTime.of(10, 0)) ? "breakfast" : "lunch";
                    }
                    String pref;
                    if (bareRestaurant && !hasSlotPrefix) {
                        // 保留原餐厅名作为 pref，只加前缀（不换餐厅）
                        pref = alreadySpecific ? name.substring(name.indexOf('·') + 1).trim() : name;
                    } else if (alreadySpecific) {
                        String after = name.substring(name.indexOf('·') + 1).trim();
                        pref = after.isEmpty() ? mealPreference(meals, mealType) : after;
                    } else {
                        pref = mealPreference(meals, mealType);
                    }
                    String specified = mealSpecifiedRestaurant(meals, mealType);
                    if (!specified.isBlank() && (placeholder || needsRestaurantUpgrade || bareRestaurant)) {
                        // 用户点名的餐厅归入对应午/晚餐，优先使用
                        String slotWord = mealType.equals("breakfast") ? "早餐"
                                : mealType.equals("dinner") ? "晚餐" : "午餐";
                        if (!usedRestaurants.contains(specified)) {
                            a.put("name", slotWord + "·" + specified);
                            if (a.containsKey("poi_name")) a.put("poi_name", slotWord + "·" + specified);
                            if (a.containsKey("scheduled_start")) a.put("poi_name", slotWord + "·" + specified);
                            a.put("notes", slotWord + "·" + specified);
                            usedRestaurants.add(specified);
                            name = slotWord + "·" + specified;
                            if (name.contains("·")) {
                                double[] rc = resolveCoord(name.substring(name.indexOf('·') + 1), city, null);
                                if (rc != null) {
                                    prevLat = rc[0];
                                    prevLng = rc[1];
                                }
                            }
                            continue;
                        }
                    }
                    String label;
                    if (bareRestaurant && !hasSlotPrefix) {
                        String slotWord = mealType.equals("breakfast") ? "早餐"
                                : mealType.equals("dinner") ? "晚餐" : "午餐";
                        label = slotWord + "·" + pref;
                    } else if ("breakfast".equals(mealType)) {
                        Map<String, Object> rest = pickRestaurant(city, pref, prevLat, prevLng, usedRestaurants);
                        String bn = rest != null ? String.valueOf(rest.get("name"))
                                : (pref == null || pref.isBlank() ? city + "早餐" : pref);
                        label = "早餐·" + bn;
                    } else {
                        label = mealDisplayName(mealType, city, pref, prevLat, prevLng, usedRestaurants);
                        // 若仍无餐厅（city 无库），保留原 · 后口味
                        if (label.endsWith("·" + pref) || label.endsWith("·" + city + "美食")) {
                            if (alreadySpecific) {
                                label = (mealType.equals("lunch") ? "午餐·" : "晚餐·") + pref;
                            }
                        }
                    }
                    a.put("name", label);
                    if (a.containsKey("poi_name")) a.put("poi_name", label);
                    if (a.containsKey("scheduled_start")) a.put("poi_name", label);
                    a.put("notes", label);
                    name = label;
                }
                if (name.contains("·")) {
                    double[] rc = resolveCoord(name.substring(name.indexOf('·') + 1), city, null);
                    if (rc != null) {
                        prevLat = rc[0];
                        prevLng = rc[1];
                    }
                }
            } else {
                double[] c = resolveCoord(name, city, a);
                if (c != null) {
                    prevLat = c[0];
                    prevLng = c[1];
                }
            }
        }
        return activities;
    }

    /** 同天活动按时间排序并顺延，保证真实路程时间反映到下一起点：
     * start_i ≥ end_{i-1} + travelTimeMin_{i-1}。只后推不前拉。
     * 迭代裁剪 21:00 作息窗口：某活动结束晚于 21:00 时，优先截断该活动；
     * 若是用餐被挤到 21:00 之后，则剔除它前面最近的非用餐活动腾出时间；
     * 两边都无法容纳时才剔除该活动。每轮必有进展，循环必然终止。
     */
    private List<Map<String, Object>> fixTimeOverlaps(List<Map<String, Object>> activities) {
        if (activities == null || activities.isEmpty()) return activities;
        final LocalTime dayEnd = LocalTime.of(21, 0);
        java.util.TreeMap<Integer, List<Map<String, Object>>> byDay = new java.util.TreeMap<>();
        for (Map<String, Object> a : activities) {
            byDay.computeIfAbsent(((Number) a.getOrDefault("day", 1)).intValue(), k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int trimmed = 0;
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byDay.entrySet()) {
            List<Map<String, Object>> dayActs = new ArrayList<>(e.getValue());
            dayActs.sort(Comparator.comparing(a -> String.valueOf(
                    a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "00:00")))));
            // 先整体前移回收活动之间的多余空隙（用餐不早于作息窗口），为 21:00 前收尾留出余量
            pullDayLeft(dayActs);
            boolean pulled = false;
            while (!dayActs.isEmpty()) {
                List<LocalTime> starts = new ArrayList<>();
                List<Integer> durs = new ArrayList<>();
                LocalTime prevEnd = null;
                int prevTravel = 0;
                int over = -1;
                boolean overMeal = false;
                LocalTime overStart = null;
                for (int i = 0; i < dayActs.size(); i++) {
                    Map<String, Object> a = dayActs.get(i);
                    boolean meal = isMealActivity(a);
                    LocalTime start = parseTimeSafe(String.valueOf(
                            a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00"))));
                    int dur = toIntSafe(a.get(durationKey(a)));
                    if (dur <= 0) dur = 60;
                    // 真实路程时间：下一起点不早于上一活动结束 + 上一活动到下一段的行程耗时
                    if (prevEnd != null) {
                        LocalTime earliest = prevEnd.plusMinutes(Math.max(0, prevTravel));
                        if (start.isBefore(earliest)) {
                            start = earliest;
                        }
                    }
                    starts.add(start);
                    durs.add(dur);
                    if (over < 0 && start.plusMinutes(dur).isAfter(dayEnd)) {
                        over = i;
                        overMeal = meal;
                        overStart = start;
                    }
                    prevEnd = start.plusMinutes(dur);
                    prevTravel = toIntSafe(a.get("travelTimeMin"));
                }
                if (over < 0) {
                    for (int i = 0; i < dayActs.size(); i++) {
                        applyActivityTime(dayActs.get(i), starts.get(i), durs.get(i));
                    }
                    out.addAll(dayActs);
                    break;
                }
                // 先整体前移回收空隙（用餐不早于作息窗口），避免直接裁掉活动导致节奏时长不达标
                if (!pulled) {
                    pulled = pullDayLeft(dayActs);
                    if (pulled) {
                        continue;
                    }
                }
                if (!overMeal && overStart.isBefore(dayEnd)) {
                    int remain = dayEnd.toSecondOfDay() / 60 - overStart.toSecondOfDay() / 60;
                    if (remain >= 15) {
                        applyActivityTime(dayActs.get(over), overStart, remain);
                        continue;
                    }
                }
                if (overMeal) {
                    // 用餐被挤到 21:00 之后：先剔除其前面最近的非用餐活动，保住正餐
                    int victim = -1;
                    for (int k = over - 1; k >= 0; k--) {
                        if (!isMealActivity(dayActs.get(k))) {
                            victim = k;
                            break;
                        }
                    }
                    if (victim >= 0) {
                        dayActs.remove(victim);
                        trimmed++;
                        continue;
                    }
                    if (overStart.isBefore(dayEnd)) {
                        int remain = dayEnd.toSecondOfDay() / 60 - overStart.toSecondOfDay() / 60;
                        if (remain >= 15) {
                            applyActivityTime(dayActs.get(over), overStart, remain);
                            continue;
                        }
                    }
                }
                dayActs.remove(over);
                trimmed++;
            }
        }
        if (trimmed > 0) {
            log.info("时间顺延裁剪超出 21:00 的活动: {} 个", trimmed);
        }
        out.sort((a, b) -> {
            int d1 = ((Number) a.getOrDefault("day", 1)).intValue();
            int d2 = ((Number) b.getOrDefault("day", 1)).intValue();
            if (d1 != d2) return Integer.compare(d1, d2);
            return String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "00:00")))
                    .compareTo(String.valueOf(b.getOrDefault("startTime", b.getOrDefault("scheduled_start", "00:00"))));
        });
        return out;
    }

    /**
     * 整体把当日活动向前收拢，回收活动之间的多余空隙。
     * 下一起点 = 上一活动结束 + 真实路程时间；用餐最早只收到其作息窗口起点（早 7:30 / 午 11:30 / 晚 17:30）。
     *
     * @return 是否发生了前移
     */
    private boolean pullDayLeft(List<Map<String, Object>> dayActs) {
        boolean changed = false;
        LocalTime prevEnd = null;
        int prevTravel = 0;
        for (int i = 0; i < dayActs.size(); i++) {
            Map<String, Object> a = dayActs.get(i);
            int dur = toIntSafe(a.get(durationKey(a)));
            if (dur <= 0) dur = 60;
            LocalTime start = parseTimeSafe(String.valueOf(
                    a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00"))));
            LocalTime floor = (i == 0) ? LocalTime.of(8, 0) : null;
            if (prevEnd != null) {
                LocalTime minStart = prevEnd.plusMinutes(Math.max(0, prevTravel));
                if (floor == null || minStart.isAfter(floor)) {
                    floor = minStart;
                }
            }
            if (isMealActivity(a)) {
                String name = activityName(a);
                LocalTime winStart = null;
                if (name.contains("早")) winStart = LocalTime.of(7, 30);
                else if (name.contains("午")) winStart = LocalTime.of(11, 30);
                else if (name.contains("晚")) winStart = LocalTime.of(17, 30);
                if (winStart != null && (floor == null || winStart.isAfter(floor))) {
                    floor = winStart;
                }
            }
            if (floor != null && start.isAfter(floor)) {
                applyActivityTime(a, floor, dur);
                start = floor;
                changed = true;
            }
            prevEnd = start.plusMinutes(dur);
            prevTravel = toIntSafe(a.get("travelTimeMin"));
        }
        return changed;
    }

    private boolean isMealActivity(Map<String, Object> a) {
        String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "visit")));
        return "meal".equals(type) || "restaurant".equals(type) || "breakfast".equals(type);
    }

    /** 每日餐次归一：同一天最多保留 1 顿早餐/午餐/晚餐（按时间取第一顿），并按时间排序 */
    private List<Map<String, Object>> normalizeDailyMeals(List<Map<String, Object>> activities) {
        if (activities == null || activities.isEmpty()) return activities;
        java.util.TreeMap<Integer, List<Map<String, Object>>> byDay = new java.util.TreeMap<>();
        for (Map<String, Object> a : activities) {
            byDay.computeIfAbsent(((Number) a.getOrDefault("day", 1)).intValue(), k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byDay.entrySet()) {
            List<Map<String, Object>> dayActs = new ArrayList<>(e.getValue());
            dayActs.sort(Comparator.comparing(a -> String.valueOf(
                    a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "00:00")))));
            Set<String> keptMeals = new HashSet<>();
            for (Map<String, Object> a : dayActs) {
                String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
                if ("meal".equals(type)) {
                    String name = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")));
                    LocalTime startT = parseTimeSafe(String.valueOf(
                            a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "00:00"))));
                    String slot = name.contains("早") ? "b"
                            : name.contains("晚") ? "d"
                            : name.contains("午") ? "l"
                            : inferMealSlotFromTime(startT);
                    if (!keptMeals.add(slot)) {
                        continue; // 同 slot 第二顿丢弃
                    }
                }
                out.add(a);
            }
        }
        out.sort((a, b) -> {
            int d1 = ((Number) a.getOrDefault("day", 1)).intValue();
            int d2 = ((Number) b.getOrDefault("day", 1)).intValue();
            if (d1 != d2) return Integer.compare(d1, d2);
            return String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "00:00")))
                    .compareTo(String.valueOf(b.getOrDefault("startTime", b.getOrDefault("scheduled_start", "00:00"))));
        });
        if (out.size() != activities.size()) {
            log.info("每日餐次归一: {} -> {}", activities.size(), out.size());
        }
        return out;
    }

    /** 按开始时间推断餐次 slot（b/l/d） */
    private String inferMealSlotFromTime(LocalTime t) {
        if (t == null) return "l";
        if (t.isBefore(LocalTime.of(10, 0))) return "b";
        if (t.isAfter(LocalTime.of(16, 0))) return "d";
        return "l";
    }

    /**
     * 完整性兜底：确保每天至少有 4 个活动（含午餐/晚餐），缺失天数用本地库补齐
     *
     * @param excludePois 换版排除列表（非换版为空）：城市候选池据此过滤，避免兜底把排除景点补回
     * @param rawInput    用户原文（排除的兜底取舍：原文提到的主题地点保留）
     */
    private List<Map<String, Object>> ensureCompleteItinerary(
            List<Map<String, Object>> activities,
            List<Map<String, Object>> places,
            List<Map<String, Object>> meals,
            String timeStart, String timeEnd, String city, TripPace pace,
            List<String> excludePois, String rawInput) {
        int totalDays = calcTotalDays(timeStart, timeEnd);
        // 每日活动数下限随节奏变化：紧凑 6（4 景点+2 餐）、适中 5、宽松 4
        int minPerDay = pace.getMinDailyActivities();
        log.info("完整性兜底节奏: {} {}, 每日至少 {} 个活动 / {} 个游览 / 游览时长 ≥ {} 分钟",
                pace.getLabel(), pace.getCode(), minPerDay, pace.getMinVisits(), pace.getMinHours() * 60);
        // 紧凑节奏：正餐压缩到 60 分钟，把时间让给游览（后续人性化校准会按新时长重排）
        if (pace == TripPace.COMPACT && activities != null) {
            int compressed = 0;
            for (Map<String, Object> a : activities) {
                String mealType = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
                if ("meal".equals(mealType) && toIntSafe(a.get(durationKey(a))) > 60) {
                    setDurationMinutes(a, 60);
                    compressed++;
                }
            }
            if (compressed > 0) {
                log.info("紧凑节奏: 已把 {} 个正餐时长压缩到 60 分钟", compressed);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        if (activities != null) {
            activities.forEach(a -> usedNames.add(String.valueOf(a.get("name"))));
        }

        List<Map<String, Object>> cityPool = buildCityPool(city);
        removeExcludedFromPool(cityPool, excludePois, rawInput);
        int poolIdx = 0;
        Set<String> usedRestaurants = new HashSet<>();
        // 已出现的餐厅（如午餐·楼外楼）禁止在补晚餐时复用，避免补出的餐次被去重剔除
        if (activities != null) {
            for (Map<String, Object> a : activities) {
                String n = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")));
                if (n.contains("·")) {
                    usedRestaurants.add(n.substring(n.indexOf('·') + 1));
                }
            }
        }
        Double lastLat = null;
        Double lastLng = null;

        for (int day = 1; day <= totalDays; day++) {
            List<Map<String, Object>> dayActs = new ArrayList<>();
            if (activities != null) {
                for (Map<String, Object> a : activities) {
                    int d = ((Number) a.getOrDefault("day", 1)).intValue();
                    if (d == day) dayActs.add(a);
                }
            }

            boolean hasLunch = dayActs.stream().anyMatch(a -> "meal".equals(String.valueOf(
                            a.getOrDefault("type", a.getOrDefault("activity_type", ""))))
                    && String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", ""))).contains("午"));
            boolean hasDinner = dayActs.stream().anyMatch(a -> "meal".equals(String.valueOf(
                            a.getOrDefault("type", a.getOrDefault("activity_type", ""))))
                    && String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", ""))).contains("晚"));
            long visitCount = dayActs.stream().filter(a -> {
                String t = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
                return !"meal".equals(t) && !"transit".equals(t) && !"breakfast".equals(t);
            }).count();

            // 总数不足、缺正餐、或游览类活动/游览时长未达节奏下限时进入补齐
            if (dayActs.size() < minPerDay || !hasLunch || !hasDinner
                    || visitCount < pace.getMinVisits()
                    || visitMinutes(dayActs) < pace.getMinHours() * 60) {
                // 重新按时间线排列并补齐
                dayActs.sort(Comparator.comparing(a -> String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00")))));
                LocalTime cursor = dayActs.isEmpty()
                        ? LocalTime.of(8, 0)
                        : parseTimeSafe(String.valueOf(dayActs.get(dayActs.size() - 1)
                            .getOrDefault("endTime", dayActs.get(dayActs.size() - 1).getOrDefault("scheduled_end", "10:00"))));

                // 先补午餐
                if (!hasLunch && cursor.getHour() >= 11) {
                    Map<String, Object> lunch = newAct(day,
                            mealDisplayName("lunch", city, mealPreference(meals, "lunch"), lastLat, lastLng, usedRestaurants),
                            "meal", cursor, 60);
                    dayActs.add(lunch);
                    cursor = cursor.plusMinutes(75);
                    hasLunch = true;
                }

                // 补游览活动直到达标（就近选择）；无候选时用占位活动填满
                long visitSoFar = dayActs.stream().filter(a -> {
                    String t = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
                    return !"meal".equals(t) && !"transit".equals(t);
                }).count();
                int guard = 0;
                while ((dayActs.size() < minPerDay
                        || visitSoFar < pace.getMinVisits()
                        || visitMinutes(dayActs) < pace.getMinHours() * 60)
                        && guard++ < 20 && cursor.isBefore(LocalTime.of(21, 0))) {
                    String name = pickNextPlace(cityPool, places, usedNames, poolIdx++, city, lastLat, lastLng);
                    if (name == null) {
                        name = "自由活动/市区漫步(" + day + "-" + dayActs.size() + ")";
                    }
                    int dur = name.contains("街") || name.contains("巷") ? 90 : 60;
                    dayActs.add(newAct(day, name, "visit", cursor, dur));
                    visitSoFar++;
                    double[] nc = resolveCoord(name, city, null);
                    if (nc != null) {
                        lastLat = nc[0];
                        lastLng = nc[1];
                    }
                    cursor = cursor.plusMinutes(dur + 20);
                    if (!hasLunch && cursor.getHour() >= 11 && cursor.getHour() < 14) {
                        dayActs.add(newAct(day,
                                mealDisplayName("lunch", city, mealPreference(meals, "lunch"), lastLat, lastLng, usedRestaurants),
                                "meal", cursor, 60));
                        cursor = cursor.plusMinutes(75);
                        hasLunch = true;
                    }
                    if (cursor.getHour() >= 17 && cursor.getHour() <= 19 && !hasDinner) {
                        LocalTime dinnerStart = cursor.isBefore(LocalTime.of(17, 30)) ? LocalTime.of(17, 30) : cursor;
                        dayActs.add(newAct(day,
                                mealDisplayName("dinner", city, mealPreference(meals, "dinner"), lastLat, lastLng, usedRestaurants),
                                "meal", dinnerStart, 60));
                        cursor = dinnerStart.plusMinutes(75);
                        hasDinner = true;
                    }
                }

                // 强制补午餐（即使景点已耗尽；时间窗 11:00-14:00，或当天尚无正餐且未过 14:00）
                if (!hasLunch) {
                    LocalTime lunchAt = cursor.isBefore(LocalTime.of(11, 30)) ? LocalTime.of(11, 30) : cursor;
                    if (lunchAt.getHour() < 14 && !dayActs.isEmpty()) {
                        dayActs.add(newAct(day,
                                mealDisplayName("lunch", city, mealPreference(meals, "lunch"), lastLat, lastLng, usedRestaurants),
                                "meal", lunchAt, 60));
                        if (cursor.isBefore(lunchAt.plusMinutes(75))) {
                            cursor = lunchAt.plusMinutes(75);
                        }
                        hasLunch = true;
                    }
                }

                // 强制补晚餐（17:00-20:00 窗口；当天已有活动则必补）
                if (!hasDinner && !dayActs.isEmpty()) {
                    LocalTime dinnerStart = cursor.isBefore(LocalTime.of(17, 30))
                            ? LocalTime.of(17, 30)
                            : cursor;
                    if (dinnerStart.getHour() >= 20 || (dinnerStart.getHour() == 19 && dinnerStart.getMinute() > 30)) {
                        dinnerStart = LocalTime.of(19, 0);
                    }
                    if (dinnerStart.getHour() >= 16) {
                        dayActs.add(newAct(day,
                                mealDisplayName("dinner", city, mealPreference(meals, "dinner"), lastLat, lastLng, usedRestaurants),
                                "meal", dinnerStart, 60));
                        hasDinner = true;
                    }
                }
                // 晚间活动（最晚 21:00 结束）
                if (dayActs.size() < minPerDay) {
                    String night = pickNextPlace(cityPool, places, usedNames, poolIdx++, city, lastLat, lastLng);
                    if (night != null) {
                        LocalTime nightStart = LocalTime.of(19, 0).isBefore(cursor) ? cursor : LocalTime.of(19, 0);
                        if (nightStart.isAfter(LocalTime.of(20, 0))) {
                            nightStart = LocalTime.of(19, 30);
                        }
                        int nightDur = (LocalTime.of(21, 0).toSecondOfDay() - nightStart.toSecondOfDay()) / 60;
                        if (nightDur >= 30) {
                            dayActs.add(newAct(day, night, "visit", nightStart, Math.min(90, nightDur)));
                        }
                    }
                }
            }

            // 缺口填充放到结构定稿（去重/餐次归一）之后执行，见 fillPaceGaps

            dayActs.sort(Comparator.comparing(a -> String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00")))));
            // 统一字段名为 LLM 格式
            for (Map<String, Object> a : dayActs) {
                if (!a.containsKey("startTime") && a.containsKey("scheduled_start")) {
                    a.put("startTime", a.get("scheduled_start"));
                    a.put("endTime", a.getOrDefault("scheduled_end", a.get("scheduled_start")));
                    a.put("type", a.getOrDefault("activity_type", "visit"));
                    a.put("name", a.getOrDefault("poi_name", a.get("name")));
                }
                if (!a.containsKey("day")) a.put("day", day);
                result.add(a);
            }
        }

        log.info("完整性兜底完成: {} 天, 共 {} 个活动", totalDays, result.size());
        return result;
    }

    /** 城市候选池：静态景点 + 高德真实 POI（博物馆/商圈/夜市/公园），兜底时避免产出占位名 */
    private List<Map<String, Object>> buildCityPool(String city) {
        List<Map<String, Object>> pool = new ArrayList<>(
                CITY_ATTRACTIONS.getOrDefault(city, List.of()));
        if (city != null && !city.isBlank() && pool.size() < 12) {
            try {
                List<Map<String, Object>> pois = poiSearchService.searchCityDiverse(city, 14);
                for (Map<String, Object> poi : pois) {
                    if (pool.stream().noneMatch(p ->
                            samePlace(String.valueOf(p.get("name")), String.valueOf(poi.get("name"))))) {
                        pool.add(poi);
                    }
                }
                log.info("完整性兜底城市候选池: {} 静态 + POI 补充后 {} 条 (city={})",
                        CITY_ATTRACTIONS.getOrDefault(city, List.of()).size(), pool.size(), city);
            } catch (Exception poiEx) {
                log.warn("POI 候选池补充失败: {}", poiEx.getMessage());
            }
        }
        return pool;
    }

    /**
     * 节奏缺口填充（结构定稿后执行）：去重/餐次归一之后再按节奏补满每日空闲时间窗，
     * 避免补出的活动被后续去重剔除。必须位于 applyPaceBudget（预算裁剪）之前。
     */
    private List<Map<String, Object>> fillPaceGaps(List<Map<String, Object>> activities, String city, TripPace pace,
                                                   List<String> excludePois, String rawInput) {
        if (activities == null || activities.isEmpty()) {
            return activities;
        }
        List<Map<String, Object>> pool = buildCityPool(city);
        removeExcludedFromPool(pool, excludePois, rawInput);
        if (pool.isEmpty()) {
            return activities;
        }
        Set<String> usedNames = new HashSet<>();
        for (Map<String, Object> a : activities) {
            usedNames.add(String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", ""))));
        }
        Map<Integer, List<Map<String, Object>>> byDay = new TreeMap<>();
        for (Map<String, Object> a : activities) {
            int day = toIntSafe(a.get("day"));
            byDay.computeIfAbsent(day <= 0 ? 1 : day, k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> out = new ArrayList<>(activities.size());
        int poolIdx = 0;
        int totalAdded = 0;
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byDay.entrySet()) {
            List<Map<String, Object>> dayActs = new ArrayList<>(entry.getValue());
            int added = fillDayGaps(dayActs, pool, null, usedNames, poolIdx, entry.getKey(), city, pace);
            poolIdx += Math.max(1, added);
            totalAdded += added;
            dayActs.sort(Comparator.comparing(a -> String.valueOf(
                    a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00")))));
            out.addAll(dayActs);
        }
        if (totalAdded > 0) {
            log.info("节奏缺口填充: 共补 {} 个活动, 全程游览 {} 分钟", totalAdded, visitMinutes(out));
        }
        return out;
    }

    /**
     * 当日游览总时长是否未达所选节奏的下限（任一整天低于下限即返回 true）
     */
    private boolean belowPaceFloor(List<Map<String, Object>> activities, TripPace pace) {
        if (activities == null || activities.isEmpty()) {
            return false;
        }
        int target = pace.getMinHours() * 60;
        Map<Integer, List<Map<String, Object>>> byDay = new TreeMap<>();
        for (Map<String, Object> a : activities) {
            int day = toIntSafe(a.get("day"));
            byDay.computeIfAbsent(day <= 0 ? 1 : day, k -> new ArrayList<>()).add(a);
        }
        for (List<Map<String, Object>> dayActs : byDay.values()) {
            if (visitMinutes(dayActs) < target) {
                return true;
            }
        }
        return false;
    }

    /**
     * 二次节奏补时：真实路网校正、人性化校准与 21:00 顺延之后，每日往往仍留有空隙。
     * 在定稿时间上再补一轮缺口；补到内容后重新做预算裁剪、路网校正与时间顺延。
     *
     * @return 补时后的活动（无变化或异常时原样返回）
     */
    private List<Map<String, Object>> refillPaceGaps(List<Map<String, Object>> activities,
                                                     List<Map<String, Object>> places,
                                                     String city, TripPace pace,
                                                     List<String> excludePois, String rawInput) {
        try {
            int before = visitMinutes(activities);
            int sizeBefore = activities.size();
            List<Map<String, Object>> refilled = fillPaceGaps(activities, city, pace, excludePois, rawInput);
            if (visitMinutes(refilled) <= before && refilled.size() <= sizeBefore) {
                return activities;
            }
            refilled = applyPaceBudget(refilled, pace);
            refilled = correctActivitiesWithRealData(refilled, places, city);
            try {
                refilled = applyHumanizedTiming(refilled);
            } catch (Exception humanEx) {
                log.warn("二次人性化校准失败: {}", humanEx.getMessage());
            }
            refilled = normalizeDailyMeals(refilled);
            refilled = dedupeVisitActivities(refilled, city);
            refilled = fixTimeOverlaps(refilled);
            log.info("二次节奏补时: 每日游览 {} -> {} 分钟", before, visitMinutes(refilled));
            return refilled;
        } catch (Exception ex) {
            log.warn("二次节奏补时失败，保留首轮结果: {}", ex.getMessage());
            return activities;
        }
    }

    /**
     * 缺口填充：把 08:00-21:00 之间 ≥75 分钟的空白补上游览活动，
     * 直到当日游览时长与景点数达到所选节奏的下限（候选耗尽即停，不产出占位名）。
     * 只在已有活动之间插入，不会把当天推到 21:00 之后。
     *
     * @return 本次新增的活动数
     */
    private int fillDayGaps(List<Map<String, Object>> dayActs, List<Map<String, Object>> cityPool,
                            List<Map<String, Object>> places, Set<String> usedNames,
                            int poolIdx, int day, String city, TripPace pace) {
        final int close = 21 * 60;
        int targetMinutes = pace.getMinHours() * 60;
        int added = 0;
        int guard = 12;
        while (guard-- > 0
                && (visitMinutes(dayActs) < targetMinutes || visitCount(dayActs) < pace.getMinVisits())
                && visitCount(dayActs) < pace.getMaxVisits() + 2) {
            List<int[]> windows = freeWindows(dayActs);
            if (windows.isEmpty()) {
                break;
            }
            windows.sort((x, y) -> (y[1] - y[0]) - (x[1] - x[0]));

            // 阶段一：在能容纳新活动的时间窗插入一个景点
            boolean progressed = false;
            for (int[] win : windows) {
                int room = win[1] - win[0];
                boolean trailing = win[1] >= close;
                // 起点至少顺延出“上一活动 → 下一段”的真实路程（无真实数据时按 30 分钟兜底），
                // 否则插入后会被顺延、进而把当天推过 21:00 而整条裁掉
                int prevIdx = indexOfActivityEndingAt(dayActs, win[0]);
                int knownTravel = prevIdx >= 0 ? toIntSafe(dayActs.get(prevIdx).get("travelTimeMin")) : 0;
                int lead = Math.max(trailing ? 35 : 30, Math.min(60, knownTravel + 5));
                int trail = trailing ? 0 : 30;
                int dur = Math.min(120, room - lead - trail);
                if (dur < 30) {
                    continue;
                }
                // 景点数已达到所选节奏上限，不再插入（时长不够时走下面的延展）
                if (visitCount(dayActs) >= pace.getMaxVisits()) {
                    break;
                }
                String name = pickUniquePlace(cityPool, places, usedNames, poolIdx + added, city);
                if (name == null) {
                    log.info("day{} 缺口填充候选耗尽，停止", day);
                    return added;
                }
                int start = win[0] + lead;
                LocalTime st = LocalTime.of(Math.min(20, start / 60), start % 60);
                dayActs.add(newAct(day, name, "visit", st, dur));
                added++;
                progressed = true;
                log.info("day{} 缺口填充: 插入「{}」 {}~{} ({}分钟), 当前游览 {} 分钟 / {} 个景点",
                        day, name, st, st.plusMinutes(dur), dur,
                        visitMinutes(dayActs), visitCount(dayActs));
                break;
            }
            if (progressed) {
                continue;
            }
            StringBuilder winDump = new StringBuilder();
            for (int[] w : windows) {
                winDump.append(toHhMm(w[0])).append('~').append(toHhMm(w[1]))
                        .append('(').append(w[1] - w[0]).append(") ");
            }
            log.info("day{} 缺口填充: 无可用插入窗，窗口={}, 游览 {} 分钟 / {} 个景点",
                    day, winDump, visitMinutes(dayActs), visitCount(dayActs));

            // 阶段二：窗口装不下新活动时，把其前面的游览活动延长（不新增交通、不推后当天结束时间）
            for (int[] win : windows) {
                int room = win[1] - win[0];
                if (room < 45) {
                    continue;
                }
                int prevIdx = indexOfActivityEndingAt(dayActs, win[0]);
                if (prevIdx < 0) {
                    continue;
                }
                Map<String, Object> prev = dayActs.get(prevIdx);
                if (!isVisitActivity(prev)) {
                    continue;
                }
                // 只吃掉“真实路程 + 一点余量”之外的空隙，避免把下一起点推后
                int reserve = Math.min(60, toIntSafe(prev.get("travelTimeMin")) + 5);
                int extra = Math.min(70, room - Math.max(30, reserve));
                if (extra < 15) {
                    continue;
                }
                int newDur = Math.min(240, toIntSafe(prev.get(durationKey(prev))) + extra);
                setDurationMinutes(prev, newDur);
                progressed = true;
                log.info("day{} 缺口延展: 「{}」延长到 {} 分钟 (补充 {} 分钟), 当前游览 {} 分钟",
                        day, activityName(prev), newDur, extra, visitMinutes(dayActs));
                break;
            }
            if (!progressed) {
                break;
            }
        }
        return added;
    }

    /** 找出结束时间恰好等于指定分钟数的活动下标（找不到返回 -1） */
    private int indexOfActivityEndingAt(List<Map<String, Object>> dayActs, int minute) {
        for (int i = dayActs.size() - 1; i >= 0; i--) {
            Map<String, Object> a = dayActs.get(i);
            int e = toMinuteOfDay(a.getOrDefault("endTime", a.getOrDefault("scheduled_end", "")));
            if (e >= 0 && Math.abs(e - minute) <= 2) {
                return i;
            }
        }
        return -1;
    }

    /** 从候选池取一个与已用名称不重复（含「锦里/锦里古街」这类近似名）的景点 */
    private String pickUniquePlace(List<Map<String, Object>> cityPool, List<Map<String, Object>> places,
                                   Set<String> usedNames, int idx, String city) {
        List<String> candidates = new ArrayList<>();
        if (places != null) {
            for (Map<String, Object> p : places) {
                String n = String.valueOf(p.get("name"));
                if (!usedNames.contains(n) && !"restaurant".equals(String.valueOf(p.getOrDefault("type", "")))) {
                    candidates.add(n);
                }
            }
        }
        if (candidates.isEmpty() && cityPool != null && !cityPool.isEmpty()) {
            for (int i = 0; i < cityPool.size(); i++) {
                Map<String, Object> p = cityPool.get((idx + i) % cityPool.size());
                String n = String.valueOf(p.get("name"));
                if (!usedNames.contains(n)) {
                    candidates.add(n);
                }
            }
        }
        for (String cand : candidates) {
            if (usedNames.stream().noneMatch(u -> samePlace(u, cand))) {
                usedNames.add(cand);
                return cand;
            }
        }
        return null;
    }

    /** 覆盖活动时长并同步结束时间（结束时间仅在可解析起始时间时更新） */
    private void setDurationMinutes(Map<String, Object> a, int minutes) {
        if (a.containsKey("durationMin")) a.put("durationMin", minutes);
        if (a.containsKey("duration_min")) a.put("duration_min", minutes);
        if (!a.containsKey("durationMin") && !a.containsKey("duration_min")) a.put("durationMin", minutes);
        int s = toMinuteOfDay(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "")));
        if (s >= 0) {
            int end = s + Math.max(1, minutes);
            String endStr = String.format("%02d:%02d", Math.min(23, end / 60), end % 60);
            if (a.containsKey("endTime")) a.put("endTime", endStr);
            if (a.containsKey("scheduled_end")) a.put("scheduled_end", endStr);
        }
    }

    /** 当日 08:00-21:00 的空闲时间窗（排除已有活动占用时段，允许活动重叠） */
    private List<int[]> freeWindows(List<Map<String, Object>> dayActs) {
        final int open = 8 * 60;
        final int close = 21 * 60;
        List<int[]> occupied = new ArrayList<>();
        for (Map<String, Object> a : dayActs) {
            int s = toMinuteOfDay(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "")));
            if (s < 0) {
                continue;
            }
            int e = toMinuteOfDay(a.getOrDefault("endTime", a.getOrDefault("scheduled_end", "")));
            if (e < 0) {
                e = s + Math.max(30, toIntSafe(a.get(durationKey(a))));
            }
            if (e <= s) {
                e = s + 60;
            }
            if (s < open) s = open;
            if (e > close) e = close;
            if (e <= s) {
                continue;
            }
            occupied.add(new int[]{s, e});
        }
        occupied.sort(Comparator.comparingInt(iv -> iv[0]));
        List<int[]> out = new ArrayList<>();
        int cursor = open;
        for (int[] iv : occupied) {
            if (iv[0] > cursor + 1) {
                out.add(new int[]{cursor, iv[0]});
            }
            cursor = Math.max(cursor, iv[1]);
        }
        if (cursor + 1 < close) {
            out.add(new int[]{cursor, close});
        }
        return out;
    }

    /** 是否为游览类活动（排除用餐与交通） */
    private boolean isVisitActivity(Map<String, Object> a) {
        String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
        return switch (type.toLowerCase()) {
            case "meal", "breakfast", "lunch", "dinner", "transit", "restaurant", "free", "freetime" -> false;
            default -> !type.isBlank();
        };
    }

    /** 当日游览总时长（分钟，不含用餐与交通） */
    private int visitMinutes(List<Map<String, Object>> acts) {
        int total = 0;
        for (Map<String, Object> a : acts) {
            if (isVisitActivity(a)) {
                total += toIntSafe(a.get(durationKey(a)));
            }
        }
        return total;
    }

    /** 当日游览活动个数 */
    private int visitCount(List<Map<String, Object>> acts) {
        int count = 0;
        for (Map<String, Object> a : acts) {
            if (isVisitActivity(a)) count++;
        }
        return count;
    }

    private String activityName(Map<String, Object> a) {
        Object name = a.get("name");
        if (name == null) name = a.get("poi_name");
        return name == null ? "" : String.valueOf(name);
    }

    /**
     * 活动频率预算：按用户所选节奏裁剪每日游览时长
     *
     * 每日游览时长（不含用餐与交通）超过节奏上限时，从最靠后、且非 must 的游览活动开始剔除；
     * 每天至少保留节奏下限的游览活动，午餐/晚餐永不参与裁剪。
     * 裁剪发生在真实路网时间校正之前，保证后续计算基于最终活动结构。
     */
    private List<Map<String, Object>> applyPaceBudget(List<Map<String, Object>> activities, TripPace pace) {
        if (activities == null || activities.isEmpty()) {
            return activities;
        }
        Set<Map<String, Object>> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, List<Map<String, Object>>> byDay = new TreeMap<>();
        for (Map<String, Object> a : activities) {
            int day = toIntSafe(a.get("day"));
            byDay.computeIfAbsent(day <= 0 ? 1 : day, k -> new ArrayList<>()).add(a);
        }
        int maxMinutes = pace.getMaxHours() * 60;
        int removed = 0;
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byDay.entrySet()) {
            List<Map<String, Object>> dayActs = new ArrayList<>(entry.getValue());
            log.info("活动频率预算 day{}: 游览 {} 分钟 / {} 个景点, 上限 {} 分钟 / {} 个景点",
                    entry.getKey(), visitMinutes(dayActs), visitCount(dayActs),
                    maxMinutes, pace.getMaxVisits());
            if (visitMinutes(dayActs) <= maxMinutes && visitCount(dayActs) <= pace.getMaxVisits()) {
                continue;
            }
            int guard = 50;
            while ((visitMinutes(dayActs) > maxMinutes || visitCount(dayActs) > pace.getMaxVisits())
                    && visitCount(dayActs) > pace.getMinVisits()
                    && guard-- > 0) {
                int victim = -1;
                for (int i = dayActs.size() - 1; i >= 0; i--) {
                    Map<String, Object> a = dayActs.get(i);
                    if (isVisitActivity(a) && !"must".equals(String.valueOf(a.getOrDefault("priority", "")))) {
                        victim = i;
                        break;
                    }
                }
                if (victim < 0) {
                    for (int i = dayActs.size() - 1; i >= 0; i--) {
                        if (isVisitActivity(dayActs.get(i))) {
                            victim = i;
                            break;
                        }
                    }
                }
                if (victim < 0) {
                    break;
                }
                Map<String, Object> gone = dayActs.remove(victim);
                dropped.add(gone);
                removed++;
                log.info("活动频率裁剪 day{}: 去掉「{}」({}分钟, priority={})",
                        entry.getKey(), activityName(gone), toIntSafe(gone.get(durationKey(gone))),
                        gone.getOrDefault("priority", ""));
            }
            int after = visitMinutes(dayActs);
            if (after > maxMinutes) {
                log.warn("day{} 游览时长 {} 分钟仍超 {} 节奏上限 {} 分钟（已到每日下限 {} 个游览，停止裁剪）",
                        entry.getKey(), after, pace.getCode(), maxMinutes, pace.getMinVisits());
            }
        }
        if (removed == 0) {
            return activities;
        }
        log.info("活动频率预算: {} {} 裁掉 {} 个超预算游览活动（每日上限 {} 小时）",
                pace.getLabel(), pace.getCode(), removed, pace.getMaxHours());
        List<Map<String, Object>> out = new ArrayList<>(activities.size());
        for (Map<String, Object> a : activities) {
            if (!dropped.contains(a)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * 人性化时间校准：用餐符合作息窗口，每日行程 21:00 前结束
     */
    private List<Map<String, Object>> applyHumanizedTiming(List<Map<String, Object>> activities) {
        if (activities == null || activities.isEmpty()) {
            return activities;
        }

        final LocalTime breakfastStart = LocalTime.of(7, 30);
        final LocalTime breakfastEnd = LocalTime.of(9, 0);
        final LocalTime lunchStart = LocalTime.of(11, 30);
        final LocalTime lunchEnd = LocalTime.of(13, 0);
        final LocalTime dinnerStart = LocalTime.of(17, 30);
        final LocalTime dinnerEnd = LocalTime.of(19, 30);
        final LocalTime dayEnd = LocalTime.of(21, 0);

        java.util.TreeMap<Integer, List<Map<String, Object>>> byDay = new java.util.TreeMap<>();
        for (Map<String, Object> a : activities) {
            int day = ((Number) a.getOrDefault("day", 1)).intValue();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(a);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byDay.entrySet()) {
            List<Map<String, Object>> dayActs = new ArrayList<>(entry.getValue());
            dayActs.sort(Comparator.comparing(a ->
                    String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00")))));

            for (Map<String, Object> a : dayActs) {
                String type = String.valueOf(a.getOrDefault("type", a.getOrDefault("activity_type", "")));
                String name = String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")));
                LocalTime start = parseTimeSafe(String.valueOf(
                        a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00"))));
                int duration = toIntSafe(a.get(durationKey(a)));
                if (duration <= 0) duration = 60;

                LocalTime fixed = null;
                if ("meal".equals(type)) {
                    if (name.contains("早") || start.isBefore(LocalTime.of(10, 0))) {
                        if (start.isBefore(breakfastStart) || start.isAfter(breakfastEnd)) {
                            fixed = start.isBefore(breakfastStart) ? breakfastStart : breakfastEnd;
                        }
                    } else if (name.contains("午")) {
                        if (start.isBefore(lunchStart) || start.isAfter(lunchEnd)) {
                            fixed = start.isBefore(lunchStart) ? lunchStart : lunchEnd;
                        }
                    } else if (name.contains("晚")) {
                        if (start.isBefore(dinnerStart) || start.isAfter(dinnerEnd)) {
                            fixed = start.isBefore(dinnerStart) ? dinnerStart : dinnerEnd;
                        }
                        if (fixed != null && fixed.plusMinutes(duration).isAfter(dayEnd)) {
                            fixed = dayEnd.minusMinutes(Math.min(duration, 60));
                        }
                    }
                }

                if (fixed != null && !fixed.equals(start)) {
                    applyActivityTime(a, fixed, duration);
                    start = fixed;
                }

                // 每日 21:00 前结束：截断超长活动，剔除 21:00 后才开始的非用餐活动
                if (start.isAfter(dayEnd) || start.equals(dayEnd)) {
                    if (!"meal".equals(type)) {
                        a.put("__drop", Boolean.TRUE);
                        continue;
                    }
                }
                if (start.plusMinutes(duration).isAfter(dayEnd)) {
                    int remain = dayEnd.getHour() * 60 + dayEnd.getMinute()
                            - (start.getHour() * 60 + start.getMinute());
                    if (remain < 15 && !"meal".equals(type)) {
                        a.put("__drop", Boolean.TRUE);
                        continue;
                    }
                    if (remain > 0) {
                        applyActivityTime(a, start, remain);
                    }
                }
            }

            for (Map<String, Object> a : dayActs) {
                if (!Boolean.TRUE.equals(a.remove("__drop"))) {
                    result.add(a);
                }
            }
        }

        result.sort((a, b) -> {
            int d1 = ((Number) a.getOrDefault("day", 1)).intValue();
            int d2 = ((Number) b.getOrDefault("day", 1)).intValue();
            if (d1 != d2) return Integer.compare(d1, d2);
            return String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00")))
                    .compareTo(String.valueOf(b.getOrDefault("startTime", b.getOrDefault("scheduled_start", "08:00"))));
        });
        log.info("人性化时间校准完成: 原 {} 个活动 -> {} 个", activities.size(), result.size());
        return result;
    }

    private String durationKey(Map<String, Object> a) {
        return a.containsKey("durationMin") ? "durationMin" : "duration_min";
    }

    private int toIntSafe(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /** "HH:mm" → 当天分钟数；无法解析返回 -1 */
    private int toMinuteOfDay(Object value) {
        if (value == null) return -1;
        String s = String.valueOf(value).trim();
        if (s.length() < 5) return -1;
        try {
            int h = Integer.parseInt(s.substring(0, 2));
            int m = Integer.parseInt(s.substring(3, 5));
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 分钟数（0-1439）转 HH:mm，越界返回 "-" */
    private String toHhMm(int minute) {
        if (minute < 0 || minute >= 24 * 60) return "-";
        return String.format("%02d:%02d", minute / 60, minute % 60);
    }

    private void applyActivityTime(Map<String, Object> a, LocalTime start, int durationMin) {
        LocalTime end = start.plusMinutes(durationMin);
        String startStr = start.format(DateTimeFormatter.ofPattern("HH:mm"));
        String endStr = end.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (a.containsKey("startTime") || !a.containsKey("scheduled_start")) {
            a.put("startTime", startStr);
            a.put("endTime", endStr);
            a.put("durationMin", durationMin);
        }
        if (a.containsKey("scheduled_start") || a.containsKey("duration_min")) {
            a.put("scheduled_start", startStr);
            a.put("scheduled_end", endStr);
            a.put("duration_min", durationMin);
        }
    }

    private String pickNextPlace(List<Map<String, Object>> cityPool, List<Map<String, Object>> places,
                                 Set<String> usedNames, int idx) {
        return pickNextPlace(cityPool, places, usedNames, idx, null, null, null);
    }

    /** 就近优先：候选中选与 last 坐标距离最小的；无坐标则保持原顺序 */
    private String pickNextPlace(List<Map<String, Object>> cityPool, List<Map<String, Object>> places,
                                 Set<String> usedNames, int idx, String city,
                                 Double lastLat, Double lastLng) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (places != null) {
            for (Map<String, Object> p : places) {
                String n = String.valueOf(p.get("name"));
                if (!usedNames.contains(n) && !"restaurant".equals(String.valueOf(p.getOrDefault("type", "")))) {
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty() && cityPool != null && !cityPool.isEmpty()) {
            for (int i = 0; i < cityPool.size(); i++) {
                Map<String, Object> p = cityPool.get((idx + i) % cityPool.size());
                String n = String.valueOf(p.get("name"));
                if (!usedNames.contains(n)) {
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) return null;

        Map<String, Object> best = candidates.get(0);
        if (lastLat != null && lastLng != null && city != null) {
            double bestDist = Double.MAX_VALUE;
            for (Map<String, Object> p : candidates) {
                String n = String.valueOf(p.get("name"));
                double[] c = resolveCoord(n, city, p);
                double d = c == null ? Double.MAX_VALUE
                        : geoDistance(lastLat, lastLng, c[0], c[1]);
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
        }
        String chosen = String.valueOf(best.get("name"));
        usedNames.add(chosen);
        return chosen;
    }

    private Map<String, Object> newAct(int day, String name, String type, LocalTime start, int durationMin) {
        Map<String, Object> a = new HashMap<>();
        a.put("day", day);
        a.put("name", name);
        a.put("type", type);
        a.put("startTime", start.format(DateTimeFormatter.ofPattern("HH:mm")));
        a.put("endTime", start.plusMinutes(durationMin).format(DateTimeFormatter.ofPattern("HH:mm")));
        a.put("durationMin", durationMin);
        a.put("transportToNext", "walk");
        a.put("travelTimeMin", 15);
        a.put("travelDistanceKm", 1.0);
        a.put("priority", "recommended");
        a.put("notes", name);
        return a;
    }

    private LocalTime parseTimeSafe(String t) {
        try {
            return LocalTime.parse(t);
        } catch (Exception e) {
            return LocalTime.of(18, 0);
        }
    }

    private int calcTotalDays(String timeStart, String timeEnd) {
        try {
            LocalDateTime s = LocalDateTime.parse(timeStart);
            LocalDateTime e = LocalDateTime.parse(timeEnd);
            return (int) Math.max(1,
                    java.time.temporal.ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate()) + 1);
        } catch (Exception ex) {
            return 1;
        }
    }

    /**
     * 为指定天数生成单日行程（LLM 多日补全用）
     */
    private List<Map<String, Object>> planSingleDay(String rawInput,
                                                     List<Map<String, Object>> places,
                                                     List<Map<String, Object>> meals,
                                                     String timeStart, String timeEnd,
                                                     String distanceMatrix,
                                                     int dayNum, int totalDays,
                                                     java.util.Set<String> usedNames,
                                                     TripPace pace) {
        int minActs = pace.getMinDailyActivities();
        int maxActs = Math.min(7, minActs + 2);
        StringBuilder placesInfo = new StringBuilder();
        int idx = 1;
        for (Map<String, Object> p : places) {
            String type = String.valueOf(p.getOrDefault("type", ""));
            if ("restaurant".equalsIgnoreCase(type) || "food".equalsIgnoreCase(type)) continue;
            String name = String.valueOf(p.get("name"));
            if (usedNames.contains(name)) continue;
            placesInfo.append(String.format("%d. %s (类型:%s, 建议游玩:%d分钟)\n",
                    idx++, name, p.get("type"), p.getOrDefault("preferredDurationMin", 120)));
        }
        // 景点用尽或不足时，补充真实 POI（博物馆/商圈/夜市/公园），避免「自由推荐」产出占位名
        if (idx <= 3) {
            String dayCity = extractCity(rawInput);
            try {
                for (Map<String, Object> poi : poiSearchService.searchCityDiverse(dayCity, 8)) {
                    String poiName = String.valueOf(poi.get("name"));
                    if (usedNames.contains(poiName)) continue;
                    placesInfo.append(String.format("%d. %s (类型:%s, 建议游玩:%d分钟)\n",
                            idx++, poiName, poi.get("type"), poi.getOrDefault("preferredDurationMin", 90)));
                    if (idx > 8) break;
                }
            } catch (Exception poiEx) {
                log.debug("planSingleDay POI 补充失败: {}", poiEx.getMessage());
            }
        }
        if (placesInfo.length() == 0) {
            placesInfo.append("1. 当地真实热门景点/历史街区/特色商圈/夜市（必须是具体真实地名）\n");
        }

        StringBuilder mealsInfo = new StringBuilder();
        for (Map<String, Object> m : meals) {
            Object pref = m.get("preference");
            Object rest = m.get("restaurant");
            String restStr = (rest == null || isPlaceholderText(String.valueOf(rest)))
                    ? "" : ", 指定餐厅:" + rest;
            String prefStr = (pref == null || isPlaceholderText(String.valueOf(pref)))
                    ? "" : ", 口味偏好:" + pref;
            mealsInfo.append(String.format("- %s (时长:%d分钟%s%s)%n",
                    m.get("type"), m.getOrDefault("durationMin", 90), prefStr, restStr));
        }

        String prompt = String.format("""
                你是专业旅行规划师。请只生成【第%d天/共%d天】的行程（day=%d）。
                用户需求: %s
                时间范围: %s ~ %s
                可用景点/推荐:
                %s
                餐饮:
                %s
                路线参考:
                %s

                要求:
                1. 只输出 day=%d 的活动，共 %d-%d 个
                2. 时间 08:00-21:00，含午餐晚餐；最后活动 21:00 前结束
                3. 每个活动必须有 day 字段且等于 %d
                4. 用餐符合作息：午餐 11:30-13:00，晚餐 17:30-19:30
                5. 景点不够时用具体真实地名补齐（博物馆、历史街区、特色商圈、夜市、公园、夜景），禁止生成「自由活动」「市区漫步」等占位名（禁止把餐厅作为 visit 景点）
                6. 同一景点、同一餐厅禁止重复出现
                7. 景点顺序优先相邻距离近的，减少折返
                8. 午/晚餐 name 须含"午餐"/"晚餐"；有指定餐厅用「午餐·餐厅名」，有口味偏好用「午餐·口味」
                9. 禁止生成独立的"餐食/美食"栏目，餐食只能是 type=meal 的午餐/晚餐活动
                10. 【活动频率硬约束】本次节奏：%s；游览景点 %d-%d 个、每日游览时长(不含用餐与交通)%s，宁可把间隙留给交通与休息也不要硬塞景点

                输出严格JSON:
                {"activities":[{"day":%d,"name":"...","type":"visit/meal","startTime":"08:00","endTime":"10:00","durationMin":120,"transportToNext":"walk","travelTimeMin":10,"travelDistanceKm":1.0,"priority":"recommended","notes":"..."}]}
                """,
                dayNum, totalDays, dayNum,
                rawInput, timeStart, timeEnd,
                placesInfo, mealsInfo, distanceMatrix,
                dayNum, minActs, maxActs, dayNum,
                pace.promptLine(), pace.getMinVisits(), pace.getMaxVisits(), pace.getHoursLabel(),
                dayNum);

        String response = tripPlanningService.planItinerary(prompt);
        ObjectMapper mapper = new ObjectMapper();
        String cleaned = response.trim()
                .replaceAll("```json\\s*", "").replaceAll("```\\s*", "")
                .replaceAll("^[^{\\[]*", "").replaceAll("[^}\\]]*$", "")
                .trim();
        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (Exception e) {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    root = mapper.readTree(response.substring(start, end + 1));
                } catch (Exception e2) {
                    log.warn("解析第{}天LLM响应失败: {}", dayNum, e2.getMessage());
                    return new ArrayList<>();
                }
            } else {
                log.warn("第{}天LLM响应无JSON: {}", dayNum, e.getMessage());
                return new ArrayList<>();
            }
        }

        List<Map<String, Object>> acts = new ArrayList<>();
        if (root.has("activities")) {
            for (JsonNode act : root.get("activities")) {
                Map<String, Object> a = new HashMap<>();
                a.put("day", dayNum);
                a.put("name", act.has("name") ? act.get("name").asText() : "");
                a.put("type", act.has("type") ? act.get("type").asText() : "visit");
                a.put("startTime", act.has("startTime") ? act.get("startTime").asText() : "");
                a.put("endTime", act.has("endTime") ? act.get("endTime").asText() : "");
                a.put("durationMin", act.has("durationMin") ? act.get("durationMin").asInt() : 60);
                a.put("transportToNext", act.has("transportToNext") ? act.get("transportToNext").asText() : "walk");
                a.put("travelTimeMin", act.has("travelTimeMin") ? act.get("travelTimeMin").asInt() : 10);
                a.put("travelDistanceKm", act.has("travelDistanceKm") ? act.get("travelDistanceKm").asDouble() : 1.0);
                a.put("priority", act.has("priority") ? act.get("priority").asText() : "recommended");
                a.put("notes", act.has("notes") ? act.get("notes").asText() : "");
                acts.add(a);
            }
        }
        return acts;
    }

    /**
     * 本地回退行程生成 - LLM 不可用时基于景点列表生成
     */
    private List<Map<String, Object>> generateLocalItinerary(String rawInput, List<Map<String, Object>> places,
                                                              List<Map<String, Object>> meals,
                                                              String timeStart, String timeEnd) {
        List<Map<String, Object>> activities = new ArrayList<>();
        if (places == null || places.isEmpty()) {
            return activities;
        }

        LocalDateTime start = LocalDateTime.parse(timeStart);
        LocalDateTime end = LocalDateTime.parse(timeEnd);
        int totalDays = (int) Math.ceil((double) java.time.Duration.between(start, end).toHours() / 24);
        totalDays = Math.max(1, totalDays);

        String city = extractCity(rawInput);
        Set<String> usedRestaurants = new HashSet<>();
        Double lastLat = null;
        Double lastLng = null;

        // 将景点分配到各天
        int placesPerDay = (int) Math.ceil((double) places.size() / totalDays);
        int day = 1;
        LocalTime currentTime = LocalTime.of(8, 0);
        boolean hadLunch = false;
        boolean hadDinner = false;

        for (int i = 0; i < places.size(); i++) {
            if (i > 0 && i % placesPerDay == 0) {
                day++;
                currentTime = LocalTime.of(8, 0);
                hadLunch = false;
                hadDinner = false;
            }
            if (day > totalDays) day = totalDays;

            Map<String, Object> place = places.get(i);
            String name = (String) place.get("name");
            String type = (String) place.getOrDefault("type", "scenic");
            int durationMin = (int) place.getOrDefault("preferredDurationMin", 120);

            // 插入午餐（每天一次）
            if (!hadLunch && currentTime.getHour() >= 11 && currentTime.getHour() < 13 && !"meal".equals(type)) {
                Map<String, Object> lunch = new HashMap<>();
                lunch.put("day", day);
                lunch.put("scheduled_start", currentTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                lunch.put("scheduled_end", currentTime.plusMinutes(90).format(DateTimeFormatter.ofPattern("HH:mm")));
                String lunchName = mealDisplayName("lunch", city, mealPreference(meals, "lunch"),
                        lastLat, lastLng, usedRestaurants);
                lunch.put("poi_name", lunchName);
                lunch.put("name", lunchName);
                lunch.put("activity_type", "meal");
                lunch.put("type", "meal");
                lunch.put("duration_min", 90);
                activities.add(lunch);
                currentTime = currentTime.plusMinutes(90);
                hadLunch = true;
            }

            Map<String, Object> act = new HashMap<>();
            act.put("day", day);
            act.put("scheduled_start", currentTime.format(DateTimeFormatter.ofPattern("HH:mm")));
            act.put("scheduled_end", currentTime.plusMinutes(durationMin).format(DateTimeFormatter.ofPattern("HH:mm")));
            act.put("poi_name", name);
            act.put("name", name);
            act.put("activity_type", "meal".equals(type) ? "meal" : "visit");
            act.put("type", "meal".equals(type) ? "meal" : "visit");
            act.put("duration_min", durationMin);
            act.put("transport_mode", "transit");
            activities.add(act);
            if (!"meal".equals(type)) {
                double[] c = resolveCoord(name, city, place);
                if (c != null) {
                    lastLat = c[0];
                    lastLng = c[1];
                }
            }

            currentTime = currentTime.plusMinutes(durationMin + 15); // 15分钟交通时间

            // 插入晚餐（每天一次，17:00 后）
            if (!hadDinner && currentTime.getHour() >= 17 && currentTime.getHour() <= 20) {
                Map<String, Object> dinner = new HashMap<>();
                dinner.put("day", day);
                dinner.put("scheduled_start", currentTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                dinner.put("scheduled_end", currentTime.plusMinutes(60).format(DateTimeFormatter.ofPattern("HH:mm")));
                String dinnerName = mealDisplayName("dinner", city, mealPreference(meals, "dinner"),
                        lastLat, lastLng, usedRestaurants);
                dinner.put("poi_name", dinnerName);
                dinner.put("name", dinnerName);
                dinner.put("activity_type", "meal");
                dinner.put("type", "meal");
                dinner.put("duration_min", 60);
                activities.add(dinner);
                currentTime = currentTime.plusMinutes(60);
                hadDinner = true;
            }
        }

        log.info("本地回退行程生成完成: {} 活动, {} 天", activities.size(), totalDays);
        return activities;
    }

    /**
     * 使用高德API构建真实距离矩阵（多交通方式）
     * 节点 = 景点 + 指定餐厅（餐厅参与两两路段计算）
     * @param places 景点列表（需包含name字段）
     * @param meals 餐次列表（restaurant 字段为指定餐厅名，可选）
     * @param city 城市名称（用于公交规划）
     * @return 格式化的距离矩阵字符串（包含所有交通方式和推荐）
     */
    private String buildRealDistanceMatrix(List<Map<String, Object>> places, List<Map<String, Object>> meals, String city) {
        List<String> placeNames = new ArrayList<>();
        if (places != null) {
            for (Map<String, Object> p : places) {
                String name = String.valueOf(p.getOrDefault("name", "")).trim();
                if (!name.isEmpty() && !placeNames.contains(name)) {
                    placeNames.add(name);
                }
            }
        }
        if (meals != null) {
            for (Map<String, Object> m : meals) {
                Object rest = m.get("restaurant");
                if (rest == null || isPlaceholderText(String.valueOf(rest))) continue;
                String name = String.valueOf(rest).trim();
                if (!name.isEmpty() && !placeNames.contains(name)) {
                    placeNames.add(name);
                }
            }
        }
        if (placeNames.size() < 2) {
            return "景点不足，无法构建距离矩阵";
        }

        // Step 1: 对每个节点（景点+餐厅）并行地理编码获取坐标
        List<CompletableFuture<double[]>> coordFutures = new ArrayList<>(placeNames.size());
        for (String name : placeNames) {
            coordFutures.add(CompletableFuture.supplyAsync(() -> resolveNodeCoord(name, city), planExecutor));
        }
        List<double[]> coordinates = new ArrayList<>(placeNames.size());
        for (CompletableFuture<double[]> f : coordFutures) {
            coordinates.add(f.join());
        }

        // Step 2: 每对节点并行查询三种交通方式（按节点对顺序拼装，保证输出稳定）
        List<CompletableFuture<String>> pairFutures = new ArrayList<>();
        for (int i = 0; i < placeNames.size(); i++) {
            for (int j = i + 1; j < placeNames.size(); j++) {
                if (coordinates.get(i) == null || coordinates.get(j) == null) {
                    continue;
                }
                final double[] from = coordinates.get(i);
                final double[] to = coordinates.get(j);
                final String fromName = placeNames.get(i);
                final String toName = placeNames.get(j);
                pairFutures.add(CompletableFuture.supplyAsync(
                        () -> buildMatrixBlock(fromName, toName, from, to, city), planExecutor));
            }
        }
        StringBuilder matrix = new StringBuilder();
        for (CompletableFuture<String> pf : pairFutures) {
            matrix.append(pf.join());
        }

        String result = matrix.toString();
        if (result.isEmpty()) {
            return "无法获取景点间路线信息，请检查景点名称是否正确";
        }
        return result;
    }

    /**
     * 查询单对节点的步行/公交/驾车路线并格式化为距离矩阵文本块。
     * 作为并行执行单元（planExecutor），返回空串表示无可用路线。
     */
    private String buildMatrixBlock(String fromName, String toName, double[] from, double[] to, String city) {
        double straightDistance = geoDistance(from[1], from[0], to[1], to[0]);
        Map<String, Map<String, Object>> allModes = new HashMap<>();

        try {
            RouteResponse walkRoute = routeService.route(from[1], from[0], to[1], to[0], "walk");
            if (walkRoute != null && walkRoute.isSuccess()) {
                Map<String, Object> walkInfo = new HashMap<>();
                walkInfo.put("time", (int) Math.ceil(walkRoute.getDuration() / 60.0));
                walkInfo.put("dist", walkRoute.getDistance() / 1000.0);
                allModes.put("walk", walkInfo);
            }
        } catch (Exception e) {
            log.debug("步行路线获取失败: {} -> {}", fromName, toName);
        }

        try {
            RouteResponse transitRoute = routeService.route(from[1], from[0], to[1], to[0], "transit", city);
            if (transitRoute != null && transitRoute.isSuccess()) {
                Map<String, Object> transitInfo = new HashMap<>();
                transitInfo.put("time", (int) Math.ceil(transitRoute.getDuration() / 60.0));
                transitInfo.put("dist", transitRoute.getDistance() / 1000.0);
                transitInfo.put("cost", transitRoute.getCost());
                allModes.put("transit", transitInfo);
            }
        } catch (Exception e) {
            log.debug("公交路线获取失败: {} -> {}", fromName, toName);
        }

        try {
            RouteResponse driveRoute = routeService.route(from[1], from[0], to[1], to[0], "drive");
            if (driveRoute != null && driveRoute.isSuccess()) {
                Map<String, Object> driveInfo = new HashMap<>();
                driveInfo.put("time", (int) Math.ceil(driveRoute.getDuration() / 60.0));
                driveInfo.put("dist", driveRoute.getDistance() / 1000.0);
                allModes.put("drive", driveInfo);
            }
        } catch (Exception e) {
            log.debug("驾车路线获取失败: {} -> {}", fromName, toName);
        }

        if (allModes.isEmpty()) {
            return "";
        }

        String recommendation = recommendMode(allModes, straightDistance);
        StringBuilder matrix = new StringBuilder();
        matrix.append(String.format("【%s → %s】(直线距离: %.1fkm)\n",
                fromName, toName, straightDistance / 1000.0));
        if (allModes.containsKey("walk")) {
            Map<String, Object> w = allModes.get("walk");
            matrix.append(String.format("  步行: %.1fkm, %d分钟\n", w.get("dist"), w.get("time")));
        }
        if (allModes.containsKey("transit")) {
            Map<String, Object> t = allModes.get("transit");
            String costStr = t.get("cost") != null ? String.format(", %.0f元", t.get("cost")) : "";
            matrix.append(String.format("  公交/地铁: %.1fkm, %d分钟%s\n", t.get("dist"), t.get("time"), costStr));
        }
        if (allModes.containsKey("drive")) {
            Map<String, Object> d = allModes.get("drive");
            matrix.append(String.format("  驾车: %.1fkm, %d分钟\n", d.get("dist"), d.get("time")));
        }
        matrix.append(String.format("  ★ 推荐: %s\n\n", recommendation));
        return matrix.toString();
    }

    /**
     * 智能推荐交通方式
     */
    private String recommendMode(Map<String, Map<String, Object>> allModes, double straightDistance) {
        // 如果距离很近，推荐步行
        if (straightDistance < 1000 && allModes.containsKey("walk")) {
            int walkTime = (int) allModes.get("walk").get("time");
            if (walkTime <= 15) {
                return "步行（距离近，步行最方便）";
            }
        }

        // 如果距离较近且步行时间可接受
        if (straightDistance < 2000 && allModes.containsKey("walk")) {
            int walkTime = (int) allModes.get("walk").get("time");
            if (walkTime <= 20) {
                return "步行（距离适中，步行可达）";
            }
        }

        // 如果有公交且时间合理
        if (allModes.containsKey("transit")) {
            int transitTime = (int) allModes.get("transit").get("time");
            if (allModes.containsKey("drive")) {
                int driveTime = (int) allModes.get("drive").get("time");
                // 公交时间不超过驾车的1.5倍，推荐公交
                if (transitTime <= driveTime * 1.5) {
                    return "公交/地铁（环保便捷）";
                }
            } else {
                return "公交/地铁（推荐）";
            }
        }

        // 如果有驾车
        if (allModes.containsKey("drive")) {
            return "驾车（距离较远，驾车最省时）";
        }

        // 默认返回可用的方式
        if (allModes.containsKey("transit")) {
            return "公交/地铁";
        }
        if (allModes.containsKey("walk")) {
            return "步行";
        }
        return "未知";
    }

    /**
     * 计算两点间直线距离（米）
     */
    private double geoDistance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 剥离餐饮显示名前缀：「午餐·楼外楼」→「楼外楼」，便于地理编码/坐标匹配
     */
    private String stripMealPrefix(String name) {
        if (name == null) return "";
        String k = name.trim();
        return k.replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
    }

    /** 去掉名称尾部的城市/分店消歧括号，便于地理编码命中：「南京大牌档(杭州)」→「南京大牌档」 */
    private String stripPlaceSuffix(String name) {
        if (name == null) return "";
        return name.trim().replaceFirst("[（(][^（()）]{1,8}[)）]\\s*$", "");
    }

    /** 按城市+名称查内置餐厅坐标，返回 [lng, lat]；找不到返回 null */
    private double[] lookupRestaurantCoord(String city, String name) {
        if (name == null || name.isBlank()) return null;
        String bare = stripMealPrefix(name);
        List<Map<String, Object>> pool = CITY_RESTAURANTS.getOrDefault(city, List.of());
        for (Map<String, Object> r : pool) {
            String rn = String.valueOf(r.getOrDefault("name", ""));
            if (bare.equals(rn) || name.equals(rn)) {
                return new double[]{((Number) r.get("lng")).doubleValue(), ((Number) r.get("lat")).doubleValue()};
            }
        }
        // 跨城兜底：仅当名称权威归属 == 目标城市时放行（归属未知不跨城，防坐标串味）
        String owner = CityOwnershipUtils.ownerCityOfPlace(bare);
        if (owner != null && (city == null || city.isBlank() || owner.equals(city))) {
            for (Map.Entry<String, List<Map<String, Object>>> e : CITY_RESTAURANTS.entrySet()) {
                if (city != null && !city.isBlank() && e.getKey().equals(city)) {
                    continue;
                }
                for (Map<String, Object> r : e.getValue()) {
                    String rn = String.valueOf(r.getOrDefault("name", ""));
                    if (bare.equals(rn)) {
                        return new double[]{((Number) r.get("lng")).doubleValue(), ((Number) r.get("lat")).doubleValue()};
                    }
                }
            }
        }
        return null;
    }

    /**
     * 内置坐标查询：优先 CITY_RESTAURANTS（按城市），再全表按名称匹配。
     * 全表匹配仅在名称权威归属 == 目标城市时放行（归属未知不跨城）。
     * 返回 [lng, lat]（与 geocode 坐标序一致），找不到返回 null。
     */
    private double[] lookupStaticCoord(String name, String city) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim();
        if (city != null && !city.isBlank()) {
            for (Map<String, Object> r : CITY_RESTAURANTS.getOrDefault(city, List.of())) {
                if (key.equals(r.get("name"))) {
                    return new double[]{((Number) r.get("lng")).doubleValue(), ((Number) r.get("lat")).doubleValue()};
                }
            }
        }
        String owner = CityOwnershipUtils.ownerCityOfPlace(key);
        if (owner != null && (city == null || city.isBlank() || owner.equals(city))) {
            for (Map.Entry<String, List<Map<String, Object>>> e : CITY_RESTAURANTS.entrySet()) {
                if (city != null && !city.isBlank() && e.getKey().equals(city)) {
                    continue;
                }
                for (Map<String, Object> r : e.getValue()) {
                    if (key.equals(r.get("name"))) {
                        return new double[]{((Number) r.get("lng")).doubleValue(), ((Number) r.get("lat")).doubleValue()};
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析节点坐标：优先高德地理编码，失败依次回退内置餐厅表、内置静态坐标表。
     * 可在 planExecutor 中并发调用，返回 [lng, lat]，全部失败返回 null。
     */
    private double[] resolveNodeCoord(String name, String city) {
        String geoName = stripMealPrefix(name);
        // 手工校准的内置餐厅坐标优先：名称精确命中时比 API 泛匹配更可靠
        double[] rest = lookupRestaurantCoord(city, geoName);
        if (rest != null) {
            log.info("地理编码命中内置坐标: {} -> ({}, {})", name, rest[0], rest[1]);
            return rest;
        }
        try {
            GeocodeResponse geo = geocodeService.geocode(stripPlaceSuffix(geoName), city, "amap");
            if (geo != null && geo.isSuccess() && geo.getLat() != null && geo.getLng() != null) {
                log.info("地理编码成功: {} -> ({}, {})", name, geo.getLng(), geo.getLat());
                return new double[]{geo.getLng(), geo.getLat()};
            }
        } catch (Exception e) {
            log.debug("地理编码异常: {} - {}", geoName, e.getMessage());
        }
        double[] staticC = lookupStaticCoord(geoName, city);
        if (staticC != null) {
            log.info("地理编码回退静态坐标: {} -> ({}, {})", name, staticC[0], staticC[1]);
            return staticC;
        }
        log.warn("地理编码失败且无内置坐标: {}", name);
        return null;
    }

    /**
     * 用真实路网修正LLM可能编造的路程数据。
     * 对【全部相邻活动对】（含餐饮段）地理编码后调高德路线，强制覆盖
     * travelTimeMin / travelDistanceKm / transportToNext；并回写 lat/lng 便于持久化。
     */
    private List<Map<String, Object>> correctActivitiesWithRealData(
            List<Map<String, Object>> activities, List<Map<String, Object>> places, String city) {
        if (activities == null || activities.size() < 2) {
            return activities;
        }

        // 收集全部活动名称（景点 + 餐饮），去空
        List<String> nodeNames = new ArrayList<>();
        for (Map<String, Object> act : activities) {
            String name = String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", ""))).trim();
            if (!name.isEmpty() && !nodeNames.contains(name)) {
                nodeNames.add(name);
            }
        }

        // 对每个节点并行地理编码（含餐厅），餐次前缀（午餐·/晚餐·）由 resolveNodeCoord 剥离
        Map<String, double[]> coordMap = new HashMap<>();
        // 预置：活动自带坐标（餐段注解静态坐标 / POI 补充坐标）直接复用，省地理编码配额
        for (Map<String, Object> act : activities) {
            String seededName = String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", ""))).trim();
            if (seededName.isEmpty() || coordMap.containsKey(seededName)) {
                continue;
            }
            if (act.get("lat") instanceof Number && act.get("lng") instanceof Number) {
                coordMap.put(seededName, new double[]{
                        ((Number) act.get("lng")).doubleValue(),
                        ((Number) act.get("lat")).doubleValue()});
            }
        }
        Map<String, CompletableFuture<double[]>> coordFutures = new LinkedHashMap<>();
        for (String name : nodeNames) {
            if (coordMap.containsKey(name)) {
                continue;
            }
            coordFutures.put(name, CompletableFuture.supplyAsync(() -> resolveNodeCoord(name, city), planExecutor));
        }
        coordFutures.forEach((name, future) -> {
            double[] c = future.join();
            if (c != null) {
                coordMap.put(name, c);
            }
        });

        // 为每对【实际相邻】活动并行查询真实路线（按活动下标缓存键，避免同名餐厅复用错误）
        Map<Integer, Map<String, Object>> routeByIndex = new HashMap<>();
        log.info("修正行程: 节点数={}, 坐标数={}, 活动数={}", nodeNames.size(), coordMap.size(), activities.size());
        Map<Integer, CompletableFuture<Map<String, Object>>> routeFutures = new LinkedHashMap<>();
        for (int i = 0; i < activities.size() - 1; i++) {
            Map<String, Object> act = activities.get(i);
            Map<String, Object> nextAct = activities.get(i + 1);
            String from = String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", "?"))).trim();
            String to = String.valueOf(nextAct.getOrDefault("name", nextAct.getOrDefault("poi_name", "?"))).trim();
            double[] fromCoord = coordMap.get(from);
            double[] toCoord = coordMap.get(to);

            // 跨天不计算城际/过夜路程
            Object dayA = act.getOrDefault("day", 1);
            Object dayB = nextAct.getOrDefault("day", 1);
            if (dayA != null && dayB != null && ((Number) dayA).intValue() != ((Number) dayB).intValue()) {
                continue;
            }

            if (fromCoord == null || toCoord == null) {
                log.debug("修正行程: 跳过 {}→{}, fromCoord={}, toCoord={}", from, to, fromCoord != null, toCoord != null);
                continue;
            }

            final double[] fromC = fromCoord;
            final double[] toC = toCoord;
            final String fromName = from;
            final String toName = to;
            routeFutures.put(i, CompletableFuture.supplyAsync(() -> {
                Map<String, Object> bestRoute = findBestRoute(fromC, toC, city);
                if (bestRoute != null) {
                    log.info("修正行程: {}→{} = {} {}km {}min", fromName, toName,
                            bestRoute.get("mode"), bestRoute.get("dist"), bestRoute.get("time"));
                } else {
                    log.warn("修正行程: {}→{} 无法获取路线", fromName, toName);
                }
                return bestRoute;
            }, planExecutor));
        }
        routeFutures.forEach((idx, future) -> {
            Map<String, Object> bestRoute = future.join();
            if (bestRoute != null) {
                routeByIndex.put(idx, bestRoute);
            }
        });

        // 回写坐标到活动（便于持久化与后续替换重算）
        for (Map<String, Object> act : activities) {
            String name = String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", ""))).trim();
            double[] c = coordMap.get(name);
            if (c != null) {
                // c = [lng, lat]
                act.put("lng", c[0]);
                act.put("lat", c[1]);
            }
        }

        // 用真实数据覆盖活动的路程信息（每个活动的travelData = 到下一个活动的距离）
        for (int i = 0; i < activities.size(); i++) {
            Map<String, Object> act = activities.get(i);

            if (i == activities.size() - 1) {
                act.put("travelTimeMin", 0);
                act.put("travelDistanceKm", 0.0);
                act.put("transportToNext", "");
                continue;
            }

            Map<String, Object> nextAct = activities.get(i + 1);
            Object dayA = act.getOrDefault("day", 1);
            Object dayB = nextAct.getOrDefault("day", 1);
            if (dayA != null && dayB != null && ((Number) dayA).intValue() != ((Number) dayB).intValue()) {
                act.put("travelTimeMin", 0);
                act.put("travelDistanceKm", 0.0);
                act.put("transportToNext", "");
                continue;
            }

            Map<String, Object> realRoute = routeByIndex.get(i);
            String actName = String.valueOf(act.getOrDefault("name", "?"));
            if (realRoute != null) {
                act.put("transportToNext", realRoute.get("mode"));
                act.put("travelTimeMin", realRoute.get("time"));
                act.put("travelDistanceKm", Math.round((double) realRoute.get("dist") * 10.0) / 10.0);
                log.info("修正[相邻段] {}→next: {}min {}km {}",
                        actName, realRoute.get("time"), realRoute.get("dist"), realRoute.get("mode"));
            } else {
                // 路线失败时保留原值，避免用假数据覆盖
                log.warn("修正[相邻段] {} 无真实路线，保留原 travel 数据", actName);
            }
        }

        // 兜底：同天相邻段不允许出现 0 分钟（LLM 常给餐饮段写 0，坐标缺失/路线失败时会漏到最终结果）
        fillZeroTravelTimes(activities, coordMap);

        // 打印修正后完整行程
        log.info("=== 修正后行程摘要 ===");
        for (int i = 0; i < activities.size(); i++) {
            Map<String, Object> a = activities.get(i);
            String name = (String) a.getOrDefault("name", "?");
            String type = (String) a.getOrDefault("type", "?");
            Object time = a.get("travelTimeMin");
            Object dist = a.get("travelDistanceKm");
            Object mode = a.get("transportToNext");
            log.info("  [{}] {} ({}) → {}min {}km {}", i, name, type, time, dist, mode);
        }

        return activities;
    }

    /**
     * 同天相邻活动的 travelTimeMin 为 0 时兜底补全。
     * <p>
     * 触发场景：LLM 按示例给餐饮段写 0、地理编码失败拿不到坐标、高德路线查询失败。
     * 有坐标 -> 直线距离 × 绕行系数按交通方式估算；无坐标 -> 按交通方式给经验值。
     * 跨天段与当天最后一个活动保持 0（本来就没有下一段路程）。
     *
     * @param activities 活动列表（原地修改）
     * @param coordMap   节点名 -> [lng, lat]
     */
    private void fillZeroTravelTimes(List<Map<String, Object>> activities, Map<String, double[]> coordMap) {
        if (activities == null || activities.size() < 2) {
            return;
        }
        for (int i = 0; i < activities.size() - 1; i++) {
            Map<String, Object> act = activities.get(i);
            Map<String, Object> next = activities.get(i + 1);

            Object dayA = act.getOrDefault("day", 1);
            Object dayB = next.getOrDefault("day", 1);
            if (dayA instanceof Number na && dayB instanceof Number nb && na.intValue() != nb.intValue()) {
                continue;
            }
            if (toIntSafe(act.get("travelTimeMin")) > 0) {
                continue;
            }

            String fromName = nameOfActivity(act);
            String toName = nameOfActivity(next);
            double[] from = coordMap.getOrDefault(fromName, coordOfActivity(act));
            double[] to = coordMap.getOrDefault(toName, coordOfActivity(next));

            double roadKm = -1;
            if (from != null && to != null) {
                // from/to = [lng, lat]
                roadKm = geoDistance(from[1], from[0], to[1], to[0]) / 1000.0 * 1.35;
            }

            String mode = String.valueOf(act.getOrDefault("transportToNext", "")).trim().toLowerCase();
            if (mode.isEmpty()) {
                mode = roadKm >= 2.0 ? "transit" : "walk";
            }

            int minutes = estimateTravelMinutes(roadKm, mode);
            act.put("travelTimeMin", minutes);
            act.put("transportToNext", mode);
            if (roadKm > 0) {
                act.put("travelDistanceKm", Math.round(roadKm * 10.0) / 10.0);
            }
            log.info("补全[相邻段] {}→{}: {}min {}km {}", fromName, toName, minutes,
                    roadKm > 0 ? Math.round(roadKm * 10.0) / 10.0 : "-", mode);
        }
    }

    /**
     * 按道路公里数与交通方式估算分钟数；roadKm &lt; 0（无坐标）时给经验值。
     */
    private int estimateTravelMinutes(double roadKm, String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase();
        double speedKmh;
        if (m.equals("drive") || m.equals("taxi")) {
            speedKmh = 25;
        } else if (m.equals("transit") || m.equals("subway")) {
            speedKmh = 18;
        } else if (m.equals("bike")) {
            speedKmh = 15;
        } else {
            speedKmh = 4.5;
        }
        if (roadKm < 0) {
            // 无坐标兜底：步行 10 分钟、公交/驾车 15 分钟、骑行 8 分钟
            if (m.equals("drive") || m.equals("taxi") || m.equals("transit") || m.equals("subway")) {
                return 15;
            }
            return m.equals("bike") ? 8 : 10;
        }
        int minutes = (int) Math.ceil(Math.max(0.2, roadKm) / speedKmh * 60.0);
        return Math.max(3, Math.min(minutes, 240));
    }

    /** 活动显示名（兼容 name / poi_name） */
    private String nameOfActivity(Map<String, Object> act) {
        return String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", "?"))).trim();
    }

    /** 活动自带坐标 -> [lng, lat]，没有则返回 null */
    private double[] coordOfActivity(Map<String, Object> act) {
        Object latObj = act.get("lat");
        Object lngObj = act.get("lng");
        if (!(latObj instanceof Number latN) || !(lngObj instanceof Number lngN)) {
            return null;
        }
        double lat = latN.doubleValue();
        double lng = lngN.doubleValue();
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || (lat == 0 && lng == 0)) {
            return null;
        }
        return new double[]{lng, lat};
    }

    /**
     * 查找两点间最佳交通方式（综合时间推荐）
     */
    private Map<String, Object> findBestRoute(double[] from, double[] to, String city) {
        Map<String, Object> best = null;
        int bestTime = Integer.MAX_VALUE;

        double straightDist = geoDistance(from[1], from[0], to[1], to[0]);

        // 短距离（<5km）优先检查步行
        if (straightDist < 5000) {
            try {
                RouteResponse walkRoute = routeService.route(from[1], from[0], to[1], to[0], "walk");
                if (walkRoute != null && walkRoute.isSuccess()) {
                    // 严格采用高德路网距离与时长，不做直线估算压缩
                    int apiTime = (int) Math.ceil(walkRoute.getDuration() / 60.0);
                    double apiDist = walkRoute.getDistance() / 1000.0;
                    int finalTime = Math.max(1, apiTime);
                    if (finalTime <= 45) {
                        bestTime = finalTime;
                        best = buildRouteResult("walk", finalTime, Math.round(apiDist * 10.0) / 10.0, "");
                        // 短距离步行（<2km）直接返回步行，不比较驾车
                        if (apiDist < 2.0) {
                            return best;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("修正步行路线异常: {}", e.getMessage());
            }
        }

        // 驾车
        try {
            RouteResponse driveRoute = routeService.route(from[1], from[0], to[1], to[0], "drive");
            if (driveRoute != null && driveRoute.isSuccess()) {
                int time = (int) Math.ceil(driveRoute.getDuration() / 60.0);
                double dist = driveRoute.getDistance() / 1000.0;
                if (time < bestTime) {
                    bestTime = time;
                    best = buildRouteResult("drive", time, dist, "");
                }
            }
        } catch (Exception e) {
            log.debug("修正驾车路线异常: {}", e.getMessage());
        }

        // 公交
        try {
            RouteResponse transitRoute = routeService.route(from[1], from[0], to[1], to[0], "transit", city);
            if (transitRoute != null && transitRoute.isSuccess()) {
                int time = (int) Math.ceil(transitRoute.getDuration() / 60.0);
                double dist = transitRoute.getDistance() / 1000.0;
                // 公交时间不超过驾车1.5倍时优先（更环保）
                if (time <= bestTime * 1.5) {
                    bestTime = time;
                    best = buildRouteResult("transit", time, dist, "");
                }
            }
        } catch (Exception e) {
            log.debug("修正公交路线异常: {}", e.getMessage());
        }

        return best;
    }

    /**
     * 构建带有诊断信息的路线结果Map（可修改）
     */
    private Map<String, Object> buildRouteResult(String mode, int time, double dist, String diag) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("mode", mode);
        result.put("time", time);
        result.put("dist", dist);
        result.put("diag", diag);
        return result;
    }

    private String buildItineraryPrompt(String rawInput, List<Map<String, Object>> places,
                                         List<Map<String, Object>> meals,
                                         String timeStart, String timeEnd,
                                         String distanceMatrix, String city, TripPace pace,
                                         VariantSpec variantSpec) {
        // Build places info
        StringBuilder placesInfo = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            Map<String, Object> p = places.get(i);
            placesInfo.append(String.format("%d. %s (类型:%s, 建议游玩:%d分钟)\n",
                    i + 1, p.get("name"), p.get("type"), p.getOrDefault("preferredDurationMin", 120)));
        }

        // Build meals info（含口味偏好/指定餐厅，不输出独立餐厅景点栏目）
        StringBuilder mealsInfo = new StringBuilder();
        for (Map<String, Object> m : meals) {
            Object pref = m.get("preference");
            Object rest = m.get("restaurant");
            String restStr = (rest == null || isPlaceholderText(String.valueOf(rest)))
                    ? "" : ", 指定餐厅:" + rest;
            String prefStr = (pref == null || isPlaceholderText(String.valueOf(pref)))
                    ? "" : ", 口味偏好:" + pref;
            mealsInfo.append(String.format("- %s (时长:%d分钟%s%s)%n",
                    m.get("type"), m.getOrDefault("durationMin", 90), prefStr, restStr));
        }

        // 计算总天数，明确写入提示词
        int totalDays = 1;
        try {
            java.time.LocalDateTime s = java.time.LocalDateTime.parse(timeStart);
            java.time.LocalDateTime e = java.time.LocalDateTime.parse(timeEnd);
            totalDays = (int) Math.max(1,
                    java.time.temporal.ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate()) + 1);
        } catch (Exception ignored) {}

        String basePrompt = String.format("""
                你是一个专业的旅行行程规划师。根据以下信息生成精确到分钟的完整多日行程，直接输出严格 JSON（{"activities":[...]}），禁止任何其他文字。
                
                === 用户需求 ===
                %s
                
                === 时间范围 ===
                开始: %s，结束: %s，总天数: %d 天
                每天 08:00 开始、最晚 21:00 结束；activities 必须覆盖 day=1 到 day=%d 的每一天，每天至少 %d 个活动（含午餐晚餐）、游览景点 %d-%d 个，活动总数至少 %d 个。

                === 活动频率（用户已选择，硬约束） ===
                %s
                游览时长指景点停留时间合计，不含用餐与交通；宁可少排景点、把间隙留给交通与休息，也不要把一天塞满。

                === 景点列表 ===
                %s
                
                === 目标城市（硬约束） ===
                %s
                禁止输出其他城市的景点；景点不足时只推荐目标城市本地热门景点（餐厅不得作为 type=visit）。
                
                === 餐饮安排 ===
                %s
                每餐 name 必须含「午餐」「晚餐」；有指定餐厅用「午餐·餐厅名」，有口味偏好用「午餐·菜系」。
                
                === 景点间真实路线（高德API数据，必须原样使用） ===
                %s
                travelTimeMin / travelDistanceKm / transportToNext 必须等于上方【★推荐】方式对应的数值，禁止自行估算：推荐"步行"→walk，"公交/地铁"→transit，"驾车"→drive。
                同一天内相邻两个活动之间的 travelTimeMin 必须是大于 0 的分钟数（餐饮段同样要有交通时间）；只有当天最后一个活动才允许 travelTimeMin=0。
                
                === 规划要求 ===
                1. 按时间顺序给出每个活动的 startTime/endTime（HH:mm），相邻活动之间必须有交通时间
                2. 用餐符合作息：早餐 7:30-9:00，午餐 11:30-13:00，晚餐 17:30-19:30 且 21:00 前结束
                3. 游玩时长参考建议时长；优先安排 priority=must；顺序优先相邻距离近的以减少折返
                4. 每个活动含 day 字段，取值 1..%d；同一景点、同一餐厅禁止重复出现（含跨天）
                5. notes 只写 10 字以内的简短说明；景点不够时用真实地名补齐（博物馆/历史街区/商圈/夜市/公园/夜景），禁止生成「自由活动」「市区漫步」等活动名，name 必须是具体真实地点
                
                === 输出格式（严格JSON） ===
                {"activities":[{"day":1,"name":"景点名","type":"visit","startTime":"08:00","endTime":"10:00","durationMin":120,"transportToNext":"walk","travelTimeMin":15,"travelDistanceKm":1.2,"priority":"must","notes":"简要说明"}]}
                
                === 示例：travelTimeMin/travelDistanceKm 严格匹配推荐 ===
                路线：【天安门 → 故宫】步行 1.1km/15分钟（★推荐）；【故宫 → 王府井】公交 3.2km/25分钟（★推荐）
                {"activities":[{"day":1,"name":"天安门广场","type":"visit","startTime":"08:00","endTime":"08:40","durationMin":40,"transportToNext":"walk","travelTimeMin":15,"travelDistanceKm":1.1,"priority":"must","notes":"参观天安门"},{"day":1,"name":"午餐·全聚德","type":"meal","startTime":"12:00","endTime":"13:00","durationMin":60,"transportToNext":"walk","travelTimeMin":12,"travelDistanceKm":0.9,"priority":"must","notes":"北京烤鸭"},{"day":1,"name":"王府井大街","type":"visit","startTime":"13:15","endTime":"15:15","durationMin":120,"transportToNext":"walk","travelTimeMin":8,"travelDistanceKm":0.6,"priority":"recommended","notes":"逛街"}]}
                """, rawInput, timeStart, timeEnd, totalDays, totalDays,
                pace.getMinDailyActivities(), pace.getMinVisits(), pace.getMaxVisits(),
                Math.max(pace.getMinDailyActivities(), totalDays * pace.getMinDailyActivities()),
                pace.promptLine(),
                placesInfo, city == null || city.isBlank() ? "未知（仅可使用上方景点列表）" : city,
                mealsInfo, distanceMatrix, totalDays);

        if (variantSpec == null || !variantSpec.isActive()) {
            return basePrompt;
        }
        List<String> excludePois = variantSpec.getExcludePois();
        // 换版规划：同主题不同内容——排除已用 POI，强制换景点/换餐厅/换顺序
        StringBuilder variantBlock = new StringBuilder("\n\n=== 变体规划（同主题·不同内容，硬约束）===\n")
                .append("本次为换版重规划：目标城市、日期、活动频率与用户需求保持不变，但路线内容必须与上一版明显不同。\n")
                .append("以下地点已在上一版使用，activities 中禁止出现（用户原文明确提到的除外，原文提到的必须保留）：\n");
        int excludeNo = 1;
        for (String excluded : excludePois) {
            if (excludeNo > 60) {
                variantBlock.append("…（其余同类排除项同样生效）\n");
                break;
            }
            variantBlock.append(excludeNo++).append(". ").append(excluded).append('\n');
        }
        // 基准行程摘要：让 LLM 显式避开「相同组合」，而非仅靠排除名单被动规避
        String baseSummary = variantSpec.getBasePlanSummary();
        if (baseSummary != null && !baseSummary.isBlank()) {
            variantBlock.append("上一版每日行程（须整体避开该组合与顺序，不得照抄）：\n")
                    .append(baseSummary.trim()).append('\n');
        }
        variantBlock.append("替代要求：\n")
                .append("1. 景点优先换用排除列表之外、不同区域或不同类型的其他真实地点\n")
                .append("2. 餐厅全部换用排除列表之外的其他真实餐厅\n")
                .append("3. 游览顺序与分组须重新安排，不得照抄常规路线\n")
                .append("4. 系统会在输出后强制校验并替换违反排除列表的景点与餐厅，请直接从合规地点中选择\n");
        return basePrompt + variantBlock;
    }

    /**
     * 换版排除：剔除基准版本已用景点；用户原文明确提到的景点视为主题，保留
     */
    private List<Map<String, Object>> applyVariantExclusions(List<Map<String, Object>> places,
                                                             String rawInput, List<String> excludePois) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : places) {
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (isExcludedName(name, excludePois) && !rawInput.contains(name)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * 换版排除：基准版本用过的餐厅（且非用户原文指定）去掉指定餐厅，交由 LLM 换新餐厅
     */
    private List<Map<String, Object>> applyVariantMealExclusions(List<Map<String, Object>> meals,
                                                                 String rawInput, List<String> excludePois) {
        for (Map<String, Object> m : meals) {
            Object rest = m.get("restaurant");
            if (rest == null) {
                continue;
            }
            String restaurant = String.valueOf(rest).trim();
            if (restaurant.isEmpty() || isPlaceholderText(restaurant)) {
                continue;
            }
            if (isExcludedName(restaurant, excludePois) && !rawInput.contains(restaurant)) {
                m.remove("restaurant");
            }
        }
        return meals;
    }

    /**
     * 换版排除·硬清洗：LLM 或兜底流程可能无视提示词重新引入排除项，
     * 输出前强制替换——景点换成候选池中的合规真实地点，餐段在线换一家合规餐厅（失败则仅保留餐次前缀）。
     * 须在 correctActivitiesWithRealData 之前执行，改名后的行程段会走真实路网重新计算。
     */
    private List<Map<String, Object>> enforceVariantExclusions(List<Map<String, Object>> activities,
                                                               String rawInput, List<String> excludePois,
                                                               String city,
                                                               List<Map<String, Object>> repairPool) {
        if (activities == null || activities.isEmpty()) {
            return activities;
        }
        Set<String> usedNorm = new HashSet<>();
        for (Map<String, Object> a : activities) {
            usedNorm.add(variantNorm(String.valueOf(a.getOrDefault("name", a.getOrDefault("poi_name", "")))));
        }
        int replacedVisits = 0;
        int replacedMeals = 0;
        int unresolved = 0;

        for (Map<String, Object> act : activities) {
            String name = String.valueOf(act.getOrDefault("name", act.getOrDefault("poi_name", "")));
            String norm = variantNorm(name);
            if (norm.isEmpty() || !isExcludedName(name, excludePois) || rawInput.contains(name)) {
                continue;
            }
            String type = String.valueOf(act.getOrDefault("type", act.getOrDefault("activity_type", "visit")));
            if ("meal".equals(type)) {
                String prefix = mealPrefixOf(name);
                if (prefix.isEmpty()) {
                    continue;
                }
                String newName = replaceViolatingMeal(act, prefix, city, excludePois, usedNorm);
                if (newName != null) {
                    usedNorm.add(variantNorm(newName));
                    replacedMeals++;
                    log.info("换版餐段替换: '{}' -> '{}'", name, newName);
                } else {
                    unresolved++;
                }
            } else {
                Map<String, Object> candidate = pickVisitRepair(repairPool, excludePois, usedNorm);
                if (candidate != null) {
                    String newName = String.valueOf(candidate.getOrDefault("name", ""));
                    act.put("name", newName);
                    if (act.containsKey("poi_name")) {
                        act.put("poi_name", newName);
                    }
                    stripStalePoiMeta(act);
                    usedNorm.add(variantNorm(newName));
                    replacedVisits++;
                    log.info("换版景点替换: '{}' -> '{}'", name, newName);
                } else {
                    unresolved++;
                    log.warn("换版排除未能替换（候选池耗尽）: {}", name);
                }
            }
        }
        if (replacedVisits + replacedMeals + unresolved > 0) {
            log.info("换版排除清洗完成: 替换景点{}个, 替换餐段{}个, 未替换{}个", replacedVisits, replacedMeals, unresolved);
        }
        return activities;
    }

    /**
     * 违规餐段换餐厅：先在线检索附近真实餐厅（须合规且未被行程使用），
     * 失败则退回「仅餐次前缀」（如「午餐」），并清掉旧餐厅的坐标/评分残留
     */
    private String replaceViolatingMeal(Map<String, Object> act, String prefix, String city,
                                        List<String> excludePois, Set<String> usedNorm) {
        RestaurantSearchService.RestaurantInfo nearby = null;
        try {
            nearby = restaurantSearchService.searchNearby("美食", city, null, null);
        } catch (Exception e) {
            log.debug("换版餐厅检索失败: {}", e.getMessage());
        }
        if (nearby != null && nearby.name() != null && !nearby.name().isBlank()) {
            String bare = nearby.name().trim();
            if (!isExcludedName(bare, excludePois) && usedNorm.add(variantNorm(bare))) {
                String newName = prefix + "·" + bare;
                act.put("name", newName);
                if (act.containsKey("poi_name")) {
                    act.put("poi_name", newName);
                }
                applyRestaurantInfo(act, nearby, false);
                return newName;
            }
        }
        // 兜底：去掉餐厅名，仅保留餐次前缀
        stripStalePoiMeta(act);
        act.put("name", prefix);
        if (act.containsKey("poi_name")) {
            act.put("poi_name", prefix);
        }
        return prefix;
    }

    /**
     * 换版候选补充：排除后景点不足（低于节奏要求的每日最少游览数）时，
     * 用 PoiSearchService 检索城市多元真实地点补足，且不与已保留景点/排除列表重复。
     * 所有合规候选（含未用于补足的）写入 repairPool，供 LLM 违规输出的强制替换。
     */
    private List<Map<String, Object>> augmentVariantPlaces(List<Map<String, Object>> places, String city,
                                                            String rawInput, List<String> excludePois,
                                                            String timeStart, String timeEnd, TripPace pace,
                                                            List<Map<String, Object>> repairPool) {
        int totalDays = calcTotalDays(timeStart, timeEnd);
        int minNeeded = totalDays * pace.getMinVisits();
        if (city == null || city.isBlank()) {
            return places;
        }
        int fetchLimit = Math.max(totalDays * pace.getMaxVisits(), minNeeded) + 8;
        List<Map<String, Object>> candidates;
        try {
            candidates = poiSearchService.searchCityDiverse(city, fetchLimit);
        } catch (Exception e) {
            log.warn("换版候选POI检索失败: city={}, err={}", city, e.getMessage());
            return places;
        }
        Set<String> used = new HashSet<>();
        for (Map<String, Object> p : places) {
            used.add(variantNorm(String.valueOf(p.getOrDefault("name", ""))));
        }
        List<Map<String, Object>> pool = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String name = String.valueOf(candidate.getOrDefault("name", "")).trim();
            String norm = variantNorm(name);
            if (name.isEmpty() || used.contains(norm)) {
                continue;
            }
            if (isExcludedName(name, excludePois) || rawInput.contains(name)) {
                continue;
            }
            used.add(norm);
            pool.add(candidate);
        }
        repairPool.addAll(pool);

        List<Map<String, Object>> out = new ArrayList<>(places);
        for (Map<String, Object> candidate : pool) {
            if (out.size() >= minNeeded) {
                break;
            }
            out.add(candidate);
        }
        log.info("换版候选补充: {} -> {} 个景点 (目标{}), 备选池剩余{}",
                places.size(), out.size(), minNeeded, pool.size());
        return out;
    }

    /**
     * Agent服务接口
     */
    public interface TripPlanningService {
        @dev.langchain4j.service.SystemMessage("""
            你是一个专业的旅行规划助手。你的任务是根据用户的需求，直接制定详细的旅行行程。
            
            【重要规则】
            - 绝对不要向用户反问任何问题（如"请问您想去哪里"、"预算多少"等）
            - 如果信息不完整，请基于常识做出合理假设并直接给出完整方案
            - 必须一次性输出完整的旅行规划，不要分步骤询问
            
            规划原则：
            - 充分考虑用户的时间、预算、兴趣偏好
            - 合理安排每日行程，避免过于紧凑
            - 考虑交通衔接和住宿安排
            
            请使用中文回复，提供详细的行程规划，必须包含：
            1. 行程概览（目的地、天数、主题）
            2. 每日详细安排（上午、下午、晚上）
            3. 景点推荐（含类型：自然风光/人文古迹/主题乐园等）
            4. 餐厅/美食推荐
            5. 交通方式建议（地铁/公交/打车/自驾等）
            6. 住宿推荐
            7. 预算分配
            8. 注意事项
            """)
        String planTrip(@dev.langchain4j.service.UserMessage String userRequest);

        @dev.langchain4j.service.SystemMessage("你是一个旅行需求解析助手。请严格按照要求的JSON格式输出，不要添加任何多余文字。")
        String parseInput(@dev.langchain4j.service.UserMessage String prompt);

        @dev.langchain4j.service.SystemMessage("你是一个专业的旅行行程规划师。请严格按照要求的JSON格式输出完整行程，不要添加任何多余文字。")
        String planItinerary(@dev.langchain4j.service.UserMessage String prompt);
    }

    // ==================== 本地回退规划辅助方法 ====================

    private static final Map<String, List<Map<String, Object>>> CITY_ATTRACTIONS = new HashMap<>();
    static {
        CITY_ATTRACTIONS.put("北京", List.of(
            Map.of("name", "故宫", "type", "scenic", "duration", 240),
            Map.of("name", "天安门广场", "type", "scenic", "duration", 60),
            Map.of("name", "颐和园", "type", "scenic", "duration", 180),
            Map.of("name", "天坛", "type", "temple", "duration", 120),
            Map.of("name", "南锣鼓巷", "type", "shopping", "duration", 90),
            Map.of("name", "什刹海", "type", "scenic", "duration", 90),
            Map.of("name", "圆明园", "type", "scenic", "duration", 150),
            Map.of("name", "北海公园", "type", "park", "duration", 120),
            Map.of("name", "雍和宫", "type", "temple", "duration", 90),
            Map.of("name", "798艺术区", "type", "museum", "duration", 120),
            Map.of("name", "中国国家博物馆", "type", "museum", "duration", 150),
            Map.of("name", "王府井步行街", "type", "shopping", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("上海", List.of(
            Map.of("name", "外滩", "type", "scenic", "duration", 90),
            Map.of("name", "东方明珠", "type", "scenic", "duration", 120),
            Map.of("name", "豫园", "type", "scenic", "duration", 90),
            Map.of("name", "南京路", "type", "shopping", "duration", 120),
            Map.of("name", "迪士尼", "type", "scenic", "duration", 480),
            Map.of("name", "田子坊", "type", "shopping", "duration", 90),
            Map.of("name", "新天地", "type", "shopping", "duration", 90),
            Map.of("name", "陆家嘴", "type", "scenic", "duration", 60),
            Map.of("name", "上海博物馆", "type", "museum", "duration", 150),
            Map.of("name", "中华艺术宫", "type", "museum", "duration", 120),
            Map.of("name", "静安寺", "type", "temple", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("杭州", List.of(
            Map.of("name", "西湖", "type", "scenic", "duration", 180),
            Map.of("name", "灵隐寺", "type", "temple", "duration", 120),
            Map.of("name", "河坊街", "type", "shopping", "duration", 90),
            Map.of("name", "雷峰塔", "type", "scenic", "duration", 90),
            Map.of("name", "龙井村", "type", "scenic", "duration", 120),
            Map.of("name", "断桥", "type", "scenic", "duration", 30),
            Map.of("name", "西溪湿地", "type", "scenic", "duration", 180),
            Map.of("name", "宋城", "type", "scenic", "duration", 240),
            Map.of("name", "浙江省博物馆", "type", "museum", "duration", 150),
            Map.of("name", "南宋御街", "type", "shopping", "duration", 90),
            Map.of("name", "太子湾公园", "type", "park", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("成都", List.of(
            Map.of("name", "宽窄巷子", "type", "shopping", "duration", 90),
            Map.of("name", "锦里", "type", "shopping", "duration", 90),
            Map.of("name", "武侯祠", "type", "temple", "duration", 120),
            Map.of("name", "大熊猫基地", "type", "scenic", "duration", 180),
            Map.of("name", "杜甫草堂", "type", "temple", "duration", 90),
            Map.of("name", "春熙路", "type", "shopping", "duration", 120),
            Map.of("name", "成都博物馆", "type", "museum", "duration", 150),
            Map.of("name", "人民公园", "type", "park", "duration", 60),
            Map.of("name", "金沙遗址博物馆", "type", "museum", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("西安", List.of(
            Map.of("name", "兵马俑", "type", "museum", "duration", 180),
            Map.of("name", "大雁塔", "type", "temple", "duration", 90),
            Map.of("name", "回民街", "type", "shopping", "duration", 90),
            Map.of("name", "城墙", "type", "scenic", "duration", 120),
            Map.of("name", "陕西历史博物馆", "type", "museum", "duration", 180),
            Map.of("name", "大唐不夜城", "type", "shopping", "duration", 120),
            Map.of("name", "大明宫遗址公园", "type", "park", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("大理", List.of(
            Map.of("name", "大理古城", "type", "shopping", "duration", 150),
            Map.of("name", "洱海", "type", "scenic", "duration", 180),
            Map.of("name", "苍山", "type", "scenic", "duration", 180),
            Map.of("name", "崇圣寺三塔", "type", "temple", "duration", 120),
            Map.of("name", "双廊古镇", "type", "scenic", "duration", 120),
            Map.of("name", "喜洲古镇", "type", "scenic", "duration", 90),
            Map.of("name", "大理白族自治州博物馆", "type", "museum", "duration", 120),
            Map.of("name", "洋人街", "type", "shopping", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("丽江", List.of(
            Map.of("name", "丽江古城", "type", "shopping", "duration", 180),
            Map.of("name", "玉龙雪山", "type", "scenic", "duration", 240),
            Map.of("name", "束河古镇", "type", "scenic", "duration", 120),
            Map.of("name", "泸沽湖", "type", "scenic", "duration", 240),
            Map.of("name", "木府", "type", "scenic", "duration", 90),
            Map.of("name", "东巴文化博物馆", "type", "museum", "duration", 120),
            Map.of("name", "万古楼", "type", "scenic", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("三亚", List.of(
            Map.of("name", "亚龙湾", "type", "scenic", "duration", 180),
            Map.of("name", "天涯海角", "type", "scenic", "duration", 120),
            Map.of("name", "南山文化旅游区", "type", "temple", "duration", 180),
            Map.of("name", "鹿回头公园", "type", "park", "duration", 90),
            Map.of("name", "第一市场", "type", "shopping", "duration", 90),
            Map.of("name", "三亚千古情", "type", "museum", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("厦门", List.of(
            Map.of("name", "鼓浪屿", "type", "scenic", "duration", 240),
            Map.of("name", "厦门大学", "type", "scenic", "duration", 90),
            Map.of("name", "南普陀寺", "type", "temple", "duration", 90),
            Map.of("name", "曾厝垵", "type", "shopping", "duration", 90),
            Map.of("name", "中山路步行街", "type", "shopping", "duration", 90),
            Map.of("name", "厦门博物馆", "type", "museum", "duration", 120),
            Map.of("name", "环岛路", "type", "scenic", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("重庆", List.of(
            Map.of("name", "洪崖洞", "type", "scenic", "duration", 90),
            Map.of("name", "解放碑", "type", "shopping", "duration", 90),
            Map.of("name", "磁器口古镇", "type", "scenic", "duration", 120),
            Map.of("name", "长江索道", "type", "scenic", "duration", 60),
            Map.of("name", "三峡博物馆", "type", "museum", "duration", 120),
            Map.of("name", "观音桥步行街", "type", "shopping", "duration", 90),
            Map.of("name", "李子坝观景平台", "type", "scenic", "duration", 30)
        ));
        CITY_ATTRACTIONS.put("苏州", List.of(
            Map.of("name", "拙政园", "type", "scenic", "duration", 120),
            Map.of("name", "留园", "type", "scenic", "duration", 90),
            Map.of("name", "虎丘", "type", "scenic", "duration", 90),
            Map.of("name", "平江路", "type", "shopping", "duration", 90),
            Map.of("name", "山塘街", "type", "shopping", "duration", 90),
            Map.of("name", "苏州博物馆", "type", "museum", "duration", 120),
            Map.of("name", "寒山寺", "type", "temple", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("南京", List.of(
            Map.of("name", "中山陵", "type", "scenic", "duration", 120),
            Map.of("name", "明孝陵", "type", "scenic", "duration", 120),
            Map.of("name", "夫子庙", "type", "shopping", "duration", 90),
            Map.of("name", "南京博物院", "type", "museum", "duration", 180),
            Map.of("name", "鸡鸣寺", "type", "temple", "duration", 60),
            Map.of("name", "玄武湖公园", "type", "park", "duration", 90),
            Map.of("name", "新街口", "type", "shopping", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("广州", List.of(
            Map.of("name", "广州塔", "type", "scenic", "duration", 120),
            Map.of("name", "沙面", "type", "scenic", "duration", 90),
            Map.of("name", "陈家祠", "type", "museum", "duration", 90),
            Map.of("name", "上下九步行街", "type", "shopping", "duration", 90),
            Map.of("name", "越秀公园", "type", "park", "duration", 90),
            Map.of("name", "广东省博物馆", "type", "museum", "duration", 120),
            Map.of("name", "珠江夜游", "type", "scenic", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("长沙", List.of(
            Map.of("name", "岳麓山", "type", "scenic", "duration", 150),
            Map.of("name", "橘子洲", "type", "scenic", "duration", 120),
            Map.of("name", "太平街", "type", "shopping", "duration", 90),
            Map.of("name", "岳麓书院", "type", "museum", "duration", 90),
            Map.of("name", "湖南博物院", "type", "museum", "duration", 180),
            Map.of("name", "五一广场", "type", "shopping", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("武汉", List.of(
            Map.of("name", "黄鹤楼", "type", "scenic", "duration", 120),
            Map.of("name", "东湖绿道", "type", "park", "duration", 150),
            Map.of("name", "武汉长江大桥", "type", "scenic", "duration", 60),
            Map.of("name", "湖北省博物馆", "type", "museum", "duration", 180),
            Map.of("name", "户部巷", "type", "shopping", "duration", 60),
            Map.of("name", "昙华林", "type", "scenic", "duration", 90),
            Map.of("name", "江汉路步行街", "type", "shopping", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("哈尔滨", List.of(
            Map.of("name", "中央大街", "type", "shopping", "duration", 120),
            Map.of("name", "圣索菲亚教堂", "type", "scenic", "duration", 60),
            Map.of("name", "松花江畔", "type", "scenic", "duration", 60),
            Map.of("name", "太阳岛", "type", "park", "duration", 120),
            Map.of("name", "哈尔滨极地馆", "type", "museum", "duration", 150),
            Map.of("name", "老道外中华巴洛克", "type", "scenic", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("青岛", List.of(
            Map.of("name", "栈桥", "type", "scenic", "duration", 60),
            Map.of("name", "八大关", "type", "scenic", "duration", 120),
            Map.of("name", "青岛啤酒博物馆", "type", "museum", "duration", 90),
            Map.of("name", "五四广场", "type", "scenic", "duration", 60),
            Map.of("name", "天主教堂", "type", "temple", "duration", 45),
            Map.of("name", "台东步行街", "type", "shopping", "duration", 90),
            Map.of("name", "中山公园", "type", "park", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("昆明", List.of(
            Map.of("name", "石林", "type", "scenic", "duration", 180),
            Map.of("name", "滇池", "type", "scenic", "duration", 120),
            Map.of("name", "翠湖公园", "type", "park", "duration", 60),
            Map.of("name", "云南民族村", "type", "scenic", "duration", 150),
            Map.of("name", "云南省博物馆", "type", "museum", "duration", 150),
            Map.of("name", "斗南花市", "type", "shopping", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("洛阳", List.of(
            Map.of("name", "龙门石窟", "type", "scenic", "duration", 180),
            Map.of("name", "白马寺", "type", "temple", "duration", 90),
            Map.of("name", "洛阳博物馆", "type", "museum", "duration", 150),
            Map.of("name", "丽景门", "type", "scenic", "duration", 60),
            Map.of("name", "老城十字街夜市", "type", "shopping", "duration", 90),
            Map.of("name", "隋唐城遗址植物园", "type", "park", "duration", 90)
        ));
    }

    private List<String> extractPlaces(String rawInput) {
        List<String> places = new ArrayList<>();
        String[] parts = rawInput.split("[「」《》\"']");
        for (int i = 1; i < parts.length; i += 2) {
            if (!parts[i].isBlank()) {
                places.add(parts[i].trim());
            }
        }
        return places;
    }

    private List<Map<String, Object>> generateActivities(String city, List<Map<String, Object>> attractions, List<String> mentionedPlaces, int totalDays) {
        return generateActivities(city, attractions, mentionedPlaces, totalDays, List.of());
    }

    private List<Map<String, Object>> generateActivities(String city, List<Map<String, Object>> attractions,
                                                         List<String> mentionedPlaces, int totalDays,
                                                         List<Map<String, Object>> meals) {
        List<Map<String, Object>> allActivities = new ArrayList<>();
        int seq = 1;

        List<Map<String, Object>> sortedAttractions = dedupePlaces(new ArrayList<>(attractions));
        sortedAttractions = sortPlacesByProximity(sortedAttractions, city, mentionedPlaces);
        if (!mentionedPlaces.isEmpty()) {
            sortedAttractions.sort((a, b) -> {
                String nameA = (String) a.get("name");
                String nameB = (String) b.get("name");
                boolean mentionedA = mentionedPlaces.stream().anyMatch(p -> nameA.contains(p));
                boolean mentionedB = mentionedPlaces.stream().anyMatch(p -> nameB.contains(p));
                return Boolean.compare(mentionedB, mentionedA);
            });
        }

        int attractionsPerDay = Math.max(3, Math.min(5, sortedAttractions.size() / Math.max(1, totalDays)));
        int attractionIdx = 0;
        Set<String> usedRestaurants = new HashSet<>();
        Double lastLat = null;
        Double lastLng = null;

        for (int day = 1; day <= totalDays; day++) {
            boolean hadLunch = false;
            Map<String, Object> transit = new LinkedHashMap<>();
            transit.put("id", "act" + seq++);
            transit.put("seq", seq - 1);
            transit.put("poi_name", (city == null || city.isBlank() ? "当地" : city) + "站/机场");
            transit.put("activity_type", "transit");
            transit.put("priority", "must");
            transit.put("duration_min", 30);
            transit.put("scheduled_start", "08:00");
            transit.put("scheduled_end", "08:30");
            transit.put("day", day);
            transit.put("status", "scheduled");
            allActivities.add(transit);

            LocalTime currentTime = LocalTime.of(9, 0);

            for (int i = 0; i < attractionsPerDay && attractionIdx < sortedAttractions.size(); i++) {
                Map<String, Object> attraction = sortedAttractions.get(attractionIdx++);
                int duration = (int) attraction.getOrDefault("duration", 90);
                String type = (String) attraction.getOrDefault("type", "scenic");

                String activityType = switch (type) {
                    case "temple" -> "visit";
                    case "shopping" -> "shopping";
                    case "museum" -> "visit";
                    default -> "visit";
                };

                if (!currentTime.equals(LocalTime.of(9, 0))) {
                    currentTime = currentTime.plusMinutes(30);
                }

                LocalTime startTime = currentTime;
                LocalTime endTime = startTime.plusMinutes(duration);
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");

                Map<String, Object> activity = new LinkedHashMap<>();
                activity.put("id", "act" + seq++);
                activity.put("seq", seq - 1);
                activity.put("poi_name", attraction.get("name"));
                activity.put("activity_type", activityType);
                activity.put("priority", "must");
                activity.put("duration_min", duration);
                activity.put("scheduled_start", startTime.format(fmt));
                activity.put("scheduled_end", endTime.format(fmt));
                activity.put("travel_duration_min", 15);
                activity.put("day", day);
                activity.put("status", "scheduled");
                allActivities.add(activity);

                double[] c = resolveCoord(String.valueOf(attraction.get("name")), city, attraction);
                if (c != null) {
                    lastLat = c[0];
                    lastLng = c[1];
                }

                currentTime = endTime;

                if (!hadLunch && currentTime.isAfter(LocalTime.of(11, 30)) && currentTime.isBefore(LocalTime.of(13, 0))) {
                    Map<String, Object> lunch = new LinkedHashMap<>();
                    lunch.put("id", "act" + seq++);
                    lunch.put("seq", seq - 1);
                    lunch.put("poi_name", mealDisplayName("lunch", city, mealPreference(meals, "lunch"),
                            lastLat, lastLng, usedRestaurants));
                    lunch.put("activity_type", "meal");
                    lunch.put("priority", "must");
                    lunch.put("duration_min", 60);
                    lunch.put("scheduled_start", currentTime.format(fmt));
                    lunch.put("scheduled_end", currentTime.plusMinutes(60).format(fmt));
                    lunch.put("travel_duration_min", 10);
                    lunch.put("day", day);
                    lunch.put("status", "scheduled");
                    allActivities.add(lunch);
                    currentTime = currentTime.plusMinutes(60);
                    hadLunch = true;
                }
            }

            if (currentTime.isAfter(LocalTime.of(17, 0))) {
                Map<String, Object> dinner = new LinkedHashMap<>();
                dinner.put("id", "act" + seq++);
                dinner.put("seq", seq - 1);
                dinner.put("poi_name", mealDisplayName("dinner", city, mealPreference(meals, "dinner"),
                        lastLat, lastLng, usedRestaurants));
                dinner.put("activity_type", "meal");
                dinner.put("priority", "must");
                dinner.put("duration_min", 60);
                dinner.put("scheduled_start", "18:00");
                dinner.put("scheduled_end", "19:00");
                dinner.put("travel_duration_min", 10);
                dinner.put("day", day);
                dinner.put("status", "scheduled");
                allActivities.add(dinner);
            }
        }

        return allActivities;
    }

    private List<Map<String, Object>> generateRoutes(List<Map<String, Object>> activities) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < activities.size() - 1; i++) {
            Map<String, Object> from = activities.get(i);
            Map<String, Object> to = activities.get(i + 1);

            Map<String, Object> route = new LinkedHashMap<>();
            route.put("from", from.get("poi_name"));
            route.put("to", to.get("poi_name"));
            route.put("distance_km", 2.0 + Math.random() * 5);
            route.put("duration_min", (int) from.getOrDefault("travel_duration_min", 15));
            route.put("mode", "transit");
            routes.add(route);
        }
        return routes;
    }
}
