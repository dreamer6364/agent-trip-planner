package com.tripplanner.plan.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.GeoUtils;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.plan.config.MapApiConfig;
import com.tripplanner.plan.dto.response.GeocodeResponse;
import com.tripplanner.plan.entity.GeocodeCache;
import com.tripplanner.plan.repository.GeocodeCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 地理编码服务
 * 支持高德/百度双源、缓存、降级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeocodeService {

    private final MapApiConfig mapApiConfig;
    private final WebClient amapWebClient;
    private final WebClient baiduWebClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final GeocodeCacheRepository geocodeCacheRepository;
    private final JsonUtils jsonUtils;

    private static final String CACHE_PREFIX = "geocode:";
    private static final long CACHE_TTL_DAYS = 30;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 地理编码 (地址/名称 -> 坐标)
     */
    public GeocodeResponse geocode(String query, String city, String source) {
        // 1. Redis 缓存查询
        String cacheKey = buildCacheKey(query, city, source);
        Object cachedObj = redisTemplate.opsForValue().get(cacheKey);
        if (cachedObj != null) {
            GeocodeResponse cached = toGeocodeResponse(cachedObj);
            if (cached != null) {
                log.debug("地理编码命中 Redis 缓存: {}", query);
                return cached;
            }
        }

        // 2. MySQL 缓存查询
        String queryHash = hashQuery(query, city, source);
        GeocodeCache dbCache = geocodeCacheRepository.findByHash(queryHash, source);
        if (dbCache != null) {
            GeocodeResponse response = toResponse(dbCache);
            redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL_DAYS, TimeUnit.DAYS);
            return response;
        }

        // 3. 调用外部 API
        GeocodeResponse response = null;
        try {
            if ("baidu".equalsIgnoreCase(source)) {
                response = callBaiduGeocode(query, city);
            } else {
                response = callAmapGeocode(query, city);
            }
        } catch (Exception e) {
            log.warn("外部API地理编码失败: query={}, error={}", query, e.getMessage());
        }

        // 3.5 POI 搜索兜底：地址型 geocode 对景点名命中率低（ENGINE_RESPONSE_DATA_ERROR 等）
        if ((response == null || !response.isSuccess()) && !"baidu".equalsIgnoreCase(source)) {
            GeocodeResponse place = callAmapPlace(query, city);
            if (place != null && place.isSuccess()) {
                log.info("POI搜索兜底成功: query={}, lat={}, lng={}", query, place.getLat(), place.getLng());
                response = place;
            }
        }

        // 4. 降级：使用内置坐标表（仅当归属城市与请求一致）
        if (response == null || !response.isSuccess()) {
            GeocodeResponse fallback = getFallbackGeocode(query, city);
            if (fallback != null && fallbackCityMatches(fallback, city)) {
                log.info("使用内置坐标: query={}, lat={}, lng={}, fallbackCity={}",
                        query, fallback.getLat(), fallback.getLng(), fallback.getCity());
                return fallback;
            }
            if (fallback != null && !fallbackCityMatches(fallback, city)) {
                log.warn("丢弃跨城地理编码兜底: query={}, fallbackCity={}, requestCity={}",
                        query, fallback.getCity(), city);
            }
        }

        // 5. 缓存结果
        if (response != null && response.isSuccess()) {
            redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL_DAYS, TimeUnit.DAYS);
            saveToDb(query, city, source, response);
        }

        return response != null ? response : GeocodeResponse.failure(query, "地理编码失败");
    }

/** 兜底结果城市是否允许用于当前请求 */
    private boolean fallbackCityMatches(GeocodeResponse fallback, String requestCity) {
        if (requestCity == null || requestCity.isBlank()) {
            return true;
        }
        String fb = fallback.getCity();
        if (fb == null || fb.isBlank()) {
            return true;
        }
        String req = requestCity.endsWith("市") && requestCity.length() > 2
                ? requestCity.substring(0, requestCity.length() - 1)
                : requestCity;
        String f = fb.endsWith("市") && fb.length() > 2
                ? fb.substring(0, fb.length() - 1)
                : fb;
        return req.equals(f) || req.startsWith(f) || f.startsWith(req);
    }

    /**
     * 批量地理编码
     * <p>
     * 必须串行：高德免费版地理编码 QPS=3，并发调用会整批返回
     * {@code CUQPS_HAS_EXCEEDED_LIMIT}，导致行程里的景点批量拿不到坐标
     * （地图地标缺失、路程时间无法修正）。
     */
    public List<GeocodeResponse> batchGeocode(List<String> queries, String city, String source) {
        List<GeocodeResponse> results = new java.util.ArrayList<>(queries.size());
        for (String q : queries) {
            GeocodeResponse r = geocode(q, city, source);
            if (!r.isSuccess() && isQpsError(r.getErrorMessage())) {
                // 突发限流：等一个窗口后重试一次（失败结果不会进缓存，可安全重查）
                sleepQuietly(1200);
                r = geocode(q, city, source);
            }
            results.add(r);
        }
        return results;
    }

    /** 高德限流类错误 */
    private boolean isQpsError(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String e = errorMessage.toUpperCase();
        return e.contains("CUQPS") || e.contains("QPS") || e.contains("EXCEEDED");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 高德免费版 QPS=3：全局按 350ms 间隔放行，避免并发规划把配额打爆 */
    private static final long AMAP_MIN_INTERVAL_MS = 350;

    private long amapNextSlotMs = 0;

    private void throttleAmap() {
        long wait;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, amapNextSlotMs);
            amapNextSlotMs = slot + AMAP_MIN_INTERVAL_MS;
            wait = slot - now;
        }
        if (wait > 0) {
            sleepQuietly(wait);
        }
    }


    /**
     * 逆地理编码 (坐标 -> 地址)
     */
    public GeocodeResponse reverseGeocode(double lat, double lng, String source) {
        String cacheKey = buildCacheKey("reverse:" + lat + "," + lng, null, source);
        
        Object cachedObj = redisTemplate.opsForValue().get(cacheKey);
        GeocodeResponse cached = toGeocodeResponse(cachedObj);
        if (cached != null) {
            return cached;
        }

        GeocodeResponse response;
        if ("baidu".equalsIgnoreCase(source)) {
            response = callBaiduRegeocode(lat, lng);
        } else {
            response = callAmapRegeocode(lat, lng);
        }

        if (response.isSuccess()) {
            redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL_DAYS, TimeUnit.DAYS);
        }
        return response;
    }

    private GeocodeResponse callAmapGeocode(String query, String city) {
        try {
            String url = mapApiConfig.getAmapGeocodeUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&address=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                    + "&city=" + java.net.URLEncoder.encode(city != null ? city : "", java.nio.charset.StandardCharsets.UTF_8)
                    + "&output=JSON";
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseAmapGeocodeResponse(response, query);
        } catch (Exception e) {
            log.error("高德地理编码失败: query={}, error={}", query, e.getMessage());
            return GeocodeResponse.failure(query, "高德地理编码失败: " + e.getMessage());
        }
    }

    /**
     * 高德 POI 搜索（/place/text）兜底：
     * 地址型地理编码对「景点名」命中率低（常返回 ENGINE_RESPONSE_DATA_ERROR），
     * 景点/餐厅这类名称用 POI 搜索才能拿到精确坐标。
     */
    private GeocodeResponse callAmapPlace(String query, String city) {
        try {
            String url = mapApiConfig.getAmapPlaceUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&keywords=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                    + (city == null || city.isBlank()
                            ? ""
                            : "&city=" + java.net.URLEncoder.encode(city, java.nio.charset.StandardCharsets.UTF_8))
                    + "&citylimit=true&offset=5&page=1&output=JSON";
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return parseAmapPlaceResponse(response, query, city);
        } catch (Exception e) {
            log.error("高德POI搜索失败: query={}, error={}", query, e.getMessage());
            return GeocodeResponse.failure(query, "高德POI搜索失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private GeocodeResponse parseAmapPlaceResponse(Map<String, Object> response, String query, String city) {
        if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
            String info = response == null ? "NULL_RESPONSE"
                    : String.valueOf(response.getOrDefault("info", "UNKNOWN_ERROR"));
            return GeocodeResponse.failure(query, "高德POI搜索错误: " + info);
        }
        List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
        if (pois == null || pois.isEmpty()) {
            return GeocodeResponse.failure(query, "未找到匹配地点");
        }
        Map<String, Object> poi = pois.get(0);
        String location = String.valueOf(poi.getOrDefault("location", ""));
        String[] coords = location.split(",");
        if (coords.length < 2) {
            return GeocodeResponse.failure(query, "POI坐标缺失");
        }
        double lng = Double.parseDouble(coords[0].trim());
        double lat = Double.parseDouble(coords[1].trim());
        String name = String.valueOf(poi.getOrDefault("name", query));
        String address = poi.get("address") == null ? "" : String.valueOf(poi.get("address"));
        String formatted = (name + (address.isEmpty() || "[]".equals(address) ? "" : " " + address)).trim();
        return GeocodeResponse.of(query, true, formatted, lat, lng,
                GeoUtils.toWktPoint(lng, lat), null, city, null, "poi", "amap");
    }


    private GeocodeResponse callBaiduGeocode(String query, String city) {
        try {
            String url = mapApiConfig.getBaiduGeocodeUrl();
            Map<String, Object> response = baiduWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(url)
                            .queryParam("ak", mapApiConfig.getBaiduApiKey())
                            .queryParam("address", query)
                            .queryParam("city", city != null ? city : "")
                            .queryParam("output", "json")
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseBaiduGeocodeResponse(response, query);
        } catch (Exception e) {
            log.error("百度地理编码失败: query={}, error={}", query, e.getMessage());
            return GeocodeResponse.failure(query, "百度地理编码失败: " + e.getMessage());
        }
    }

    private GeocodeResponse callAmapRegeocode(double lat, double lng) {
        try {
            String url = mapApiConfig.getAmapRegeocodeUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&location=" + lng + "," + lat
                    + "&output=JSON";
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseAmapRegeocodeResponse(response);
        } catch (Exception e) {
            log.error("高德逆地理编码失败: lat={}, lng={}, error={}", lat, lng, e.getMessage());
            return GeocodeResponse.failure("逆地理编码失败: " + e.getMessage());
        }
    }

    private GeocodeResponse callBaiduRegeocode(double lat, double lng) {
        try {
            String url = "https://api.map.baidu.com/reverse_geocoding/v3";
            Map<String, Object> response = baiduWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(url)
                            .queryParam("ak", mapApiConfig.getBaiduApiKey())
                            .queryParam("location", lat + "," + lng)
                            .queryParam("output", "json")
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseBaiduRegeocodeResponse(response);
        } catch (Exception e) {
            log.error("百度逆地理编码失败: lat={}, lng={}, error={}", lat, lng, e.getMessage());
            return GeocodeResponse.failure("逆地理编码失败: " + e.getMessage());
        }
    }

    private GeocodeResponse parseAmapGeocodeResponse(Map<String, Object> response, String query) {
        if (response == null || !"1".equals(response.get("status"))) {
            String info = (String) response.getOrDefault("info", "UNKNOWN_ERROR");
            return GeocodeResponse.failure("高德返回错误: " + info);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> geocodes = (List<Map<String, Object>>) response.get("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return GeocodeResponse.failure("未找到匹配地点");
        }

        Map<String, Object> geo = geocodes.get(0);
        String location = (String) geo.get("location"); // "lng,lat"
        String[] coords = location.split(",");
        
        return GeocodeResponse.of(query, true, (String) geo.get("formatted_address"),
                Double.parseDouble(coords[1]), Double.parseDouble(coords[0]),
                GeoUtils.toWktPoint(Double.parseDouble(coords[0]), Double.parseDouble(coords[1])),
                (String) geo.get("province"), (String) geo.get("city"), (String) geo.get("district"),
                (String) geo.get("category"), "amap");
    }

    private GeocodeResponse parseBaiduGeocodeResponse(Map<String, Object> response, String query) {
        if (response == null || !"0".equals(String.valueOf(response.get("status")))) {
            return GeocodeResponse.failure("百度返回错误: " + response.get("message"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) result.get("location");
        
        return GeocodeResponse.of(query, true, (String) result.get("formatted_address"),
                ((Number) location.get("lng")).doubleValue(),
                ((Number) location.get("lat")).doubleValue(),
                GeoUtils.toWktPoint(((Number) location.get("lng")).doubleValue(), ((Number) location.get("lat")).doubleValue()),
                (String) result.get("province"), (String) result.get("city"), (String) result.get("district"),
                "baidu");
    }

    private GeocodeResponse parseAmapRegeocodeResponse(Map<String, Object> response) {
        if (response == null || !"1".equals(response.get("status"))) {
            return GeocodeResponse.failure("高德逆地理编码错误: " + response.get("info"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> regeocode = (Map<String, Object>) response.get("regeocode");
        @SuppressWarnings("unchecked")
        Map<String, Object> addressComponent = (Map<String, Object>) regeocode.get("addressComponent");
        
        return GeocodeResponse.of("reverse", true,
                (String) regeocode.get("formatted_address"),
                0, 0, "",
                (String) addressComponent.get("province"),
                (String) addressComponent.get("city"),
                (String) addressComponent.get("district"),
                "amap");
    }

    private GeocodeResponse parseBaiduRegeocodeResponse(Map<String, Object> response) {
        if (response == null || !"0".equals(String.valueOf(response.get("status")))) {
            return GeocodeResponse.failure("百度逆地理编码错误: " + response.get("message"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        
        return GeocodeResponse.of("reverse", true,
                (String) result.get("formatted_address"),
                0, 0, "",
                (String) result.get("province"),
                (String) result.get("city"),
                (String) result.get("district"),
                "baidu");
    }

    private GeocodeResponse toResponse(GeocodeCache cache) {
        return GeocodeResponse.of(cache.getQueryText(), true,
                cache.getFormattedAddress(),
                parseLat(cache.getLocation()), parseLng(cache.getLocation()),
                cache.getLocation(),
                cache.getProvince(), cache.getCity(), cache.getDistrict(),
                cache.getPoiType(), cache.getConfidence(), cache.getSource());
    }

    private double parseLat(String wkt) {
        double[] coords = GeoUtils.parseWktPoint(wkt);
        return coords[1];
    }

    private double parseLng(String wkt) {
        double[] coords = GeoUtils.parseWktPoint(wkt);
        return coords[0];
    }

    private void saveToDb(String query, String city, String source, GeocodeResponse response) {
        try {
            GeocodeCache cache = new GeocodeCache();
            cache.setQueryHash(hashQuery(query, city, source));
            cache.setQueryText(query + (city != null ? "|" + city : ""));
            cache.setSource(source);
            cache.setFormattedAddress(response.getFormattedAddress());
            cache.setLocation(response.getLocation());
            cache.setProvince(response.getProvince());
            cache.setCity(response.getCity());
            cache.setDistrict(response.getDistrict());
            cache.setPoiType(response.getPoiType());
            cache.setConfidence(response.getConfidence());
            cache.setRawResponse(jsonUtils.toJson(response));
            geocodeCacheRepository.insert(cache);
        } catch (Exception e) {
            log.warn("保存地理编码缓存到 DB 失败: {}", e.getMessage());
        }
    }

    private String buildCacheKey(String query, String city, String source) {
        return CACHE_PREFIX + source + ":" + hashQuery(query, city, source);
    }

    /**
     * 内置坐标降级表（无API Key时使用）
     */
    private GeocodeResponse getFallbackGeocode(String query, String city) {
        String q = (query + " " + (city != null ? city : "")).toLowerCase();

        // ========== 上海 ==========
        if (q.contains("外滩") || q.contains("bund")) {
            return GeocodeResponse.of(query, true, "上海外滩", 31.2400, 121.4900,
                    "POINT(121.4900 31.2400)", "上海", "上海", "黄浦区", "风景名胜", 0.9, "fallback");
        }
        if (q.contains("南京路") || q.contains("nanjing road")) {
            return GeocodeResponse.of(query, true, "南京路步行街", 31.2360, 121.4750,
                    "POINT(121.4750 31.2360)", "上海", "上海", "黄浦区", "购物", 0.9, "fallback");
        }
        if (q.contains("陆家嘴")) {
            return GeocodeResponse.of(query, true, "陆家嘴", 31.2397, 121.4998,
                    "POINT(121.4998 31.2397)", "上海", "上海", "浦东新区", "商业区", 0.9, "fallback");
        }
        if (q.contains("豫园") || q.contains("城隍庙")) {
            return GeocodeResponse.of(query, true, "豫园", 31.2273, 121.4920,
                    "POINT(121.4920 31.2273)", "上海", "上海", "黄浦区", "景点", 0.9, "fallback");
        }
        if (q.contains("迪士尼")) {
            return GeocodeResponse.of(query, true, "上海迪士尼", 31.1440, 121.6580,
                    "POINT(121.6580 31.1440)", "上海", "上海", "浦东新区", "主题乐园", 0.9, "fallback");
        }
        if (q.contains("东方明珠") || q.contains("明珠")) {
            return GeocodeResponse.of(query, true, "东方明珠", 31.2398, 121.4953,
                    "POINT(121.4953 31.2398)", "上海", "上海", "浦东新区", "景点", 0.9, "fallback");
        }
        if (q.contains("田子坊")) {
            return GeocodeResponse.of(query, true, "田子坊", 31.2095, 121.4625,
                    "POINT(121.4625 31.2095)", "上海", "上海", "黄浦区", "创意园区", 0.9, "fallback");
        }
        if (q.contains("武康路") || q.contains("武康大楼")) {
            return GeocodeResponse.of(query, true, "武康路", 31.2085, 121.4365,
                    "POINT(121.4365 31.2085)", "上海", "上海", "徐汇区", "历史建筑", 0.9, "fallback");
        }
        if (q.contains("新天地")) {
            return GeocodeResponse.of(query, true, "新天地", 31.2190, 121.4740,
                    "POINT(121.4740 31.2190)", "上海", "上海", "黄浦区", "商业区", 0.9, "fallback");
        }
        if (q.contains("淮海路") || q.contains("淮海中路")) {
            return GeocodeResponse.of(query, true, "淮海中路", 31.2190, 121.4610,
                    "POINT(121.4610 31.2190)", "上海", "上海", "黄浦区", "购物", 0.9, "fallback");
        }
        // ========== 杭州 ==========
        if (q.contains("西湖")) {
            return GeocodeResponse.of(query, true, "西湖", 30.2420, 120.1450,
                    "POINT(120.1450 30.2420)", "浙江", "杭州", "西湖区", "风景名胜", 0.9, "fallback");
        }
        if (q.contains("灵隐寺")) {
            return GeocodeResponse.of(query, true, "灵隐寺", 30.2410, 120.1010,
                    "POINT(120.1010 30.2410)", "浙江", "杭州", "西湖区", "寺庙", 0.9, "fallback");
        }
        if (q.contains("千岛湖")) {
            return GeocodeResponse.of(query, true, "千岛湖", 29.6040, 119.0120,
                    "POINT(119.0120 29.6040)", "浙江", "杭州", "淳安县", "风景名胜", 0.9, "fallback");
        }
        if (q.contains("河坊街") || q.contains("河坊")) {
            return GeocodeResponse.of(query, true, "河坊街", 30.2460, 120.1690,
                    "POINT(120.1690 30.2460)", "浙江", "杭州", "上城区", "商业街", 0.9, "fallback");
        }
        // ========== 北京 ==========
        if (q.contains("天安门") || q.contains("故宫") || q.contains("紫禁城")) {
            return GeocodeResponse.of(query, true, "天安门广场", 39.9055, 116.3976,
                    "POINT(116.3976 39.9055)", "北京", "北京", "东城区", "景点", 0.9, "fallback");
        }
        if (q.contains("长城") || q.contains("八达岭")) {
            return GeocodeResponse.of(query, true, "八达岭长城", 40.3540, 116.0190,
                    "POINT(116.0190 40.3540)", "北京", "北京", "延庆区", "景点", 0.9, "fallback");
        }
        if (q.contains("颐和园")) {
            return GeocodeResponse.of(query, true, "颐和园", 39.9999, 116.2750,
                    "POINT(116.2750 39.9999)", "北京", "北京", "海淀区", "景点", 0.9, "fallback");
        }
        if (q.contains("王府井")) {
            return GeocodeResponse.of(query, true, "王府井", 39.9138, 116.4104,
                    "POINT(116.4104 39.9138)", "北京", "北京", "东城区", "商业街", 0.9, "fallback");
        }
        if (q.contains("南锣鼓巷") || q.contains("南锣")) {
            return GeocodeResponse.of(query, true, "南锣鼓巷", 39.9375, 116.4031,
                    "POINT(116.4031 39.9375)", "北京", "北京", "东城区", "胡同", 0.9, "fallback");
        }
        if (q.contains("鸟巢") || q.contains("国家体育场")) {
            return GeocodeResponse.of(query, true, "鸟巢", 39.9929, 116.3966,
                    "POINT(116.3966 39.9929)", "北京", "北京", "朝阳区", "体育场馆", 0.9, "fallback");
        }
        if (q.contains("三里屯")) {
            return GeocodeResponse.of(query, true, "三里屯", 39.9337, 116.4540,
                    "POINT(116.4540 39.9337)", "北京", "北京", "朝阳区", "商业区", 0.9, "fallback");
        }
        // ========== 成都 ==========
        if (q.contains("宽窄巷子") || q.contains("宽窄")) {
            return GeocodeResponse.of(query, true, "宽窄巷子", 30.6720, 104.0560,
                    "POINT(104.0560 30.6720)", "四川", "成都", "青羊区", "商业街", 0.9, "fallback");
        }
        if (q.contains("锦里")) {
            return GeocodeResponse.of(query, true, "锦里", 30.6460, 104.0480,
                    "POINT(104.0480 30.6460)", "四川", "成都", "武侯区", "商业街", 0.9, "fallback");
        }
        if (q.contains("武侯祠")) {
            return GeocodeResponse.of(query, true, "武侯祠", 30.6455, 104.0485,
                    "POINT(104.0485 30.6455)", "四川", "成都", "武侯区", "景点", 0.9, "fallback");
        }
        if (q.contains("大熊猫") || q.contains("熊猫基地") || q.contains("熊猫")) {
            return GeocodeResponse.of(query, true, "成都大熊猫繁育研究基地", 30.7340, 104.1450,
                    "POINT(104.1450 30.7340)", "四川", "成都", "成华区", "动物园", 0.9, "fallback");
        }
        if (q.contains("春熙路")) {
            return GeocodeResponse.of(query, true, "春熙路", 30.6570, 104.0820,
                    "POINT(104.0820 30.6570)", "四川", "成都", "锦江区", "商业街", 0.9, "fallback");
        }
        // ========== 广州 ==========
        if (q.contains("广州塔") || q.contains("小蛮腰")) {
            return GeocodeResponse.of(query, true, "广州塔", 23.1065, 113.3245,
                    "POINT(113.3245 23.1065)", "广东", "广州", "海珠区", "地标", 0.9, "fallback");
        }
        if (q.contains("沙面")) {
            return GeocodeResponse.of(query, true, "沙面", 23.1070, 113.2440,
                    "POINT(113.2440 23.1070)", "广东", "广州", "荔湾区", "历史街区", 0.9, "fallback");
        }
        if (q.contains("珠江") || q.contains("珠江夜游")) {
            return GeocodeResponse.of(query, true, "珠江", 23.1180, 113.2530,
                    "POINT(113.2530 23.1180)", "广东", "广州", "越秀区", "风景名胜", 0.9, "fallback");
        }
        // ========== 深圳 ==========
        if (q.contains("世界之窗")) {
            return GeocodeResponse.of(query, true, "世界之窗", 22.5340, 113.9720,
                    "POINT(113.9720 22.5340)", "广东", "深圳", "南山区", "主题乐园", 0.9, "fallback");
        }
        if (q.contains("大梅沙") || q.contains("小梅沙")) {
            return GeocodeResponse.of(query, true, "大梅沙海滨公园", 22.6000, 114.3150,
                    "POINT(114.3150 22.6000)", "广东", "深圳", "盐田区", "海滩", 0.9, "fallback");
        }
        // ========== 西安 ==========
        if (q.contains("兵马俑") || q.contains("秦始皇")) {
            return GeocodeResponse.of(query, true, "秦始皇兵马俑博物馆", 34.3845, 109.2785,
                    "POINT(109.2785 34.3845)", "陕西", "西安", "临潼区", "博物馆", 0.9, "fallback");
        }
        if (q.contains("大雁塔") || q.contains("雁塔")) {
            return GeocodeResponse.of(query, true, "大雁塔", 34.2220, 108.9620,
                    "POINT(108.9620 34.2220)", "陕西", "西安", "雁塔区", "景点", 0.9, "fallback");
        }
        if (q.contains("回民街") || q.contains("回坊")) {
            return GeocodeResponse.of(query, true, "回民街", 34.2630, 108.9420,
                    "POINT(108.9420 34.2630)", "陕西", "西安", "莲湖区", "美食街", 0.9, "fallback");
        }
        if (q.contains("城墙") || q.contains("西安城墙")) {
            return GeocodeResponse.of(query, true, "西安城墙", 34.2600, 108.9430,
                    "POINT(108.9430 34.2600)", "陕西", "西安", "碑林区", "景点", 0.9, "fallback");
        }
        // ========== 苏州 ==========
        if (q.contains("拙政园")) {
            return GeocodeResponse.of(query, true, "拙政园", 31.3246, 120.6295,
                    "POINT(120.6295 31.3246)", "江苏", "苏州", "姑苏区", "园林", 0.9, "fallback");
        }
        if (q.contains("虎丘")) {
            return GeocodeResponse.of(query, true, "虎丘", 31.3110, 120.5720,
                    "POINT(120.5720 31.3110)", "江苏", "苏州", "姑苏区", "景点", 0.9, "fallback");
        }
        if (q.contains("平江路")) {
            return GeocodeResponse.of(query, true, "平江路", 31.3170, 120.6380,
                    "POINT(120.6380 31.3170)", "江苏", "苏州", "姑苏区", "历史街区", 0.9, "fallback");
        }
        // ========== 重庆 ==========
        if (q.contains("洪崖洞")) {
            return GeocodeResponse.of(query, true, "洪崖洞", 29.5630, 106.5730,
                    "POINT(106.5730 29.5630)", "重庆", "重庆", "渝中区", "景点", 0.9, "fallback");
        }
        if (q.contains("解放碑")) {
            return GeocodeResponse.of(query, true, "解放碑", 29.5630, 106.5750,
                    "POINT(106.5750 29.5630)", "重庆", "重庆", "渝中区", "地标", 0.9, "fallback");
        }
        if (q.contains("磁器口")) {
            return GeocodeResponse.of(query, true, "磁器口古镇", 29.5820, 106.4430,
                    "POINT(106.4430 29.5820)", "重庆", "重庆", "沙坪坝区", "古镇", 0.9, "fallback");
        }
        // ========== 通用餐饮连锁 / 知名餐厅 ==========
        if (q.contains("楼外楼")) {
            return GeocodeResponse.of(query, true, "杭州楼外楼", 30.2485, 120.1425,
                    "POINT(120.1425 30.2485)", "浙江", "杭州", "西湖区", "餐厅", 0.9, "fallback");
        }
        if (q.contains("知味观")) {
            return GeocodeResponse.of(query, true, "杭州知味观", 30.2470, 120.1640,
                    "POINT(120.1640 30.2470)", "浙江", "杭州", "上城区", "餐厅", 0.9, "fallback");
        }
        if (q.contains("新白鹿")) {
            return GeocodeResponse.of(query, true, "杭州新白鹿", 30.2560, 120.1620,
                    "POINT(120.1620 30.2560)", "浙江", "杭州", "西湖区", "餐厅", 0.9, "fallback");
        }
        if (q.contains("绿茶餐厅") || q.equals("绿茶")) {
            return GeocodeResponse.of(query, true, "杭州绿茶餐厅", 30.2680, 120.1450,
                    "POINT(120.1450 30.2680)", "浙江", "杭州", "西湖区", "餐厅", 0.85, "fallback");
        }
        if (q.contains("弄堂里")) {
            return GeocodeResponse.of(query, true, "杭州弄堂里", 30.2410, 120.1680,
                    "POINT(120.1680 30.2410)", "浙江", "杭州", "上城区", "餐厅", 0.9, "fallback");
        }
        if (q.contains("奎元馆")) {
            return GeocodeResponse.of(query, true, "杭州奎元馆", 30.2450, 120.1660,
                    "POINT(120.1660 30.2450)", "浙江", "杭州", "上城区", "餐厅", 0.9, "fallback");
        }
        if (q.contains("南京大牌档")) {
            return GeocodeResponse.of(query, true, "南京大牌档", 30.2510, 120.1550,
                    "POINT(120.1550 30.2510)", "浙江", "杭州", "西湖区", "餐厅", 0.85, "fallback");
        }
        if (q.contains("海底捞")) {
            return GeocodeResponse.of(query, true, "海底捞火锅", 31.2350, 121.4800,
                    "POINT(121.4800 31.2350)", "上海", "上海", "黄浦区", "餐厅", 0.8, "fallback");
        }
        if (q.contains("全聚德")) {
            return GeocodeResponse.of(query, true, "全聚德烤鸭", 39.9070, 116.3970,
                    "POINT(116.3970 39.9070)", "北京", "北京", "东城区", "餐厅", 0.8, "fallback");
        }
        if (q.contains("西贝") || q.contains("西贝莜面")) {
            return GeocodeResponse.of(query, true, "西贝莜面村", 39.9200, 116.4200,
                    "POINT(116.4200 39.9200)", "北京", "北京", "朝阳区", "餐厅", 0.8, "fallback");
        }
        if (q.contains("外婆家")) {
            return GeocodeResponse.of(query, true, "外婆家", 30.2500, 120.1700,
                    "POINT(120.1700 30.2500)", "浙江", "杭州", "上城区", "餐厅", 0.8, "fallback");
        }
        if (q.contains("喜茶")) {
            return GeocodeResponse.of(query, true, "喜茶", 31.2300, 121.4730,
                    "POINT(121.4730 31.2300)", "上海", "上海", "黄浦区", "饮品店", 0.8, "fallback");
        }

        // ========== 城市中心（仅 query 本身为城市名时命中，避免覆盖景点）==========
        return cityCenterFallback(query, city);
    }

    /** 城市名 -> 市中心坐标（API 失效时用于地图居中；仅 query 本身为城市名时命中） */
    private GeocodeResponse cityCenterFallback(String query, String city) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String name = query.trim();
        // 去掉常见后缀：成都市 -> 成都
        String bare = name.endsWith("市") && name.length() > 2
                ? name.substring(0, name.length() - 1)
                : name;

        java.util.LinkedHashMap<String, double[]> centers = new java.util.LinkedHashMap<>();
        centers.put("北京", new double[]{39.9042, 116.4074});
        centers.put("上海", new double[]{31.2304, 121.4737});
        centers.put("广州", new double[]{23.1291, 113.2644});
        centers.put("深圳", new double[]{22.5431, 114.0579});
        centers.put("成都", new double[]{30.5728, 104.0668});
        centers.put("杭州", new double[]{30.2741, 120.1551});
        centers.put("西安", new double[]{34.3416, 108.9398});
        centers.put("重庆", new double[]{29.5630, 106.5516});
        centers.put("苏州", new double[]{31.2989, 120.5853});
        centers.put("南京", new double[]{32.0603, 118.7969});
        centers.put("武汉", new double[]{30.5928, 114.3055});
        centers.put("长沙", new double[]{28.2282, 112.9388});
        centers.put("青岛", new double[]{36.0671, 120.3826});
        centers.put("厦门", new double[]{24.4798, 118.0894});
        centers.put("昆明", new double[]{25.0389, 102.7183});
        centers.put("大理", new double[]{25.6065, 100.2676});
        centers.put("丽江", new double[]{26.8550, 100.2270});
        centers.put("三亚", new double[]{18.2528, 109.5119});
        centers.put("哈尔滨", new double[]{45.8038, 126.5349});
        centers.put("沈阳", new double[]{41.8057, 123.4315});
        centers.put("天津", new double[]{39.3434, 117.3616});
        centers.put("郑州", new double[]{34.7466, 113.6254});
        centers.put("合肥", new double[]{31.8206, 117.2272});
        centers.put("福州", new double[]{26.0745, 119.2965});
        centers.put("南昌", new double[]{28.6820, 115.8579});
        centers.put("贵阳", new double[]{26.6470, 106.6302});
        centers.put("南宁", new double[]{22.8170, 108.3665});
        centers.put("宁波", new double[]{29.8683, 121.5440});
        centers.put("无锡", new double[]{31.4912, 120.3119});
        centers.put("桂林", new double[]{25.2736, 110.2900});
        centers.put("洛阳", new double[]{34.6197, 112.4540});
        centers.put("敦煌", new double[]{40.1424, 94.6618});
        centers.put("拉萨", new double[]{29.6520, 91.1721});
        centers.put("乌鲁木齐", new double[]{43.8256, 87.6168});

        // query 本身必须是已知城市名（去掉尾部“市”后完全匹配）
        if (!centers.containsKey(bare)) {
            return null;
        }
        double[] center = centers.get(bare);
        if (center == null) {
            return null;
        }
        double lat = center[0];
        double lng = center[1];
        String province = bare;
        String district = "";
        boolean municipality = bare.equals("北京") || bare.equals("上海")
                || bare.equals("天津") || bare.equals("重庆");
        if (municipality) {
            province = bare;
            district = "";
        }
        return GeocodeResponse.of(query, true, bare + "市", lat, lng,
                "POINT(" + lng + " " + lat + ")",
                province, bare, district, "城市", 0.85, "fallback");
    }

    private String hashQuery(String query, String city, String source) {
        String input = source + "|" + query + "|" + (city != null ? city : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private GeocodeResponse toGeocodeResponse(Object obj) {
        if (obj instanceof GeocodeResponse) return (GeocodeResponse) obj;
        if (obj instanceof java.util.Map) {
            try {
                return MAPPER.convertValue(obj, GeocodeResponse.class);
            } catch (Exception e) {
                log.warn("转换GeocodeResponse失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }
}