package com.tripplanner.plan.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.GeoUtils;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.plan.config.MapApiConfig;
import com.tripplanner.plan.dto.response.RouteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 路线规划服务
 * 支持步行、公共交通、驾车、骑行
 * 缓存策略：Redis 7 天
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

    private final MapApiConfig mapApiConfig;
    private final WebClient amapWebClient;
    private final WebClient baiduWebClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JsonUtils jsonUtils;

    private static final String CACHE_PREFIX = "route:";
    private static final long CACHE_TTL_DAYS = 7;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 单条路线规划
     */
    public RouteResponse route(double originLat, double originLng, double destLat, double destLng, String mode) {
        return route(originLat, originLng, destLat, destLng, mode, null);
    }

    /**
     * 单条路线规划 (带城市信息，用于公交规划)
     */
    public RouteResponse route(double originLat, double originLng, double destLat, double destLng, String mode, String city) {
        String cacheKey = buildCacheKey(originLat, originLng, destLat, destLng, mode);
        
        Object cachedObj = redisTemplate.opsForValue().get(cacheKey);
        RouteResponse cached = toRouteResponse(cachedObj);
        if (cached != null) {
            log.debug("路线缓存命中: {} -> {}", cacheKey, mode);
            return cached;
        }

        RouteResponse response;
        if ("walk".equalsIgnoreCase(mode)) {
            response = callAmapWalk(originLat, originLng, destLat, destLng);
        } else if ("transit".equalsIgnoreCase(mode)) {
            response = callAmapTransit(originLat, originLng, destLat, destLng, city);
        } else if ("drive".equalsIgnoreCase(mode)) {
            response = callAmapDrive(originLat, originLng, destLat, destLng);
        } else if ("bike".equalsIgnoreCase(mode)) {
            response = callAmapBicycle(originLat, originLng, destLat, destLng);
        } else {
            response = callAmapDrive(originLat, originLng, destLat, destLng); // 默认驾车
        }

        if (response.isSuccess()) {
            redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL_DAYS, TimeUnit.DAYS);
        }
        return response;
    }

    /**
     * 批量路线规划 (用于距离矩阵)
     * <p>
     * 串行执行：路径规划接口同样有 QPS 限制，并发会整批超限导致
     * 路程时间拿不到真实数据（页面显示 0 分钟）。
     */
    public List<RouteResponse> batchRoute(List<RouteRequest> requests) {
        List<RouteResponse> results = new java.util.ArrayList<>(requests.size());
        for (RouteRequest req : requests) {
            results.add(route(req.originLat(), req.originLng(), req.destLat(), req.destLng(), req.mode(), req.city()));
        }
        return results;
    }

    /** 高德路径规划接口全局限速（免费版 QPS 有限） */
    private static final long AMAP_MIN_INTERVAL_MS = 200;

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
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 距离矩阵计算
     */
    public DistanceMatrixResponse distanceMatrix(List<Point> points, String mode) {
        return distanceMatrix(points, mode, null);
    }

    /**
     * 距离矩阵计算 (带城市信息)
     */
    public DistanceMatrixResponse distanceMatrix(List<Point> points, String mode, String city) {
        int n = points.size();
        double[][] distances = new double[n][n];
        int[][] durations = new int[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    distances[i][j] = 0;
                    durations[i][j] = 0;
                } else {
                    RouteResponse r = route(points.get(i).lat(), points.get(i).lng(), points.get(j).lat(), points.get(j).lng(), mode, city);
                    distances[i][j] = r.isSuccess() ? r.getDistance() : GeoUtils.distance(points.get(i).lat(), points.get(i).lng(), points.get(j).lat(), points.get(j).lng());
                    durations[i][j] = r.isSuccess() ? r.getDuration() : (int) (distances[i][j] / getSpeed(mode));
                }
            }
        }

        return new DistanceMatrixResponse(distances, durations);
    }

    private int getSpeed(String mode) {
        return switch (mode.toLowerCase()) {
            case "walk" -> 50;      // m/min ~ 3km/h
            case "bike" -> 250;     // m/min ~ 15km/h
            case "transit" -> 500;  // m/min ~ 30km/h (含等待)
            case "drive" -> 800;    // m/min ~ 48km/h
            default -> 500;
        };
    }

    private RouteResponse callAmapWalk(double oLat, double oLng, double dLat, double dLng) {
        try {
            String url = mapApiConfig.getAmapWalkUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&origin=" + oLng + "," + oLat
                    + "&destination=" + dLng + "," + dLat
                    + "&output=JSON";
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseAmapRouteResponse(response, "walk");
        } catch (Exception e) {
            log.error("高德步行规划失败: {}", e.getMessage());
            return RouteResponse.builder().success(false).errorMessage(e.getMessage()).source("amap").build();
        }
    }

    private RouteResponse callAmapTransit(double oLat, double oLng, double dLat, double dLng, String city) {
        if (city == null || city.isBlank()) {
            // 公交接口强制 city 参数；缺城市时用驾车兜底（禁止默认北京导致跨城错路由）
            log.warn("公交规划缺少城市参数，回退驾车估算: ({},{})->({},{})", oLat, oLng, dLat, dLng);
            return callAmapDrive(oLat, oLng, dLat, dLng);
        }
        try {
            String url = mapApiConfig.getAmapTransitUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&origin=" + oLng + "," + oLat
                    + "&destination=" + dLng + "," + dLat
                    + "&city=" + java.net.URLEncoder.encode(AmapCityAlias.toAmapCity(city), java.nio.charset.StandardCharsets.UTF_8)
                    + "&output=JSON";
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseAmapTransitResponse(response);
        } catch (Exception e) {
            log.error("高德公交规划失败: {}", e.getMessage());
            return RouteResponse.builder().success(false).errorMessage(e.getMessage()).source("amap").build();
        }
    }

    private RouteResponse callAmapDrive(double oLat, double oLng, double dLat, double dLng) {
        try {
            String url = mapApiConfig.getAmapRouteUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&origin=" + oLng + "," + oLat
                    + "&destination=" + dLng + "," + dLat
                    + "&output=JSON&extensions=base";
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseAmapRouteResponse(response, "drive");
        } catch (Exception e) {
            log.error("高德驾车规划失败: {}", e.getMessage());
            return RouteResponse.builder().success(false).errorMessage(e.getMessage()).source("amap").build();
        }
    }

    private RouteResponse callAmapBicycle(double oLat, double oLng, double dLat, double dLng) {
        try {
            String url = mapApiConfig.getAmapBicycleUrl()
                    + "?key=" + mapApiConfig.getAmapApiKey()
                    + "&origin=" + oLng + "," + oLat
                    + "&destination=" + dLng + "," + dLat;
            throttleAmap();
            Map<String, Object> response = amapWebClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return parseAmapRouteResponse(response, "bike");
        } catch (Exception e) {
            log.error("高德骑行规划失败: {}", e.getMessage());
            return RouteResponse.builder().success(false).errorMessage(e.getMessage()).source("amap").build();
        }
    }

    private RouteResponse parseAmapRouteResponse(Map<String, Object> response, String mode) {
        if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
            return RouteResponse.builder()
                    .success(false)
                    .errorMessage("高德返回错误: " + response.get("info"))
                    .mode(mode)
                    .source("amap")
                    .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) response.get("route");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> paths = (List<Map<String, Object>>) route.get("paths");
        Map<String, Object> path = paths.get(0);
        List<RouteResponse.RouteStep> steps = parseAmapSteps(path);
        String polyline = firstNonBlank(
                asString(path.get("polyline")),
                joinStepPolylines(steps)
        );

        return RouteResponse.builder()
                .success(true)
                .distance(parseNumber(path.get("distance")))
                .duration((int) parseNumber(path.get("duration")))
                .cost(path.containsKey("cost") ? parseNumber(path.get("cost")) : null)
                .polyline(polyline)
                .steps(steps)
                .mode(mode)
                .source("amap")
                .build();
    }

    private String asString(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /** 将分段 polyline 拼成整条路径（去掉相邻段重复端点） */
    private String joinStepPolylines(List<RouteResponse.RouteStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String prevLast = null;
        for (RouteResponse.RouteStep step : steps) {
            String p = step.getPolyline();
            if (p == null || p.isBlank()) continue;
            String[] pts = p.split(";");
            for (int i = 0; i < pts.length; i++) {
                String pt = pts[i].trim();
                if (pt.isEmpty()) continue;
                if (prevLast != null && pt.equals(prevLast)) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(pt);
                prevLast = pt;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private double parseNumber(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) return Double.parseDouble(s);
        return 0;
    }

    private RouteResponse parseAmapTransitResponse(Map<String, Object> response) {
        if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
            return RouteResponse.builder()
                    .success(false)
                    .errorMessage("高德公交返回错误: " + response.get("info"))
                    .mode("transit")
                    .source("amap")
                    .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) response.get("route");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transits = (List<Map<String, Object>>) route.get("transits");
        Map<String, Object> transit = transits.get(0);

        List<RouteResponse.RouteStep> steps = parseTransitSteps(transit);
        String polyline = firstNonBlank(
                asString(transit.get("polyline")),
                joinStepPolylines(steps)
        );
        return RouteResponse.builder()
                .success(true)
                .distance(parseNumber(transit.get("distance")))
                .duration((int) parseNumber(transit.get("duration")))
                .cost(transit.containsKey("cost") ? parseNumber(transit.get("cost")) : null)
                .polyline(polyline)
                .steps(steps)
                .mode("transit")
                .source("amap")
                .build();
    }

    private List<RouteResponse.RouteStep> parseAmapSteps(Map<String, Object> path) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) path.get("steps");
        if (steps == null) return List.of();
        return steps.stream().map(step -> RouteResponse.RouteStep.builder()
                .instruction((String) step.get("instruction"))
                .distance(parseNumber(step.get("distance")))
                .duration((int) parseNumber(step.get("duration")))
                .polyline((String) step.get("polyline"))
                .roadName((String) step.get("road_name"))
                .transportMode((String) step.get("transport_mode"))
                .build()).toList();
    }

    private List<RouteResponse.RouteStep> parseTransitSteps(Map<String, Object> transit) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) transit.get("segments");
        if (segments == null) return List.of();
        return segments.stream().flatMap(segment -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> segSteps = (List<Map<String, Object>>) segment.get("steps");
            if (segSteps == null) return java.util.stream.Stream.empty();
            return segSteps.stream().map(step -> RouteResponse.RouteStep.builder()
                    .instruction((String) step.get("instruction"))
                    .distance(parseNumber(step.get("distance")))
                    .duration((int) parseNumber(step.get("duration")))
                    .polyline((String) step.get("polyline"))
                    .transportMode((String) step.get("transport_mode"))
                    .transitInfo(step.containsKey("buslines") ? jsonUtils.toJson(step.get("buslines")) : null)
                    .build());
        }).toList();
    }

    private String buildCacheKey(double oLat, double oLng, double dLat, double dLng, String mode) {
        return String.format("%s%.6f,%.6f->%.6f,%.6f:%s", CACHE_PREFIX, oLat, oLng, dLat, dLng, mode);
    }

    // Record for batch route requests
    public record RouteRequest(double originLat, double originLng, double destLat, double destLng, String mode, String city) {}

    // Record for point
    public record Point(double lat, double lng) {}

    // Distance Matrix Response
    public record DistanceMatrixResponse(double[][] distances, int[][] durations) {}

    private RouteResponse toRouteResponse(Object obj) {
        if (obj instanceof RouteResponse) return (RouteResponse) obj;
        if (obj instanceof java.util.Map) {
            try {
                return MAPPER.convertValue(obj, RouteResponse.class);
            } catch (Exception e) {
                log.warn("转换RouteResponse失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }
}