package com.tripplanner.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.model.ParsedInput;
import com.tripplanner.common.util.CityOwnershipUtils;
import com.tripplanner.worker.dto.SolverResult;
import com.tripplanner.worker.solver.HeuristicSolver;
import com.tripplanner.worker.solver.model.ActivityVar;
import com.tripplanner.worker.solver.model.PlanningProblem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规划编排器
 * 协调不同求解器、选择算法、处理降级
 * 注意: 目前仅使用启发式求解器，OR-Tools CP-SAT 暂未集成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanningOrchestrator {

    private final HeuristicSolver heuristicSolver;
    private final TripServiceClient tripServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * 使用启发式求解
     */
    public SolverResult solveWithHeuristic(PlanningProblem problem) {
        log.debug("使用启发式求解: activities={}", problem.getActivities().size());
        return heuristicSolver.solve(problem);
    }

    /**
     * 自动选择求解器 (目前始终使用启发式)
     */
    public SolverResult solve(PlanningProblem problem) {
        return solveWithHeuristic(problem);
    }

    /**
     * 加载基础版本问题 (用于增量重规划)
     *
     * <p>v1.15.0 修复：原实现只返回空壳（activities=null → replan NPE）。
     * 现从 TripService 内部 API 读取基础版本活动与行程信息，转换为 ActivityVar 并补齐
     * 城市/时间窗/解析输入，供增量求解与 VERIFY_CITY 使用。</p>
     *
     * @param tripId    行程 ID
     * @param versionId 基础版本 ID
     * @return 基础问题；加载失败抛异常（上层转为任务失败，避免带着空数据求解）
     */
    public PlanningProblem loadBaseProblem(String tripId, String versionId) {
        Map<String, Object> detail = tripServiceClient.getVersionDetail(tripId, versionId);
        if (detail == null) {
            throw new IllegalStateException("基础版本加载失败: tripId=" + tripId + ", versionId=" + versionId);
        }

        String activitiesJson = String.valueOf(detail.getOrDefault("activities", "[]"));
        String rawInput = String.valueOf(detail.getOrDefault("rawInput", ""));
        String timeStartStr = String.valueOf(detail.getOrDefault("timeStart", ""));
        String timeEndStr = String.valueOf(detail.getOrDefault("timeEnd", ""));
        String city = extractCity(detail);

        LocalDateTime tripStart = parseTime(timeStartStr, LocalDateTime.now().withHour(0).withMinute(0));
        LocalDateTime tripEnd = parseTime(timeEndStr, tripStart.plusDays(1));
        if (tripEnd.isBefore(tripStart)) {
            tripEnd = tripStart.plusDays(1);
        }

        // 时间窗以 day1 00:00 为锚点（plan-service 的 HH:mm 排程不依赖 tripStart 时刻，
        // 若按 tripStart 时刻对齐会压缩窗口导致求解全部不可行）
        LocalDateTime dayStart = tripStart.toLocalDate().atStartOfDay();
        List<ActivityVar> activities = toActivityVars(activitiesJson, dayStart);
        long spanDays = java.time.temporal.ChronoUnit.DAYS.between(dayStart.toLocalDate(), tripEnd.toLocalDate()) + 1;
        int endMin = (int) Math.max(1, spanDays) * 1440;
        for (ActivityVar a : activities) {
            endMin = Math.max(endMin, a.getLatestMin() + 60);
        }

        ParsedInput parsedInput = ParsedInput.builder()
                .rawInput(rawInput)
                .build();

        log.info("基础版本加载完成: tripId={}, versionId={}, activities={}, city={}, 窗口={}min",
                tripId, versionId, activities.size(), city, endMin);

        return PlanningProblem.builder()
                .tripId(tripId)
                .versionId(versionId)
                .parsedInput(parsedInput)
                .city(city)
                .activities(activities)
                .tripTimeWindow(PlanningProblem.TimeWindow.builder()
                        .startMin(0)
                        .endMin(endMin)
                        .horizon(endMin)
                        .build())
                .solverConfig(PlanningProblem.SolverConfig.builder().build())
                .build();
    }

    /** 城市优先级：trip.parsedInput.city > 正则/rawInput 反查 > ""（禁止默认北京） */
    private String extractCity(Map<String, Object> detail) {
        String parsedCity = null;
        Object parsedInputObj = detail.get("parsedInput");
        if (parsedInputObj != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(String.valueOf(parsedInputObj), Map.class);
                Object c = parsed.get("city");
                parsedCity = c != null ? String.valueOf(c) : null;
            } catch (Exception e) {
                log.debug("解析 trip.parsedInput.city 失败: {}", e.getMessage());
            }
        }
        if (parsedCity != null && !parsedCity.isBlank() && !"null".equals(parsedCity)) {
            return parsedCity.trim().replace("市", "");
        }
        String rawInput = String.valueOf(detail.getOrDefault("rawInput", ""));
        String regexCity = CityOwnershipUtils.extractCity(rawInput);
        return regexCity == null ? "" : regexCity;
    }

    /**
     * 版本活动 JSON → ActivityVar（时间转为相对行程开始的分钟数）
     * 兼容两种键名：plan-service 输出的 snake_case（poi_name/activity_type/...）
     * 与 worker 输出的 camelCase（poiName/activityType/...）
     */
    private List<ActivityVar> toActivityVars(String activitiesJson, LocalDateTime tripStart) {
        List<Map<String, Object>> maps;
        try {
            maps = objectMapper.readValue(activitiesJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("解析基础版本活动失败: {}", e.getMessage());
            return new ArrayList<>();
        }
        List<ActivityVar> out = new ArrayList<>();
        if (maps == null) {
            return out;
        }
        for (Map<String, Object> m : maps) {
            String name = pickStr(m, "poi_name", "poiName", "name", "");
            if (name.isEmpty()) {
                continue;
            }
            String type = pickStr(m, "activity_type", "activityType", "type", "visit");
            String priority = pickStr(m, "priority", "recommended");
            double[] latLng = parseLocation(m.get("poi_location"));
            if (latLng == null) {
                latLng = parseLocation(m.get("poiLocation"));
            }
            if (latLng == null && m.get("lng") instanceof Number && m.get("lat") instanceof Number) {
                latLng = new double[]{((Number) m.get("lat")).doubleValue(), ((Number) m.get("lng")).doubleValue()};
            }

            int durationMin = pickNum(m, 60, "duration_min", "durationMin");
            int day = pickNum(m, 1, "day");
            int earliestMin = minutesFromStart(pickRaw(m, "scheduled_start", "scheduledStart"), tripStart, day, 0);
            int latestMin = minutesFromStart(pickRaw(m, "scheduled_end", "scheduledEnd"), tripStart, day,
                    earliestMin + durationMin);
            if (latestMin <= earliestMin) {
                latestMin = earliestMin + Math.max(15, durationMin);
            }

            boolean isTransit = "transit".equals(type) || "buffer".equals(type);
            ActivityVar act = ActivityVar.builder()
                    .id(pickStr(m, "id", java.util.UUID.randomUUID().toString().replace("-", "")))
                    .seq(pickNum(m, out.size(), "seq"))
                    .name(name)
                    .type(type)
                    .priority(priority)
                    .lat(latLng != null ? latLng[0] : 0)
                    .lng(latLng != null ? latLng[1] : 0)
                    .earliestMin(earliestMin)
                    .latestMin(latestMin)
                    .minDurationMin(Math.max(15, durationMin / 2))
                    .preferredDurationMin(durationMin)
                    .maxDurationMin(durationMin * 2)
                    .transportMode(pickStr(m, "transport_mode", "transportMode", "transit"))
                    .isTransit(isTransit)
                    .priorityWeight("must".equals(priority) ? 1000 : "recommended".equals(priority) ? 100 : 10)
                    .travelDurationMin(pickNum(m, 0, "travel_duration_min", "travelDurationMin"))
                    .poiAddress(pickStr(m, "poi_address", "poiAddress", ""))
                    .rating(m.get("rating") instanceof Number ? ((Number) m.get("rating")).doubleValue() : null)
                    .cost(m.get("cost") != null ? String.valueOf(m.get("cost")) : null)
                    .build();
            out.add(act);
        }
        return out;
    }

    /** 按优先级取字符串键（snake_case 优先，兼容 camelCase 与通用别名） */
    private String pickStr(Map<String, Object> m, String... keys) {
        for (int i = 0; i < keys.length - 1; i++) {
            Object v = m.get(keys[i]);
            if (v != null && !"null".equals(String.valueOf(v))) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return keys.length > 0 ? keys[keys.length - 1] : "";
    }

    private Object pickRaw(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.get(k) != null) {
                return m.get(k);
            }
        }
        return null;
    }

    /** 取数值（兼容 Number 与可转数字字符串） */
    private int pickNum(Map<String, Object> m, int fallback, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return (int) Double.parseDouble(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return fallback;
    }

    /**
     * 活动时间 → 相对 day1 00:00 的分钟数
     * 支持 ISO LocalDateTime（worker 输出）与 "HH:mm"（plan-service 输出，day 为第几天）
     */
    private int minutesFromStart(Object timeObj, LocalDateTime dayStart, int day, int fallback) {
        if (timeObj == null) {
            return fallback;
        }
        String s = String.valueOf(timeObj).trim();
        // "HH:mm"：day 第几天的时刻 → 自 day1 00:00 起的分钟
        String[] hm = s.split(":");
        if (hm.length == 2 && s.length() <= 5) {
            try {
                int minutes = Math.max(1, day) * 1440 - 1440
                        + Integer.parseInt(hm[0].trim()) * 60 + Integer.parseInt(hm[1].trim());
                return Math.max(0, minutes);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        try {
            LocalDateTime t = LocalDateTime.parse(s);
            int minutes = (int) Duration.between(dayStart, t).toMinutes();
            return Math.max(0, minutes);
        } catch (Exception e) {
            return fallback;
        }
    }

    private LocalDateTime parseTime(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            log.debug("时间解析失败: {}, 使用默认 {}", value, fallback);
            return fallback;
        }
    }

    /** poiLocation「lat,lng」→ [lat, lng] */
    private double[] parseLocation(Object poiLocation) {
        if (poiLocation == null) {
            return null;
        }
        String s = String.valueOf(poiLocation);
        String[] parts = s.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o).trim();
        return "null".equals(s) ? "" : s;
    }
}
