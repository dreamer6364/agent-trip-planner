package com.tripplanner.trip.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.trip.entity.Activity;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.repository.ActivityRepository;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 内部 API (供 Planning Worker 调用)
 */
@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalController {

    private final TripRepository tripRepository;
    private final TripVersionRepository versionRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/trips/{tripId}")
    public ResponseEntity<Map<String, Object>> getTrip(@PathVariable String tripId) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "id", trip.getId(),
                "title", trip.getTitle() != null ? trip.getTitle() : "",
                "timeStart", trip.getTimeStart() != null ? trip.getTimeStart().toString() : "",
                "timeEnd", trip.getTimeEnd() != null ? trip.getTimeEnd().toString() : "",
                "status", trip.getStatus() != null ? trip.getStatus() : "",
                "rawInput", trip.getRawInput() != null ? trip.getRawInput() : ""
        ));
    }

    @GetMapping("/trips/{tripId}/versions/latest")
    public ResponseEntity<Map<String, Object>> getLatestVersion(@PathVariable String tripId) {
        TripVersion latest = versionRepository.findLatestByTripId(tripId);
        if (latest == null) {
            return ResponseEntity.ok(Map.of("versionNum", 0));
        }
        return ResponseEntity.ok(Map.of(
                "versionId", latest.getId(),
                "versionNum", latest.getVersionNum()
        ));
    }

    /**
     * 版本详情（供 Planning Worker 增量重规划加载基础版本）
     * 返回活动/路线/统计 JSON 及行程基础信息（rawInput/parsedInput/时间范围）
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/trips/{tripId}/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> getVersionDetail(
            @PathVariable String tripId, @PathVariable String versionId) {
        Trip trip = tripRepository.selectById(tripId);
        TripVersion version = versionRepository.findById(versionId);
        if (trip == null || version == null || !version.getTripId().equals(tripId)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new HashMap<>();
        out.put("versionId", version.getId());
        out.put("versionNum", version.getVersionNum());
        out.put("parentVersionId", version.getParentVersionId() != null ? version.getParentVersionId() : "");
        out.put("status", version.getStatus() != null ? version.getStatus() : "");
        out.put("activities", version.getActivities() != null ? version.getActivities() : "[]");
        out.put("routes", version.getRoutes() != null ? version.getRoutes() : "[]");
        out.put("stats", version.getStats() != null ? version.getStats() : "{}");
        out.put("feedback", version.getFeedback() != null ? version.getFeedback() : "");
        out.put("rawInput", trip.getRawInput() != null ? trip.getRawInput() : "");
        out.put("parsedInput", trip.getParsedInput() != null ? trip.getParsedInput() : "{}");
        out.put("timeStart", trip.getTimeStart() != null ? trip.getTimeStart().toString() : "");
        out.put("timeEnd", trip.getTimeEnd() != null ? trip.getTimeEnd().toString() : "");
        out.put("transportMode", trip.getTransportMode() != null ? trip.getTransportMode() : "mixed");
        return ResponseEntity.ok(out);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/trips/{tripId}/versions")
    public ResponseEntity<Map<String, Object>> createVersion(
            @PathVariable String tripId,
            @RequestBody Map<String, Object> body) {

        try {
            String baseVersionId = (String) body.get("baseVersionId");
            String status = (String) body.getOrDefault("status", "completed");
            String targetVersionId = (String) body.get("targetVersionId");
            List<Map<String, Object>> activityMaps = (List<Map<String, Object>>) body.get("activities");

            // 反馈重规划：原地更新 createVersionFromFeedback 预建的目标版本（避免重复建版）
            if (targetVersionId != null && !targetVersionId.isBlank()) {
                TripVersion target = versionRepository.findById(targetVersionId);
                if (target == null || !target.getTripId().equals(tripId)) {
                    return ResponseEntity.notFound().build();
                }
                target.setActivities(objectMapper.writeValueAsString(body.get("activities")));
                target.setRoutes(objectMapper.writeValueAsString(body.get("routes")));
                target.setConflicts(objectMapper.writeValueAsString(body.get("conflicts")));
                target.setStats(objectMapper.writeValueAsString(body.get("stats")));
                target.setSolverMeta(objectMapper.writeValueAsString(body.get("solverMeta")));
                target.setStatus(status);
                versionRepository.updateById(target);

                activityRepository.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Activity>()
                        .eq(Activity::getVersionId, targetVersionId));
                insertActivityRows(targetVersionId, activityMaps);

                Trip trip = tripRepository.selectById(tripId);
                if (trip != null) {
                    trip.setCurrentVersionId(targetVersionId);
                    if ("completed".equals(status)) {
                        trip.setStatus("completed");
                    }
                    tripRepository.updateById(trip);
                }
                log.info("内部API: 原地更新目标版本成功 tripId={}, versionId={}", tripId, targetVersionId);
                return ResponseEntity.ok(Map.of("versionId", targetVersionId, "versionNum", target.getVersionNum()));
            }

            TripVersion latest = versionRepository.findLatestByTripId(tripId);
            int nextNum = (latest != null ? latest.getVersionNum() : 0) + 1;

            TripVersion version = new TripVersion();
            version.setId(UUID.randomUUID().toString().replace("-", ""));
            version.setTripId(tripId);
            version.setVersionNum(nextNum);
            version.setParentVersionId(baseVersionId);
            version.setActivities(objectMapper.writeValueAsString(body.get("activities")));
            version.setRoutes(objectMapper.writeValueAsString(body.get("routes")));
            version.setConflicts(objectMapper.writeValueAsString(body.get("conflicts")));
            version.setStats(objectMapper.writeValueAsString(body.get("stats")));
            version.setSolverMeta(objectMapper.writeValueAsString(body.get("solverMeta")));
            version.setStatus(status);
            versionRepository.insert(version);

            insertActivityRows(version.getId(), activityMaps);

            Trip trip = tripRepository.selectById(tripId);
            if (trip != null) {
                trip.setCurrentVersionId(version.getId());
                if ("completed".equals(status)) {
                    trip.setStatus("planned");
                }
                tripRepository.updateById(trip);
            }

            log.info("内部API: 创建版本成功 tripId={}, versionNum={}", tripId, nextNum);
            return ResponseEntity.ok(Map.of("versionId", version.getId(), "versionNum", nextNum));

        } catch (Exception e) {
            log.error("内部API: 创建版本失败 tripId={}", tripId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 活动列表 → activity 行（版本级重建时先清后插） */
    @SuppressWarnings("unchecked")
    private void insertActivityRows(String versionId, List<Map<String, Object>> activityMaps) {
        if (activityMaps == null) {
            return;
        }
        for (Map<String, Object> am : activityMaps) {
            Activity act = new Activity();
            act.setId(UUID.randomUUID().toString().replace("-", ""));
            act.setVersionId(versionId);
            act.setSeq(am.get("seq") != null ? ((Number) am.get("seq")).intValue() : 0);
            act.setPoiId((String) am.get("poiId"));
            act.setPoiName(am.get("poiName") != null ? (String) am.get("poiName") : "Unknown");
            act.setPoiCategory((String) am.get("poiCategory"));
            act.setPoiLocation(am.get("poiLocation") != null ? (String) am.get("poiLocation")
                    : (am.get("lat") != null && am.get("lng") != null
                            ? am.get("lat") + "," + am.get("lng") : null));
            act.setPoiAddress(am.get("poiAddress") != null ? (String) am.get("poiAddress") : "");
            act.setActivityType(am.get("activityType") != null ? (String) am.get("activityType") : "visit");
            act.setPriority(am.get("priority") != null ? (String) am.get("priority") : "recommended");
            act.setDurationMin(numVal(am.get("durationMin")) != null ? numVal(am.get("durationMin")) : 60);
            act.setMinDuration(numVal(am.get("minDuration")));
            act.setMaxDuration(numVal(am.get("maxDuration")));
            act.setStatus((String) am.get("status"));
            act.setTransportMode(am.get("transportMode") != null ? (String) am.get("transportMode") : "transit");
            act.setTravelDurationMin(numVal(am.get("travelDurationMin")));
            if (am.get("scheduledStart") != null) {
                try { act.setScheduledStart(LocalDateTime.parse((String) am.get("scheduledStart"))); } catch (Exception ignored) {}
            }
            if (am.get("scheduledEnd") != null) {
                try { act.setScheduledEnd(LocalDateTime.parse((String) am.get("scheduledEnd"))); } catch (Exception ignored) {}
            }
            activityRepository.insert(act);
        }
    }

    @PatchMapping("/planning-tasks/{taskId}/status")
    public ResponseEntity<Void> updateTaskStatus(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> body) {
        log.info("内部API: 更新任务状态 taskId={}", taskId);
        return ResponseEntity.ok().build();
    }

    private Integer numVal(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return null; }
    }
}
