package com.tripplanner.plan.service;

import com.tripplanner.plan.config.MapApiConfig;
import com.tripplanner.plan.dto.response.GeocodeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * POI 文本搜索服务
 *
 * 基于高德 POI 文本搜索接口，按城市并行检索多元真实地点（博物馆、历史街区、
 * 特色商圈、夜市、公园、文化场馆等），用于替代「自由活动/市区漫步」类占位。
 * 结果按城市内存缓存，超时降级返回空列表，不影响主流程。
 *
 * @author TripForge Team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoiSearchService {

    private final MapApiConfig mapApiConfig;
    private final WebClient amapWebClient;
    private final GeocodeService geocodeService;

    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_RESULTS_PER_QUERY = 10;
    private static final int MAX_CACHE_SIZE = 200;
    private static final double MAX_DISTANCE_KM = 20.0;

    /** 每城市查询计划：目标类型 + 关键词（逗号多关键词非 OR，故按类别分次查询） */
    private record QueryPlan(String type, String keyword) {
    }

    private static final List<QueryPlan> QUERY_PLAN = List.of(
            new QueryPlan("museum", "博物馆"),
            new QueryPlan("museum", "纪念馆"),
            new QueryPlan("shopping", "步行街"),
            new QueryPlan("shopping", "夜市"),
            new QueryPlan("park", "公园"),
            new QueryPlan("scenic", "历史街区"),
            new QueryPlan("other", "文化馆"),
            new QueryPlan("other", "美术馆"),
            // 缺口填充候选扩容：热门景区/购物中心/特色风景（用户可接受的通用推荐类型）
            new QueryPlan("scenic", "风景区"),
            new QueryPlan("scenic", "旅游景点"),
            new QueryPlan("shopping", "购物中心"),
            new QueryPlan("shopping", "广场"),
            new QueryPlan("scenic", "古镇"),
            new QueryPlan("scenic", "古街"),
            new QueryPlan("other", "观景台"),
            new QueryPlan("park", "湿地")
    );

    /** 需要排除的 POI 大类（住宿、地产、政务、医疗、地名等） */
    private static final List<String> EXCLUDE_TYPES = List.of(
            "住宿服务", "房地产", "政府机构", "医疗", "地名地址", "金融", "公司企业", "通行设施");

    /**
     * 搜索路排除大类：较 EXCLUDE_TYPES 豁免「地名地址」——
     * 「西湖」等自然地名（地名地址;自然地名;湖泊）是有效搜索目标；
     * 道路/交通类地名噪声由 {@link #toSearchItem} 子类规则与模糊得分双重过滤
     */
    private static final List<String> SEARCH_EXCLUDE_TYPES = List.of(
            "住宿服务", "房地产", "政府机构", "医疗", "金融", "公司企业", "通行设施");

    /** 按城市缓存：城市 -> 检索结果 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final ExecutorService poiExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "poi-search-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    /**
     * 检索某城市多元真实地点
     *
     * @param city  城市名（如「大理」）
     * @param limit 返回条数上限
     * @return POI 列表，每项含 name/type/lat/lng/preferredDurationMin，失败返回空列表
     */
    public List<Map<String, Object>> searchCityDiverse(String city, int limit) {
        if (city == null || city.isBlank()) {
            return List.of();
        }
        String cityKey = city.trim();
        List<Map<String, Object>> cached = getFromCache(cityKey);
        if (cached != null) {
            return limit(cached, limit);
        }

        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
        for (QueryPlan plan : QUERY_PLAN) {
            String type = plan.type();
            String keyword = plan.keyword();
            futures.add(CompletableFuture.supplyAsync(
                    () -> searchOne(cityKey, keyword, type), poiExecutor));
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        int ok = 0;
        for (CompletableFuture<List<Map<String, Object>>> future : futures) {
            try {
                List<Map<String, Object>> part = future.get(QUERY_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
                if (part != null) {
                    ok++;
                    for (Map<String, Object> poi : part) {
                        String name = String.valueOf(poi.get("name"));
                        if (seenNames.add(normalizeName(name))) {
                            merged.add(poi);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("POI 查询超时/失败: {}", e.getMessage());
            }
        }

        if (ok == 0) {
            log.info("POI 搜索无可用结果: city={}", cityKey);
            return List.of();
        }
        // citylimit 会返回州内县郊 POI（如大理返回宾川/巍山），按城市中心距离过滤
        merged = filterByDistance(merged, cityKey);
        putToCache(cityKey, merged);
        log.info("POI 搜索完成: city={}, 关键词命中 {}/{}, 合并后 {} 条",
                cityKey, ok, QUERY_PLAN.size(), merged.size());
        return limit(merged, limit);
    }

    /**
     * 按用户自由关键词检索 POI（换景点搜索框，支持模糊搜索）
     *
     * 多路召回 + 本地模糊得分重排：
     * 1. 城市限定原词路（同城优先）；
     * 2. 全国原词路（错别字/外地著名地点兜底，如搜「寺」出「红螺寺」）；
     * 3. 单字切片路（仅城市限域，词长≥2 且前两路召回不足时补发）——
     *    用首字/尾字等切片词查高德，再按编辑距离与包含关系本地匹配，
     *    覆盖错别字（「西胡」→「西湖」lev=1）与字数不全场景。
     *
     * 结果按 {@link #fuzzyScore} 降序（同分城市路优先、高德原始排序次之），
     * 得分为 0 的候选丢弃，保证每条结果与查询词相关。
     *
     * @param keyword 用户输入关键词
     * @param city    限定城市（可空，空则全国范围关键词搜索）
     * @param limit   返回条数上限（1-10）
     * @return POI 列表，每项含 name/type/lat/lng/address/rating/preferredDurationMin/source/matchType/fuzzyScore
     */
    public List<Map<String, Object>> searchByKeyword(String keyword, String city, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = normalizeName(keyword);
        int lim = Math.max(1, Math.min(limit <= 0 ? 6 : limit, 10));
        boolean hasCity = city != null && !city.isBlank();
        String cityOrNull = hasCity ? city.trim() : null;

        // ---- 第一波：原词（城市路 + 全国路）并行 ----
        Map<String, Merged> merged = new HashMap<>();
        CompletableFuture<List<Map<String, Object>>> cityRoad =
                CompletableFuture.supplyAsync(() -> queryLoose(kw, cityOrNull), poiExecutor);
        mergeFuture(merged, cityRoad, true, kw, 0);
        // 全国原词路：单字查询高德全国返回 count=0，不空耗请求
        if (hasCity && kw.length() >= 2) {
            CompletableFuture<List<Map<String, Object>>> nationalRoad =
                    CompletableFuture.supplyAsync(() -> queryLoose(kw, null), poiExecutor);
            mergeFuture(merged, nationalRoad, false, kw, 0);
        }

        // ---- 第二波：召回不足时补发单字切片路（仅城市限域，最多 4 路）----
        if (hasCity && kw.length() >= 2 && countPositive(merged) < lim) {
            List<CompletableFuture<List<Map<String, Object>>>> wave2 = new ArrayList<>();
            for (int idx : sliceIndexes(kw)) {
                String slice = String.valueOf(kw.charAt(idx));
                wave2.add(CompletableFuture.supplyAsync(() -> queryLoose(slice, cityOrNull), poiExecutor));
            }
            for (CompletableFuture<List<Map<String, Object>>> f : wave2) {
                // 字路候选同分时排原词路之后（rank 偏移），避免切片噪声抢占头部
                mergeFuture(merged, f, true, kw, 10000);
            }
        }

        List<Merged> sorted = new ArrayList<>(merged.values());
        sorted.sort((a, b) -> {
            int s = Integer.compare(b.score, a.score);
            if (s != 0) return s;
            int c = Integer.compare((b.cityRoad ? 1 : 0), (a.cityRoad ? 1 : 0));
            if (c != 0) return c;
            return Integer.compare(a.rawRank, b.rawRank);
        });
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < lim; i++) {
            top.add(sorted.get(i).item());
        }
        log.info("POI 模糊搜索完成: keyword={}, city={}, 候选 {} -> 返回 {} 条",
                keyword, city, sorted.size(), top.size());
        return top;
    }

    /** 等待单路完成并合并（失败/超时按空结果处理，不影响其他路） */
    private void mergeFuture(Map<String, Merged> merged,
                             CompletableFuture<List<Map<String, Object>>> future,
                             boolean cityRoad, String kw, int rankBase) {
        try {
            List<Map<String, Object>> part = future.get(QUERY_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (part != null) {
                mergeResults(merged, part, cityRoad, kw, rankBase);
            }
        } catch (Exception e) {
            log.debug("POI 模糊搜索分支超时/失败: kw={}, {}", kw, e.getMessage());
        }
    }

    /** 多路合并：按规范化名称去重，保留更高得分（同分保留城市路） */
    private void mergeResults(Map<String, Merged> merged, List<Map<String, Object>> items,
                              boolean cityRoad, String kw, int rankBase) {
        int rank = rankBase;
        for (Map<String, Object> item : items) {
            String name = normalizeName(String.valueOf(item.get("name")));
            int score = fuzzyScore(name, kw);
            int rawRank = rank++;
            if (score <= 0) {
                continue;
            }
            Merged exist = merged.get(name);
            if (exist == null || score > exist.score
                    || (score == exist.score && cityRoad && !exist.cityRoad)) {
                item.put("fuzzyScore", score);
                item.put("matchType", matchTypeOf(score));
                merged.put(name, new Merged(item, score, cityRoad, rawRank));
            }
        }
    }

    private int countPositive(Map<String, Merged> merged) {
        int n = 0;
        for (Merged m : merged.values()) {
            if (m.score > 0) n++;
        }
        return n;
    }

    /** 单字切片索引：首、尾、及中间均匀取，最多 4 路（错别字/字数不全召回） */
    private int[] sliceIndexes(String kw) {
        int n = kw.length();
        LinkedHashSet<Integer> idxs = new LinkedHashSet<>();
        idxs.add(0);
        if (n > 1) idxs.add(n - 1);
        if (n > 2) idxs.add(n / 2);
        if (n > 3) idxs.add(n / 3);
        return idxs.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 模糊匹配得分（0 = 不相关，丢弃）
     *
     * 方向以规范化查询词为基准匹配候选名：精确 100 → 双向前缀 85 →
     * 查询⊆候选 75（「寺」→红螺寺）→ 候选⊆查询 65（字数/多字场景）→
     * 滑窗编辑距离（候选的连续子串与查询 lev=1 → 70，lev=2 且查询 ≥3 字 → 55，
     * 覆盖错别字命中长名：「西胡」→「杭州西湖风景名胜区」含「西湖」窗口）→
     * 整串编辑距离 1 = 70、距离 2 且双方 ≥4 字 = 55。
     * 单字查询不启用编辑距离（任意形近字会造成大量误配）。
     */
    static int fuzzyScore(String candidate, String keyword) {
        String c = normalizeName(candidate);
        String k = normalizeName(keyword);
        if (c.isEmpty() || k.isEmpty()) {
            return 0;
        }
        if (c.equals(k)) {
            return 100;
        }
        if (c.startsWith(k) || k.startsWith(c)) {
            return 85;
        }
        if (c.contains(k)) {
            return 75;
        }
        if (k.contains(c) && c.length() >= 2) {
            return 65;
        }
        if (k.length() >= 2) {
            int window = k.length();
            if (window >= 2 && c.length() <= 40) {
                int best = Integer.MAX_VALUE;
                for (int i = 0; i + window <= c.length() && best > 1; i++) {
                    best = Math.min(best, levenshtein(c.substring(i, i + window), k));
                }
                if (best == 1) {
                    return 70;
                }
                if (best == 2 && window >= 3) {
                    return 55;
                }
            }
            int d = levenshtein(c, k);
            if (d == 1) {
                return 70;
            }
            if (d == 2 && Math.min(c.length(), k.length()) >= 4) {
                return 55;
            }
        }
        return 0;
    }

    /** 得分 -> 展示标签 */
    static String matchTypeOf(int score) {
        if (score >= 100) return "精确";
        if (score >= 85) return "前缀";
        if (score >= 75) return "包含";
        if (score >= 70) return "模糊匹配";
        return "近似匹配";
    }

    /** 标准 Levenshtein 编辑距离（字符级） */
    static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[n];
    }

    /** 单次宽松 place/text 查询并解析为搜索项（不含打分） */
    private List<Map<String, Object>> queryLoose(String keyword, String city) {
        StringBuilder url = new StringBuilder(mapApiConfig.getAmapPlaceUrl()
                + "?key=" + mapApiConfig.getAmapApiKey()
                + "&keywords=" + encode(keyword)
                + "&offset=10&page=1");
        if (city != null && !city.isBlank()) {
            url.append("&city=").append(encode(city)).append("&citylimit=true");
        }
        try {
            Map<String, Object> response = amapWebClient.get()
                    .uri(URI.create(url.toString()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(4));
            if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
                log.debug("POI 查询返回异常: keyword={}, city={}, info={}",
                        keyword, city, response == null ? "null" : response.get("info"));
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
            if (pois == null || pois.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> poi : pois) {
                Map<String, Object> item = toSearchItem(poi);
                if (item != null) {
                    out.add(item);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("POI 查询失败: keyword={}, err={}", keyword, e.getMessage());
            return List.of();
        }
    }

    /** 合并用的候选记录 */
    private record Merged(Map<String, Object> item, int score, boolean cityRoad, int rawRank) {
    }

    /** 高德 POI -> 搜索结果项（放宽版 toPlace：类型兜底 attraction，保留评分与地址） */
    private Map<String, Object> toSearchItem(Map<String, Object> poi) {
        String name = String.valueOf(poi.getOrDefault("name", "")).trim();
        if (name.length() < 2 || name.length() > 30 || isNoiseName(name)) {
            return null;
        }
        String rawType = String.valueOf(poi.getOrDefault("type", ""));
        for (String exclude : SEARCH_EXCLUDE_TYPES) {
            if (rawType.contains(exclude)) {
                return null;
            }
        }
        // 地名地址大类仅保留自然地名（湖泊/山峰等），剔除道路/交通站点噪声
        if (rawType.contains("地名地址")
                && (rawType.contains("道路") || rawType.contains("交通地名")
                    || rawType.contains("车站") || rawType.contains("公交"))) {
            return null;
        }
        String location = String.valueOf(poi.getOrDefault("location", ""));
        if (!location.contains(",")) {
            return null;
        }
        double lat;
        double lng;
        try {
            String[] coords = location.split(",");
            lng = Double.parseDouble(coords[0].trim());
            lat = Double.parseDouble(coords[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String mappedType = mapType(rawType, name, null);
        if (mappedType == null) {
            mappedType = "attraction";
        }
        Map<String, Object> item = new HashMap<>();
        item.put("name", name);
        item.put("type", mappedType);
        item.put("lat", lat);
        item.put("lng", lng);
        item.put("address", String.valueOf(poi.getOrDefault("address", "")));
        item.put("preferredDurationMin", durationOf(mappedType));
        item.put("source", "amap");
        Object rating = poi.get("rating");
        if (rating != null) {
            try {
                item.put("rating", Double.parseDouble(String.valueOf(rating)));
            } catch (NumberFormatException ignored) {
                // 高德评分缺失/非数值时省略
            }
        }
        return item;
    }

    /**
     * 按关键词检索单次 POI（含类型映射与过滤）
     */
    private List<Map<String, Object>> searchOne(String city, String keyword, String type) {
        String url = mapApiConfig.getAmapPlaceUrl()
                + "?key=" + mapApiConfig.getAmapApiKey()
                + "&keywords=" + encode(keyword)
                + "&city=" + encode(city)
                + "&citylimit=true"
                + "&offset=" + MAX_RESULTS_PER_QUERY
                + "&page=1";
        try {
            Map<String, Object> response = amapWebClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(QUERY_TIMEOUT);
            if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
                log.debug("POI 搜索接口返回异常: city={}, keyword={}, info={}",
                        city, keyword, response == null ? "null" : response.get("info"));
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
            if (pois == null || pois.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> poi : pois) {
                Map<String, Object> item = toPlace(poi, type);
                if (item != null) {
                    out.add(item);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("POI 搜索请求失败: city={}, keyword={}, err={}", city, keyword, e.getMessage());
            return List.of();
        }
    }

    /**
     * POI 原始记录 -> 地点 Map（含类型映射/坐标/时长）
     *
     * @return 过滤后地点；不合规返回 null
     */
    private Map<String, Object> toPlace(Map<String, Object> poi, String fallbackType) {
        String name = String.valueOf(poi.getOrDefault("name", "")).trim();
        String rawType = String.valueOf(poi.getOrDefault("type", ""));
        // 过短/超长（含冗余地址串）的名称不适合做行程节点
        if (name.length() < 2 || name.length() > 20) {
            return null;
        }
        for (String exclude : EXCLUDE_TYPES) {
            if (rawType.contains(exclude)) {
                return null;
            }
        }
        if (isNoiseName(name)) {
            return null;
        }
        String location = String.valueOf(poi.getOrDefault("location", ""));
        if (!location.contains(",")) {
            return null;
        }
        String[] coords = location.split(",");
        double lng;
        double lat;
        try {
            lng = Double.parseDouble(coords[0].trim());
            lat = Double.parseDouble(coords[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String mappedType = mapType(rawType, name, fallbackType);
        if (mappedType == null) {
            return null;
        }
        Map<String, Object> place = new HashMap<>();
        place.put("name", name);
        place.put("type", mappedType);
        place.put("lat", lat);
        place.put("lng", lng);
        place.put("address", String.valueOf(poi.getOrDefault("address", "")));
        place.put("preferredDurationMin", durationOf(mappedType));
        place.put("priority", "recommended");
        place.put("source", "amap");
        return place;
    }

    /**
     * 高德 type 字符串 -> 项目地点类型
     * 例：科教文化服务;博物馆;博物馆 -> museum；购物服务;特色商业街;步行街 -> shopping
     */
    private String mapType(String rawType, String name, String fallbackType) {
        if (name.contains("夜市") || name.contains("美食街")) {
            return "shopping";
        }
        if (rawType.contains("博物馆") || rawType.contains("纪念馆")
                || rawType.contains("展览馆") || rawType.contains("美术馆")
                || rawType.contains("科技馆") || rawType.contains("图书馆")
                || rawType.contains("文化馆") || rawType.contains("档案馆")) {
            return "museum";
        }
        if (rawType.contains("步行街") || rawType.contains("商业街")
                || rawType.contains("购物中心") || rawType.contains("商场")) {
            return "shopping";
        }
        if (rawType.contains("公园") || rawType.contains("植物园") || rawType.contains("动物园")) {
            return "park";
        }
        if (rawType.contains("寺庙") || rawType.contains("道观") || rawType.contains("教堂")) {
            return "temple";
        }
        if (rawType.contains("风景名胜") || rawType.contains("风景点")) {
            return "scenic";
        }
        // 无法明确归类但关键词命中的文化/街区类，沿用查询计划类型
        if (fallbackType != null && (rawType.contains("文化") || rawType.contains("历史")
                || rawType.contains("宗教") || rawType.contains("遗址"))) {
            return fallbackType;
        }
        return null;
    }

    /** 过滤与地点无关的噪声名称（售票处、停车场、服务点、交通站点等） */
    private boolean isNoiseName(String name) {
        List<String> noise = List.of("售票", "停车场", "卫生间", "厕所", "服务中心", "服务点",
                "出入口", "检票", "咨询", "饮水", "充电", "酒店", "宾馆", "银行", "加油站", "药店",
                "公交站", "汽车站", "客运站", "收费站");
        for (String n : noise) {
            if (name.contains(n)) {
                return true;
            }
        }
        // 以「站」结尾的多为地铁/火车站点（含括号后缀的变体）
        return name.endsWith("站") || name.contains("站(");
    }

    /** 按类型给建议游玩时长（分钟） */
    private int durationOf(String type) {
        return switch (type) {
            case "museum" -> 120;
            case "shopping" -> 90;
            case "park" -> 90;
            case "temple" -> 60;
            case "scenic" -> 120;
            default -> 90;
        };
    }

    static String normalizeName(String name) {
        return name.replaceAll("\\s+", "");
    }

    /** 距城市中心超过阈值（县郊）的 POI 过滤，保证候选点在市区可达范围内 */
    private List<Map<String, Object>> filterByDistance(List<Map<String, Object>> pois, String city) {
        if (pois.isEmpty()) {
            return pois;
        }
        double[] center = resolveCityCenter(city);
        if (center == null) {
            return pois;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> poi : pois) {
            double lat = ((Number) poi.get("lat")).doubleValue();
            double lng = ((Number) poi.get("lng")).doubleValue();
            if (haversineKm(center[0], center[1], lat, lng) <= MAX_DISTANCE_KM) {
                out.add(poi);
            }
        }
        if (out.size() != pois.size()) {
            log.info("POI 距离过滤: city={}, {} -> {} 条 (阈值 {}km)",
                    city, pois.size(), out.size(), MAX_DISTANCE_KM);
        }
        return out;
    }

    private double[] resolveCityCenter(String city) {
        try {
            GeocodeResponse geo = geocodeService.geocode(city, city, "amap");
            if (geo != null && geo.isSuccess() && geo.getLat() != null && geo.getLng() != null) {
                return new double[]{geo.getLat(), geo.getLng()};
            }
        } catch (Exception e) {
            log.debug("城市中心地理编码失败: {}, {}", city, e.getMessage());
        }
        return null;
    }

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> list, int limit) {
        if (limit <= 0 || list.size() <= limit) {
            return list;
        }
        return new ArrayList<>(list.subList(0, limit));
    }

    private List<Map<String, Object>> getFromCache(String city) {
        CacheEntry entry = cache.get(city);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            cache.remove(city, entry);
            return null;
        }
        return entry.value;
    }

    private void putToCache(String city, List<Map<String, Object>> value) {
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(city, new CacheEntry(List.copyOf(value), System.currentTimeMillis()));
    }

    private record CacheEntry(List<Map<String, Object>> value, long timestamp) {
    }
}
