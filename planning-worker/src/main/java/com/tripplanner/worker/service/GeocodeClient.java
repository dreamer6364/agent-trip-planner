package com.tripplanner.worker.service;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.worker.solver.model.ActivityVar;
import com.tripplanner.worker.solver.model.TravelMatrix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan Service 客户端
 * 调用地理编码、路线规划、距离矩阵 API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeocodeClient {

    @Value("${plan-service.url:http://localhost:8083}")
    private String planServiceUrl;

    private final WebClient webClient;
    private final JsonUtils jsonUtils;

    // 本地缓存
    private final Map<String, ActivityVar> geocodeCache = new ConcurrentHashMap<>();
    private final Map<String, TravelMatrix> matrixCache = new ConcurrentHashMap<>();

    /**
     * 批量地理编码
     */
    public List<ActivityVar> batchGeocode(List<ActivityVar> activities) {
        return batchGeocode(activities, null);
    }

    /**
     * 批量地理编码（带城市，避免跨城同名景点编错）
     */
    public List<ActivityVar> batchGeocode(List<ActivityVar> activities, String city) {
        // 先检查缓存（key 含 city，避免跨城串味）
        String cityKey = city == null ? "" : city;
        List<ActivityVar> toGeocode = activities.stream()
                .filter(a -> a.getLat() == 0 && a.getLng() == 0)
                .filter(a -> !geocodeCache.containsKey(cityKey + "|" + a.getName()))
                .toList();

        if (!toGeocode.isEmpty()) {
            try {
                java.util.HashMap<String, Object> request = new java.util.HashMap<>();
                request.put("queries", toGeocode.stream().map(ActivityVar::getName).toList());
                if (city != null && !city.isBlank()) {
                    request.put("city", city);
                }

                Map<String, Object> response = webClient.post()
                        .uri(planServiceUrl + "/api/plan/batch-geocode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response != null) {
                    Object dataObj = response.get("data");
                    Map<String, Object> dataMap = dataObj instanceof Map ? (Map<String, Object>) dataObj : response;
                    List<Map<String, Object>> results = (List<Map<String, Object>>) dataMap.get("results");

                    for (int i = 0; i < results.size() && i < toGeocode.size(); i++) {
                        Map<String, Object> r = results.get(i);
                        ActivityVar act = toGeocode.get(i);
                        if (Boolean.TRUE.equals(r.get("success"))) {
                            act.setLat(((Number) r.get("lat")).doubleValue());
                            act.setLng(((Number) r.get("lng")).doubleValue());
                            geocodeCache.put(cityKey + "|" + act.getName(), act);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("批量地理编码失败，使用本地估算: {}", e.getMessage());
                // 回退：使用默认坐标
            }
        }

        // 合并结果
        return activities.stream().map(act -> {
            if (act.getLat() == 0 && act.getLng() == 0) {
                ActivityVar cached = geocodeCache.get(cityKey + "|" + act.getName());
                if (cached != null) {
                    return act.toBuilder().lat(cached.getLat()).lng(cached.getLng()).build();
                }
            }
            return act;
        }).toList();
    }

    /**
     * 获取距离矩阵
     */
    public TravelMatrix getDistanceMatrix(List<ActivityVar> activities, String city) {
        String cacheKey = buildMatrixCacheKey(activities);
        TravelMatrix cached = matrixCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) (List<?>) activities.stream()
                    .<Map<String, Object>>map(a -> Map.of("lat", a.getLat(), "lng", a.getLng()))
                    .toList();

            java.util.HashMap<String, Object> request = new java.util.HashMap<>();
            request.put("points", points);
            request.put("mode", "drive");
            if (city != null && !city.isBlank()) {
                request.put("city", city);
            }
            
            Map<String, Object> response = webClient.post()
                    .uri(planServiceUrl + "/api/plan/map/distance-matrix")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            TravelMatrix matrix = parseMatrixResponse(response, activities.size());
            matrixCache.put(cacheKey, matrix);
            return matrix;
        } catch (Exception e) {
            log.warn("获取距离矩阵失败，使用直线距离估算: {}", e.getMessage());
            return buildFallbackMatrix(activities);
        }
    }

    /**
     * 获取距离矩阵 (默认模式)
     */
    public TravelMatrix getDistanceMatrix(List<ActivityVar> activities) {
        return getDistanceMatrix(activities, null);
    }

    /**
     * 丰富路线信息 - 调用高德 API 获取活动间详细路线
     * 返回每个活动附带到下一活动的路线详情
     */
    public List<ActivityVar> enrichRoutes(List<ActivityVar> activities, TravelMatrix travelMatrix) {
        if (activities == null || activities.size() < 2) return activities;

        List<ActivityVar> result = new java.util.ArrayList<>();
        for (int i = 0; i < activities.size(); i++) {
            ActivityVar act = activities.get(i);
            if (act.isTransit() || act.isBuffer()) {
                result.add(act);
                continue;
            }

            // Find next non-transit activity for route info
            ActivityVar next = null;
            for (int j = i + 1; j < activities.size(); j++) {
                if (!activities.get(j).isTransit() && !activities.get(j).isBuffer()) {
                    next = activities.get(j);
                    break;
                }
            }

            if (next != null && act.getLat() != 0 && act.getLng() != 0
                    && next.getLat() != 0 && next.getLng() != 0) {
                try {
                    String mode = act.getTransportMode() != null ? act.getTransportMode() : "transit";
                    Map<String, Object> routeResp = webClient.post()
                            .uri(planServiceUrl + "/api/plan/map/route")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "originLat", act.getLat(),
                                    "originLng", act.getLng(),
                                    "destLat", next.getLat(),
                                    "destLng", next.getLng(),
                                    "mode", mode
                            ))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block();

                    if (routeResp != null) {
                        Object dataObj = routeResp.get("data");
                        Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : routeResp;
                        Boolean success = (Boolean) data.get("success");
                        if (Boolean.TRUE.equals(success)) {
                            Number dur = (Number) data.get("duration");
                            Number dist = (Number) data.get("distance");
                            String polyline = (String) data.get("polyline");
                            act = act.toBuilder()
                                    .travelDurationMin(dur != null ? (int) Math.ceil(dur.intValue() / 60.0) : 0)
                                    .travelDistanceM(dist != null ? dist.intValue() : 0)
                                    .routePolyline(polyline)
                                    .build();
                        }
                    }
                } catch (Exception e) {
                    log.debug("获取路线详情失败: {} -> {}: {}", act.getName(), next.getName(), e.getMessage());
                }
            }
            result.add(act);
        }
        return result;
    }

    private TravelMatrix parseMatrixResponse(Map<String, Object> response, int size) {
        if (response == null) {
            return TravelMatrix.createDefault(size, null, Map.of(
                    "walk", 0, "transit", 1, "drive", 2, "bike", 3
            ));
        }

        // ApiResponse wraps data in "data" field
        Object dataObj = response.get("data");
        Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : response;

        // DistanceMatrixResponse has "distances" (double[][]) and "durations" (int[][])
        double[][] distances = null;
        int[][] durations = null;

        Object distObj = data.get("distances");
        if (distObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<List<Number>> distList = (List<List<Number>>) distObj;
            distances = new double[distList.size()][];
            for (int i = 0; i < distList.size(); i++) {
                distances[i] = distList.get(i).stream().mapToDouble(Number::doubleValue).toArray();
            }
        }

        Object durObj = data.get("durations");
        if (durObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<List<Number>> durList = (List<List<Number>>) durObj;
            durations = new int[durList.size()][];
            for (int i = 0; i < durList.size(); i++) {
                durations[i] = durList.get(i).stream().mapToInt(Number::intValue).toArray();
            }
        }

        // Build TravelMatrix with real data
        Map<String, Integer> modeIndex = Map.of("walk", 0, "transit", 1, "drive", 2, "bike", 3);
        int modeCount = modeIndex.size();
        int[][][] times = new int[size][size][modeCount];

        if (distances != null && durations != null) {
            // Use real durations from Amap (duration is in seconds, convert to minutes)
            for (int i = 0; i < size && i < durations.length; i++) {
                for (int j = 0; j < size && j < durations[i].length; j++) {
                    int durMin = (int) Math.ceil(durations[i][j] / 60.0);
                    for (Map.Entry<String, Integer> entry : modeIndex.entrySet()) {
                        times[i][j][entry.getValue()] = durMin;
                    }
                }
            }
            log.info("使用高德真实距离矩阵: {}x{}", distances.length, distances[0].length);
        } else if (distances != null) {
            // Only distances available, estimate time
            for (int i = 0; i < size && i < distances.length; i++) {
                for (int j = 0; j < size && j < distances[i].length; j++) {
                    for (Map.Entry<String, Integer> entry : modeIndex.entrySet()) {
                        times[i][j][entry.getValue()] = estimateTime(distances[i][j], entry.getKey());
                    }
                }
            }
            log.info("使用高德距离矩阵（时间估算）: {}x{}", distances.length, distances[0].length);
        } else {
            log.warn("距离矩阵数据为空，使用默认估算");
            return TravelMatrix.createDefault(size, null, modeIndex);
        }

        // Convert distances to int meters
        int[][] distInt = new int[size][size];
        for (int i = 0; i < size && i < distances.length; i++) {
            for (int j = 0; j < size && j < distances[i].length; j++) {
                distInt[i][j] = (int) distances[i][j];
            }
        }

        return TravelMatrix.builder()
                .size(size)
                .travelTimeMinutes(times)
                .distanceMeters(distInt)
                .modeIndex(modeIndex)
                .build();
    }

    private int estimateTime(double distanceMeters, String mode) {
        double speedMps = switch (mode.toLowerCase()) {
            case "walk" -> 1.4;
            case "bike" -> 5.0;
            case "transit" -> 8.3;
            case "drive" -> 13.9;
            default -> 5.0;
        };
        return (int) Math.ceil(distanceMeters / speedMps / 60.0);
    }

    private TravelMatrix buildFallbackMatrix(List<ActivityVar> activities) {
        int n = activities.size();
        int[][] distances = new int[n][n];
        
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    distances[i][j] = (int) com.tripplanner.common.util.GeoUtils.distance(
                            activities.get(i).getLat(), activities.get(i).getLng(),
                            activities.get(j).getLat(), activities.get(j).getLng()
                    );
                }
            }
        }
        
        return TravelMatrix.createDefault(n, distances, Map.of(
                "walk", 0, "transit", 1, "drive", 2, "bike", 3
        ));
    }

    private String buildMatrixCacheKey(List<ActivityVar> activities) {
        StringBuilder sb = new StringBuilder();
        for (ActivityVar a : activities) {
            sb.append(a.getId()).append(":");
        }
        return sb.toString();
    }
}