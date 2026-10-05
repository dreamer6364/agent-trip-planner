package com.tripplanner.plan.service;

import com.tripplanner.plan.config.MapApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 餐厅在线检索服务（评分/人均/地址补全 + 附近餐厅兜底）
 *
 * <p>设计（v1.15.0 餐厅推荐增强）：</p>
 * <ul>
 *   <li>{@link #lookupExact}：静态库命中的具体餐厅 → 在线补 rating/cost/address（名称须匹配）</li>
 *   <li>{@link #searchNearby}：泛化餐名（「本地美食」「午餐·川菜」）→ 附近真实餐厅升级</li>
 *   <li>评分 &lt; 4.0 的候选一律排除；评分缺失保留（决策③）</li>
 *   <li>限流：高德免费版 QPS=3，350ms 槽位（与 GeocodeService 同策略）</li>
 *   <li>内存缓存 30 分钟，超时 3s 降级返回 null，不阻塞主流程</li>
 * </ul>
 *
 * <p>1.35.0 就近推荐修正：有参考点时改走 {@code place/around}（圆形范围 + 距离升序），
 * 排序改为距离优先、评分为次（{@link #nearbyScore}），并按 {@code MAX_ANCHOR_KM}
 * 丢弃远离参考点的候选——修复「推荐了别的城市/全城评分最高而非最近」的偏差。</p>
 *
 * @author TripForge Team
 * @since 1.15.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantSearchService {

    private final MapApiConfig mapApiConfig;
    private final WebClient amapWebClient;

    /** 评分下限：低于该分的候选排除（缺失评分保留） */
    private static final double MIN_RATING = 4.0;
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(3);
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_CACHE_SIZE = 500;
    /** 单次查询最多返回的 POI 数 */
    private static final int MAX_RESULTS = 8;
    /** 就近检索半径（米）：以参考点为圆心的周边检索范围 */
    private static final int AROUND_RADIUS_M = 10_000;
    /** 有参考点时的候选最远距离（km）：超过视为跨城污染结果，直接丢弃（防「餐厅串到别的城市」） */
    private static final double MAX_ANCHOR_KM = 40.0;

    /** 餐厅检索结果（字段与前端 Activity 透传字段对齐） */
    public record RestaurantInfo(String name, Double rating, String cost,
                                 String address, Double lat, Double lng) {
    }

    private static final long AMAP_MIN_INTERVAL_MS = 350;
    private long amapNextSlotMs = 0;

    /**
     * 按名称精确检索餐厅，补评分/人均/地址。
     *
     * @param name    餐厅名（可带「午餐·」前缀，内部剥离）
     * @param city    目标城市（必填，citylimit 限定防跨城）
     * @param nearLat 参考纬度（上一活动，可 null，用于就近排序）
     * @param nearLng 参考经度（可 null）
     * @return 匹配结果；无匹配/全部低于评分线/查询失败返回 null
     */
    public RestaurantInfo lookupExact(String name, String city, Double nearLat, Double nearLng) {
        if (name == null || name.isBlank() || city == null || city.isBlank()) {
            return null;
        }
        String bare = stripMealPrefix(name);
        if (bare.isBlank()) {
            return null;
        }
        // 缓存键带参考点：around 与 text 的结果不同，共用键会互相污染（1.35.0）
        String cacheKey = "exact|" + city + "|" + bare
                + "|" + (nearLat == null ? "" : String.format("%.3f", nearLat))
                + "|" + (nearLng == null ? "" : String.format("%.3f", nearLng));
        RestaurantInfo cached = getFromCache(cacheKey);
        if (cached != null) {
            return isEmptyMarker(cached) ? null : cached;
        }

        boolean anchored = nearLat != null && nearLng != null;
        List<Map<String, Object>> pois = queryPois(bare, city, nearLat, nearLng);
        if (pois.isEmpty() && anchored) {
            // 参考点周边无命中（如名店确实在城另一头）→ 退化为全城按名检索
            pois = queryPois(bare, city, null, null);
        }
        List<Map<String, Object>> found = pois;
        RestaurantInfo best = null;
        double bestScore = Double.MAX_VALUE;
        int lowRated = 0;
        for (Map<String, Object> poi : found) {
            String poiName = str(poi.get("name"));
            if (!namesMatch(bare, poiName)) {
                continue;
            }
            if (!isRestaurantPoi(poi)) {
                continue;
            }
            Double rating = parseRating(poi);
            if (rating != null && rating < MIN_RATING) {
                lowRated++;
                log.debug("排除低分餐厅: {} rating={}", poiName, rating);
                continue;
            }
            double[] coord = parseLocation(poi);
            if (coord == null) {
                continue;
            }
            double dist = anchored ? haversineKm(nearLat, nearLng, coord[1], coord[0]) : -1;
            if (anchored && dist > MAX_ANCHOR_KM) {
                log.debug("排除远离参考点的同名餐厅: {} 距参考点 {}km", poiName, Math.round(dist));
                continue;
            }
            double score = dist < 0 ? 0 : dist;
            if (score < bestScore) {
                bestScore = score;
                best = new RestaurantInfo(poiName, rating, parseCost(poi), parseAddress(poi),
                        coord[1], coord[0]);
            }
        }
        if (best == null && lowRated > 0) {
            log.info("餐厅在线检索全部低分被排除: name={}, city={}, 低分候选={}", bare, city, lowRated);
        }
        putToCache(cacheKey, best);
        return best;
    }

    /**
     * 附近真实餐厅检索（泛化餐名升级兜底）。
     *
     * @param keyword 关键词（菜系如「杭帮菜」，或「美食」）
     * @param city    目标城市
     * @param nearLat 参考纬度（优先返回附近餐厅）
     * @param nearLng 参考经度
     * @return 候选餐厅；失败/无合格候选返回 null
     */
    public RestaurantInfo searchNearby(String keyword, String city, Double nearLat, Double nearLng) {
        if (keyword == null || keyword.isBlank() || city == null || city.isBlank()) {
            return null;
        }
        String kw = keyword.trim();
        boolean anchored = nearLat != null && nearLng != null;
        String cacheKey = "nearby|" + city + "|" + kw
                + "|" + (nearLat == null ? "" : String.format("%.3f", nearLat))
                + "|" + (nearLng == null ? "" : String.format("%.3f", nearLng));
        RestaurantInfo cached = getFromCache(cacheKey);
        if (cached != null) {
            return isEmptyMarker(cached) ? null : cached;
        }

        List<Map<String, Object>> pois = queryPois(kw, city, nearLat, nearLng);
        if (pois.isEmpty() && anchored) {
            pois = queryPois(kw, city, null, null);
        }
        List<Map<String, Object>> found = pois;
        List<Map<String, Object>> qualified = new ArrayList<>();
        for (Map<String, Object> poi : found) {
            String poiName = str(poi.get("name"));
            if (poiName.length() < 2 || poiName.length() > 20 || !isRestaurantPoi(poi)) {
                continue;
            }
            Double rating = parseRating(poi);
            if (rating != null && rating < MIN_RATING) {
                continue;
            }
            double[] c = parseLocation(poi);
            if (c == null) {
                continue;
            }
            if (anchored && haversineKm(nearLat, nearLng, c[1], c[0]) > MAX_ANCHOR_KM) {
                continue;
            }
            qualified.add(poi);
        }
        // 距离优先、评分为次（1.35.0）：此前 d - rating*1000 让评分完全压过距离，
        // 推荐结果常是全城评分最高而非离上一景点最近的餐厅
        qualified.sort(Comparator.comparingDouble(p -> {
            double[] c = parseLocation(p);
            if (c == null) {
                return Double.MAX_VALUE;
            }
            double d = anchored ? haversineKm(nearLat, nearLng, c[1], c[0]) : 0;
            return nearbyScore(d, parseRating(p));
        }));

        RestaurantInfo best = null;
        if (!qualified.isEmpty()) {
            Map<String, Object> top = qualified.get(0);
            double[] c = parseLocation(top);
            best = new RestaurantInfo(str(top.get("name")), parseRating(top), parseCost(top),
                    parseAddress(top), c[1], c[0]);
        }
        putToCache(cacheKey, best);
        if (best != null) {
            log.info("附近餐厅检索命中: keyword={}, city={} -> {} (rating={})",
                    kw, city, best.name(), best.rating());
        }
        return best;
    }

    /**
     * 就近候选打分（越小越优）：距离为主、评分为次。
     *
     * <p>评分每低 0.5 分等价于远 0.5km，即评分最多只能「抵消」公里级以内的距离差——
     * 保证同一批候选里最近的餐厅优先（用户诉求：优先推荐距离上一景点近的餐厅）。
     * 无参考距离（d &lt; 0）时退化为纯评分排序；评分缺失按 4.5 计（不奖励不重罚）。</p>
     *
     * @param distKm 与参考点的距离（公里）；负数表示未知
     * @param rating 评分（可空）
     */
    static double nearbyScore(double distKm, Double rating) {
        double d = distKm < 0 ? 0 : distKm;
        double r = rating == null ? 4.5 : rating;
        return d + (5.0 - r);
    }

    /** 检索 POI：有参考点时走 place/around（圆形范围 + 距离排序），否则走 place/text 城市文本检索 */
    private List<Map<String, Object>> queryPois(String keywords, String city,
                                                Double nearLat, Double nearLng) {
        String url = nearLat != null && nearLng != null
                ? buildAroundUrl(keywords, city, nearLat, nearLng)
                : buildTextUrl(keywords, city);
        try {
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(QUERY_TIMEOUT);
            if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
                log.debug("餐厅检索接口异常: keywords={}, city={}, info={}",
                        keywords, city, response == null ? "null" : response.get("info"));
                return List.of();
            }
            Object poisObj = response.get("pois");
            if (!(poisObj instanceof List<?> pois) || pois.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : pois) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> poi = (Map<String, Object>) m;
                    out.add(poi);
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("餐厅检索请求失败: keywords={}, city={}, err={}", keywords, city, e.getMessage());
            return List.of();
        }
    }

    /** place/text 文本检索 URL：citylimit 限定城市，防跨城结果混入 */
    private String buildTextUrl(String keywords, String city) {
        return mapApiConfig.getAmapPlaceUrl()
                + "?key=" + mapApiConfig.getAmapApiKey()
                + "&keywords=" + encode(keywords)
                + "&city=" + encode(AmapCityAlias.toAmapCity(city))
                + "&citylimit=true"
                + "&types=" + encode("050000")
                + "&offset=" + MAX_RESULTS
                + "&page=1"
                + "&extensions=all";
    }

    /**
     * place/around 周边检索 URL：以参考点为圆心、按距离升序返回餐饮 POI。
     *
     * <p>与 place/text 的关键差异：text 只能按城市文本匹配（同城内仍是「全市候选」，
     * 最近的餐厅很可能不在前 8 条里）；around 用 location+radius 直接圈定参考点附近，
     * sortrule=distance 保证最近优先——这是「优先推荐距离上一景点近的餐厅」的检索基础。</p>
     */
    private String buildAroundUrl(String keywords, String city, double lat, double lng) {
        int radius = (int) Math.min(50_000,
                Math.max(1_000, AmapCityAlias.radiusKm(city, AROUND_RADIUS_M / 1000.0) * 1000));
        StringBuilder url = new StringBuilder(mapApiConfig.getAmapAroundUrl())
                .append("?key=").append(mapApiConfig.getAmapApiKey())
                .append("&location=")
                .append(String.format(java.util.Locale.ROOT, "%.6f,%.6f", lng, lat))
                .append("&keywords=").append(encode(keywords))
                .append("&types=").append(encode("050000"))
                .append("&radius=").append(radius)
                .append("&sortrule=distance")
                .append("&offset=").append(MAX_RESULTS)
                .append("&page=1")
                .append("&extensions=all");
        if (city != null && !city.isBlank()) {
            url.append("&city=").append(encode(AmapCityAlias.toAmapCity(city)));
        }
        return url.toString();
    }

    /** 是否餐饮类 POI（type 含餐饮/food，或未标注类型但带评分） */
    private boolean isRestaurantPoi(Map<String, Object> poi) {
        String type = str(poi.get("type"));
        if (type.isBlank()) {
            return parseRating(poi) != null;
        }
        return type.contains("餐饮") || type.toLowerCase().contains("food")
                || type.contains("餐厅") || type.contains("小吃") || type.contains("咖啡");
    }

    /** 名称匹配：去空白后相等，或双向包含（「楼外楼(西湖店)」vs「楼外楼」） */
    private boolean namesMatch(String wanted, String candidate) {
        String a = normalize(wanted);
        String b = stripBranchSuffix(normalize(candidate));
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private String stripBranchSuffix(String name) {
        return name.replaceFirst("[（(][^（()）]{1,10}[)）]$", "");
    }

    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s·・\\.\\-—_]+", "");
    }

    /** 解析 biz_ext.rating：「4.5」/4.5 → Double；缺失/非数字返回 null */
    private Double parseRating(Map<String, Object> poi) {
        Object bizExt = poi.get("biz_ext");
        Object raw = null;
        if (bizExt instanceof Map<?, ?> m) {
            raw = m.get("rating");
        }
        if (raw == null) {
            raw = poi.get("rating");
        }
        if (raw == null) {
            return null;
        }
        try {
            String s = String.valueOf(raw).replaceAll("[^0-9.]", "");
            if (s.isEmpty()) {
                return null;
            }
            double v = Double.parseDouble(s);
            return v >= 0 && v <= 5 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析 biz_ext.cost（人均）：「¥80/人」→「¥80/人」原样；缺失返回 null */
    private String parseCost(Map<String, Object> poi) {
        Object bizExt = poi.get("biz_ext");
        Object raw = null;
        if (bizExt instanceof Map<?, ?> m) {
            raw = m.get("cost");
        }
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "[]".equals(s)) {
            return null;
        }
        return s;
    }

    private String parseAddress(Map<String, Object> poi) {
        Object addr = poi.get("address");
        if (addr == null) {
            return "";
        }
        String s = String.valueOf(addr).trim();
        if (s.isEmpty() || "[]".equals(s)) {
            return "";
        }
        return s;
    }

    /** location「lng,lat」→ [lng, lat]；无效返回 null */
    private double[] parseLocation(Map<String, Object> poi) {
        String location = str(poi.get("location"));
        if (!location.contains(",")) {
            return null;
        }
        String[] parts = location.split(",");
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String stripMealPrefix(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    /** 高德免费版 QPS=3：350ms 槽位放行 */
    private void throttleAmap() {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, amapNextSlotMs);
            amapNextSlotMs = slot + AMAP_MIN_INTERVAL_MS;
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------- 缓存 ----------

    /** 缓存哨兵：name 为空表示「查过但无合格结果」，避免重复打接口 */
    private static final RestaurantInfo EMPTY =
            new RestaurantInfo("", null, null, null, null, null);

    private boolean isEmptyMarker(RestaurantInfo info) {
        return info == EMPTY || (info.name() != null && info.name().isEmpty());
    }

    private record CacheEntry(RestaurantInfo value, long timestamp) {
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private RestaurantInfo getFromCache(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            cache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private void putToCache(String key, RestaurantInfo value) {
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(value == null ? EMPTY : value, System.currentTimeMillis()));
    }
}
