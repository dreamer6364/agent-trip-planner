package com.tripplanner.worker.service;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.worker.solver.model.ActivityVar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 结果持久化服务
 * 调用 TripService API 保存规划结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultPersistService {

    @Value("${trip-service.url:http://localhost:8082}")
    private String tripServiceUrl;

    private final WebClient webClient;
    private final JsonUtils jsonUtils;

    /**
     * 持久化规划结果（新建版本）
     */
    public String persistResult(String tripId, String baseVersionId, String taskId, 
                                List<ActivityVar> activities, Map<String, Object> solverStats) {
        return doPersist(tripId, baseVersionId, null, taskId, activities, solverStats);
    }

    /**
     * 持久化规划结果到指定目标版本（反馈重规划原地更新，不重复建版）
     *
     * @param targetVersionId 目标版本 ID（createVersionFromFeedback 预建的版本）
     */
    public String persistResultToTarget(String tripId, String targetVersionId, String taskId,
                                        List<ActivityVar> activities, Map<String, Object> solverStats) {
        return doPersist(tripId, targetVersionId, targetVersionId, taskId, activities, solverStats);
    }

    private String doPersist(String tripId, String baseVersionId, String targetVersionId, String taskId,
                             List<ActivityVar> activities, Map<String, Object> solverStats) {
        try {
            // 行程起始时刻 → day1 00:00 锚点（求解窗口自 day1 00:00 起算，保持 HH:mm 往返一致）
            LocalDateTime tripStart = getTripStartTime(tripId).toLocalDate().atStartOfDay();

            // 构建版本数据
            java.util.HashMap<String, Object> versionData = new java.util.HashMap<>();
            versionData.put("tripId", tripId);
            versionData.put("baseVersionId", baseVersionId);
            if (targetVersionId != null) {
                versionData.put("targetVersionId", targetVersionId);
            }
            versionData.put("activities", activities.stream().map(a -> toActivityMap(a, tripStart)).toList());
            versionData.put("routes", buildRoutes(activities));
            versionData.put("conflicts", List.of());
            versionData.put("stats", buildStats(activities));
            versionData.put("solverMeta", Map.of(
                    "solveTimeMs", solverStats.getOrDefault("solveTimeMs", 0),
                    "iterations", solverStats.getOrDefault("iterations", 0),
                    "objectiveValue", solverStats.getOrDefault("objectiveValue", 0),
                    "solverStatus", solverStats.getOrDefault("status", "COMPLETED"),
                    "algorithm", solverStats.getOrDefault("algorithm", "LLM")
            ));
            versionData.put("status", "completed");

            // 调用 TripService 内部接口保存
            Map<String, Object> response = webClient.post()
                    .uri(tripServiceUrl + "/api/internal/trips/{tripId}/versions", tripId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(versionData)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String resultVersionId = (String) response.get("versionId");
            log.info("规划结果持久化成功: tripId={}, versionId={}, target={}", tripId, resultVersionId, targetVersionId != null);

            return resultVersionId;
        } catch (Exception e) {
            log.error("持久化规划结果失败: tripId={}, error={}", tripId, e.getMessage(), e);
            throw new RuntimeException("持久化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新任务状态 (供 PlanService 查询)
     */
    public void updateTaskStatus(String taskId, String status, int progress, String stage, String error) {
        try {
            Map<String, Object> update = Map.of(
                    "status", status,
                    "progress", progress,
                    "stage", stage,
                    "errorMessage", error
            );
            
            webClient.patch()
                    .uri(tripServiceUrl + "/api/internal/planning-tasks/{taskId}/status", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(update)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.warn("更新任务状态失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private Map<String, Object> toActivityMap(ActivityVar act, LocalDateTime tripStart) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", act.getId());
        map.put("seq", act.getSeq());
        map.put("poiId", act.getPoiId());
        map.put("poiName", act.getName());
        map.put("poiCategory", act.getType());
        map.put("poiLocation", act.getLat() + "," + act.getLng());
        map.put("poiAddress", act.getPoiAddress() != null ? act.getPoiAddress() : "");
        // 前端 activityCoord 只读 lat/lng 数值键
        map.put("lat", act.getLat());
        map.put("lng", act.getLng());
        map.put("rating", act.getRating());
        map.put("cost", act.getCost());
        map.put("activityType", act.getType() != null ? act.getType() : "visit");
        map.put("priority", act.getPriority() != null ? act.getPriority() : "recommended");
        map.put("scheduledStart", toIsoTime(act.getEarliestMin(), tripStart));
        map.put("scheduledEnd", toIsoTime(act.getLatestMin(), tripStart));
        map.put("durationMin", act.getLatestMin() - act.getEarliestMin());
        map.put("minDuration", act.getMinDurationMin());
        map.put("maxDuration", act.getMaxDurationMin());
        map.put("status", act.getEarliestMin() >= 0 ? "scheduled" : "removed");
        map.put("transportMode", act.getTransportMode() != null ? act.getTransportMode() : "transit");
        map.put("travelDurationMin", act.getTravelDurationMin());
        map.put("travelDistanceM", act.getTravelDistanceM());
        map.put("travelDistanceKm", act.getTravelDistanceM() > 0 ? act.getTravelDistanceM() / 1000.0 : 0.0);
        map.put("routePolyline", act.getRoutePolyline());
        return map;
    }

    private List<Map<String, Object>> buildRoutes(List<ActivityVar> activities) {
        // 简化：返回空路线，实际应包含详细路径
        return List.of();
    }

    private Map<String, Object> buildStats(List<ActivityVar> activities) {
        List<ActivityVar> scheduled = activities.stream()
                .filter(a -> a.getEarliestMin() >= 0)
                .toList();

        long totalDuration = scheduled.stream()
                .mapToLong(a -> a.getLatestMin() - a.getEarliestMin())
                .sum();

        long transitDuration = scheduled.stream()
                .filter(a -> "transit".equals(a.getType()))
                .mapToLong(a -> a.getLatestMin() - a.getEarliestMin())
                .sum();

        long visitDuration = scheduled.stream()
                .filter(a -> "visit".equals(a.getType()))
                .mapToLong(a -> a.getLatestMin() - a.getEarliestMin())
                .sum();

        long mealDuration = scheduled.stream()
                .filter(a -> "meal".equals(a.getType()))
                .mapToLong(a -> a.getLatestMin() - a.getEarliestMin())
                .sum();

        return Map.of(
                "totalDurationMin", totalDuration,
                "transitDurationMin", transitDuration,
                "visitDurationMin", visitDuration,
                "mealDurationMin", mealDuration,
                "bufferDurationMin", totalDuration - transitDuration - visitDuration - mealDuration,
                "placeCount", Map.of(
                        "must", scheduled.stream().filter(a -> "must".equals(a.getPriority())).count(),
                        "recommended", scheduled.stream().filter(a -> "recommended".equals(a.getPriority())).count(),
                        "optional", scheduled.stream().filter(a -> "optional".equals(a.getPriority())).count()
                )
        );
    }

    private String toIsoTime(int minutesFromStart, LocalDateTime tripStart) {
        if (tripStart == null) {
            tripStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        }
        return tripStart.plusMinutes(minutesFromStart).toString();
    }

    private LocalDateTime getTripStartTime(String tripId) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri(tripServiceUrl + "/api/internal/trips/{tripId}", tripId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            
            if (response != null && response.get("timeStart") != null) {
                return LocalDateTime.parse((String) response.get("timeStart"));
            }
        } catch (Exception e) {
            log.warn("获取行程开始时间失败: {}", e.getMessage());
        }
        return LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
    }
}