package com.tripplanner.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.common.model.ParsedInput;
import com.tripplanner.worker.dto.SolverResult;
import com.tripplanner.worker.service.GeocodeClient;
import com.tripplanner.worker.service.InputParsingClient;
import com.tripplanner.worker.service.PlanningOrchestrator;
import com.tripplanner.worker.service.ProgressPublisher;
import com.tripplanner.worker.service.ResultPersistService;
import com.tripplanner.worker.solver.HeuristicSolver;
import com.tripplanner.worker.solver.IncrementalReplanner;
import com.tripplanner.worker.solver.model.ActivityVar;
import com.tripplanner.worker.solver.model.PlanningProblem;
import com.tripplanner.worker.solver.model.TravelMatrix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 规划任务消费者
 * 消费 planning.jobs (新建行程规划)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanningJobConsumer {

    private final PlanningOrchestrator orchestrator;
    private final GeocodeClient geocodeClient;
    private final InputParsingClient inputParsingClient;
    private final ResultPersistService persistService;
    private final ProgressPublisher progressPublisher;
    private final HeuristicSolver heuristicSolver;
    private final IncrementalReplanner incrementalReplanner;
    private final ObjectMapper objectMapper;
    private final JsonUtils jsonUtils;

    @KafkaListener(topics = "planning.jobs", groupId = "${kafka.consumer.group.planning:planning-worker-group}", containerFactory = "kafkaListenerContainerFactory")
    public void handlePlanningJob(Map<String, Object> job, Acknowledgment ack) {
        String taskId = (String) job.get("taskId");
        String tripId = (String) job.get("tripId");
        String versionId = (String) job.get("versionId");
        
        log.info("收到规划任务: taskId={}, tripId={}, versionId={}", taskId, tripId, versionId);

        try {
            // 1. 发布进度: 开始
            progressPublisher.publishProgress(taskId, tripId, 5, "PARSE_INPUT", "解析输入数据");

            // 2. 调用LLM解析原始输入为结构化数据
            String inputJson = (String) job.get("input");
            @SuppressWarnings("unchecked")
            Map<String, Object> inputMap = jsonUtils.fromJson(inputJson, Map.class);
            String rawInput = (String) inputMap.getOrDefault("rawInput", "");
            String timeStart = (String) inputMap.getOrDefault("timeStart", "");
            String timeEnd = (String) inputMap.getOrDefault("timeEnd", "");
            String transportMode = (String) inputMap.getOrDefault("transportMode", "mixed");

            progressPublisher.publishProgress(taskId, tripId, 10, "LLM_PARSE", "AI解析旅行需求");
            Map<String, Object> parsedResult = inputParsingClient.parseInput(rawInput, timeStart, timeEnd, transportMode);

            // 提取城市信息
            String city = (String) parsedResult.getOrDefault("city", "");
            if (city.isEmpty()) {
                city = inferCity(rawInput);
            }

            // 3. 构建临时活动列表用于地理编码
            PlanningProblem problem = buildProblem(job, parsedResult);

            // 4. 地理编码
            progressPublisher.publishProgress(taskId, tripId, 15, "GEOCODE", "解析地点坐标");
            List<ActivityVar> geocodedActivities = geocodeActivities(problem.getActivities(), city);

            // 5. 获取距离矩阵
            progressPublisher.publishProgress(taskId, tripId, 25, "BUILD_MATRIX", "计算距离矩阵");
            TravelMatrix travelMatrix = geocodeClient.getDistanceMatrix(geocodedActivities, city);

            // 6. 格式化距离矩阵为LLM可读的字符串
            String distanceMatrixStr = formatDistanceMatrix(geocodedActivities, travelMatrix);

            // 7. LLM 生成完整行程规划
            progressPublisher.publishProgress(taskId, tripId, 40, "LLM_PLAN", "AI生成完整行程");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> places = (List<Map<String, Object>>) parsedResult.getOrDefault("places", List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> meals = (List<Map<String, Object>>) parsedResult.getOrDefault("meals", List.of());

            Map<String, Object> itineraryRequest = Map.of(
                    "rawInput", rawInput,
                    "timeStart", timeStart,
                    "timeEnd", timeEnd,
                    "places", places,
                    "meals", meals,
                    "distanceMatrix", distanceMatrixStr,
                    "city", city == null || city.isBlank() ? "" : city
            );

            Map<String, Object> itineraryResult = inputParsingClient.planItinerary(itineraryRequest);

            // 8. 将LLM规划结果转换为ActivityVar列表
            progressPublisher.publishProgress(taskId, tripId, 70, "CONVERT", "转换规划结果");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> llmActivities = (List<Map<String, Object>>) itineraryResult.getOrDefault("activities", List.of());
            List<ActivityVar> scheduledActivities = convertLlmActivities(llmActivities, geocodedActivities, timeStart);

            // 8.5 VERIFY_CITY: 校验推荐景点是否在目标城市范围内
            progressPublisher.publishProgress(taskId, tripId, 85, "VERIFY_CITY", "校验景点城市归属");
            scheduledActivities = verifyCityOwnership(scheduledActivities, city, taskId, tripId);

            // 9. 持久化
            progressPublisher.publishProgress(taskId, tripId, 90, "PERSIST", "保存规划结果");
            Map<String, Object> stats = Map.of(
                    "algorithm", "LLM-ITINERARY",
                    "totalActivities", scheduledActivities.size(),
                    "method", "llm-direct"
            );
            String resultVersionId = persistService.persistResult(
                    tripId, versionId, taskId, scheduledActivities, stats
            );

            // 10. 完成
            publishCompletedEvent(taskId, tripId, versionId, resultVersionId, stats);
            progressPublisher.publishProgress(taskId, tripId, 100, "COMPLETED", "规划完成");

            log.info("LLM行程规划完成: taskId={}, versionId={}, activities={}",
                    taskId, resultVersionId, scheduledActivities.size());

            ack.acknowledge();
        } catch (Exception e) {
            log.error("规划任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            
            // 发布失败事件
            publishFailedEvent(taskId, tripId, versionId, e.getMessage(), true);
            
            // 根据错误类型决定是否重试
            if (isRetryable(e)) {
                // 不 ack，让 Kafka 重试
                throw e;
            } else {
                // 标记为死信，ack
                progressPublisher.publishProgress(taskId, tripId, 0, "FAILED", "规划失败: " + e.getMessage());
                ack.acknowledge();
            }
        }
    }

    /**
     * 构建规划问题
     */
    @SuppressWarnings("unchecked")
    private PlanningProblem buildProblem(Map<String, Object> job, Map<String, Object> parsedResult) {
        String taskId = (String) job.get("taskId");
        String tripId = (String) job.get("tripId");
        String versionId = (String) job.get("versionId");

        // 从解析结果构建活动列表
        List<Map<String, Object>> places = (List<Map<String, Object>>) parsedResult.getOrDefault("places", List.of());
        List<Map<String, Object>> meals = (List<Map<String, Object>>) parsedResult.getOrDefault("meals", List.of());

        // 解析时间
        String timeStart = (String) parsedResult.getOrDefault("timeStart", "");
        String timeEnd = (String) parsedResult.getOrDefault("timeEnd", "");

        List<ActivityVar> activities = buildActivitiesFromParsed(places, meals, tripId);

        // 时间窗
        java.time.LocalDateTime start = timeStart.isEmpty() ? java.time.LocalDateTime.now() :
                java.time.LocalDateTime.parse(timeStart, java.time.format.DateTimeFormatter.ISO_DATE_TIME);
        java.time.LocalDateTime end = timeEnd.isEmpty() ? start.plusDays(1) :
                java.time.LocalDateTime.parse(timeEnd, java.time.format.DateTimeFormatter.ISO_DATE_TIME);
        int startMin = 0;
        int endMin = (int) java.time.Duration.between(start, end).toMinutes();

        // Convert meal windows from absolute (from midnight) to relative (from trip start)
        int tripStartAbsMin = start.getHour() * 60 + start.getMinute();
        activities = activities.stream().map(act -> {
            if ("meal".equals(act.getType())) {
                // Convert absolute meal windows to relative to trip start
                int relativeEarliest = Math.max(0, act.getEarliestMin() - tripStartAbsMin);
                int relativeLatest = Math.min(endMin, act.getLatestMin() - tripStartAbsMin);
                // Ensure the window is valid
                if (relativeLatest <= relativeEarliest) {
                    relativeEarliest = 0;
                    relativeLatest = endMin;
                }
                return act.toBuilder().earliestMin(relativeEarliest).latestMin(relativeLatest).build();
            }
            return act;
        }).toList();

        return PlanningProblem.builder()
                .tripId(tripId)
                .versionId(versionId)
                .taskId(taskId)
                .activities(activities)
                .tripTimeWindow(PlanningProblem.TimeWindow.builder()
                        .startMin(startMin)
                        .endMin(endMin)
                        .horizon(endMin)
                        .build())
                .solverConfig(PlanningProblem.SolverConfig.builder().build())
                .build();
    }

    /**
     * 从解析结果构建活动变量
     */
    private List<ActivityVar> buildActivitiesFromParsed(List<Map<String, Object>> places, List<Map<String, Object>> meals, String tripId) {
        List<ActivityVar> activities = new java.util.ArrayList<>();
        int seq = 0;

        // 出发地 (虚拟活动)
        activities.add(ActivityVar.builder()
                .id("origin")
                .seq(seq++)
                .name("出发")
                .type("transit")
                .priority("must")
                .earliestMin(0)
                .latestMin(0)
                .preferredDurationMin(0)
                .isTransit(true)
                .build());

        // 地点
        for (Map<String, Object> place : places) {
            String name = (String) place.getOrDefault("name", "");
            String type = (String) place.getOrDefault("type", "scenic");
            String priority = (String) place.getOrDefault("priority", "recommended");
            int duration = place.get("preferredDurationMin") != null ? ((Number) place.get("preferredDurationMin")).intValue() : getDefaultDuration(type);

            ActivityVar act = ActivityVar.builder()
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .seq(seq++)
                    .name(name)
                    .type(type)
                    .priority(priority)
                    .preferredDurationMin(duration)
                    .minDurationMin(Math.max(15, duration / 2))
                    .maxDurationMin(duration * 2)
                    .earliestMin(0)
                    .latestMin(1440)
                    .transportMode("transit")
                    .priorityWeight(getPriorityWeight(priority))
                    .build();
            activities.add(act);
        }

        // 餐饮
        for (Map<String, Object> meal : meals) {
            String type = (String) meal.getOrDefault("type", "lunch");
            int duration = meal.get("durationMin") != null ? ((Number) meal.get("durationMin")).intValue() : 90;

            ActivityVar act = ActivityVar.builder()
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .seq(seq++)
                    .name(type + "餐")
                    .type("meal")
                    .priority("must")
                    .preferredDurationMin(duration)
                    .minDurationMin(30)
                    .maxDurationMin(180)
                    .earliestMin(getMealWindowStart(type))
                    .latestMin(getMealWindowEnd(type))
                    .transportMode("walk")
                    .priorityWeight(100)
                    .build();
            activities.add(act);
        }

        // 返程
        activities.add(ActivityVar.builder()
                .id("destination")
                .seq(seq)
                .name("返程")
                .type("transit")
                .priority("must")
                .earliestMin(0)
                .latestMin(1440)
                .preferredDurationMin(0)
                .isTransit(true)
                .build());

        return activities;
    }

    private int getDefaultDuration(String type) {
        return switch (type) {
            case "scenic" -> 120;
            case "museum" -> 180;
            case "park" -> 90;
            case "temple" -> 60;
            case "restaurant" -> 90;
            case "shopping" -> 90;
            default -> 60;
        };
    }

    private int getPriorityWeight(String priority) {
        return switch (priority) {
            case "must" -> 1000;
            case "recommended" -> 100;
            case "optional" -> 10;
            case "excluded" -> -1000;
            default -> 1;
        };
    }

    private int getMealWindowStart(String type) {
        return switch (type) {
            case "breakfast" -> 7 * 60;   // 07:00
            case "lunch" -> 11 * 60 + 30; // 11:30
            case "dinner" -> 17 * 60;     // 17:00
            default -> 12 * 60;
        };
    }

    private int getMealWindowEnd(String type) {
        return switch (type) {
            case "breakfast" -> 10 * 60;  // 10:00
            case "lunch" -> 14 * 60;      // 14:00
            case "dinner" -> 21 * 60;     // 21:00
            default -> 20 * 60;
        };
    }

    /**
     * 批量地理编码（透传 city，避免跨城同名景点编错）
     */
    private List<ActivityVar> geocodeActivities(List<ActivityVar> activities, String city) {
        return geocodeClient.batchGeocode(activities, city);
    }

    /**
     * VERIFY_CITY: 过滤明确属于其他城市的 visit 景点，并重排 seq
     */
    private List<ActivityVar> verifyCityOwnership(List<ActivityVar> activities, String city,
                                                  String taskId, String tripId) {
        var result = com.tripplanner.common.util.CityOwnershipUtils.filterVisitsForCity(
                activities,
                ActivityVar::getName,
                ActivityVar::getType,
                city
        );
        if (result.rejected().isEmpty()) {
            log.info("VERIFY_CITY 通过: taskId={}, city={}, visitTotal={}",
                    taskId, city, result.visitTotal());
            return activities;
        }
        if (result.isOverwhelmed()) {
            throw new IllegalStateException(
                    "规划结果景点城市归属校验失败: 目标城市=" + city
                            + ", 剔除=" + result.rejectedSummary());
        }
        log.warn("VERIFY_CITY 剔除跨城景点: taskId={}, tripId={}, city={}, rejected={}, visitTotal={}",
                taskId, tripId, city, result.rejectedSummary(), result.visitTotal());

        List<ActivityVar> filtered = new java.util.ArrayList<>();
        int seq = 0;
        for (ActivityVar act : result.kept()) {
            filtered.add(act.toBuilder().seq(seq++).build());
        }
        return filtered;
    }

    /**
     * 格式化距离矩阵为LLM可读的字符串
     */
    private String formatDistanceMatrix(List<ActivityVar> activities, TravelMatrix matrix) {
        StringBuilder sb = new StringBuilder();
        sb.append("景点编号对应:\n");
        for (int i = 0; i < activities.size(); i++) {
            ActivityVar a = activities.get(i);
            if (!a.isTransit() && !a.isBuffer()) {
                sb.append(String.format("%d. %s\n", i, a.getName()));
            }
        }
        sb.append("\n距离矩阵 (米):\n");
        for (int i = 0; i < activities.size(); i++) {
            ActivityVar a = activities.get(i);
            if (a.isTransit() || a.isBuffer()) continue;
            for (int j = 0; j < activities.size(); j++) {
                ActivityVar b = activities.get(j);
                if (b.isTransit() || b.isBuffer()) continue;
                if (i != j && matrix.getDistanceMeters() != null
                        && i < matrix.getDistanceMeters().length
                        && j < matrix.getDistanceMeters()[0].length) {
                    sb.append(String.format("%s -> %s: %dm\n", a.getName(), b.getName(), matrix.getDistanceMeters()[i][j]));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 将LLM返回的活动列表转换为ActivityVar列表
     */
    @SuppressWarnings("unchecked")
    private List<ActivityVar> convertLlmActivities(List<Map<String, Object>> llmActivities,
                                                    List<ActivityVar> geocodedActivities, String timeStart) {
        List<ActivityVar> result = new java.util.ArrayList<>();
        int seq = 0;

        java.time.LocalDateTime tripStart = java.time.LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        if (timeStart != null && !timeStart.isEmpty()) {
            try {
                tripStart = java.time.LocalDateTime.parse(timeStart, java.time.format.DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e) {
                log.warn("解析行程开始时间失败: {}", timeStart);
            }
        }

        for (Map<String, Object> llmAct : llmActivities) {
            String name = (String) llmAct.getOrDefault("name", "");
            String type = (String) llmAct.getOrDefault("type", "visit");
            String startTime = (String) llmAct.getOrDefault("startTime", "");
            String endTime = (String) llmAct.getOrDefault("endTime", "");
            int durationMin = llmAct.get("durationMin") != null ? ((Number) llmAct.get("durationMin")).intValue() : 60;
            String transport = (String) llmAct.getOrDefault("transportToNext", "");
            int travelTime = llmAct.get("travelTimeMin") != null ? ((Number) llmAct.get("travelTimeMin")).intValue() : 0;
            String priority = (String) llmAct.getOrDefault("priority", "recommended");

            int startMin = parseTimeToMinutes(startTime, tripStart);
            int endMin = parseTimeToMinutes(endTime, tripStart);

            double lat = 0, lng = 0;
            for (ActivityVar ga : geocodedActivities) {
                if (ga.getName().equals(name) && ga.getLat() != 0) {
                    lat = ga.getLat();
                    lng = ga.getLng();
                    break;
                }
            }

            boolean isMeal = "meal".equals(type);
            boolean isTransit = "transit".equals(type);

            ActivityVar act = ActivityVar.builder()
                    .id(java.util.UUID.randomUUID().toString().replace("-", ""))
                    .seq(seq++)
                    .name(name)
                    .type(isMeal ? "meal" : isTransit ? "transit" : "visit")
                    .priority(priority)
                    .lat(lat)
                    .lng(lng)
                    .earliestMin(startMin)
                    .latestMin(endMin)
                    .minDurationMin(durationMin)
                    .preferredDurationMin(durationMin)
                    .maxDurationMin(durationMin)
                    .transportMode(transport)
                    .isTransit(isTransit)
                    .travelDurationMin(travelTime)
                    .priorityWeight("must".equals(priority) ? 1000 : "recommended".equals(priority) ? 100 : 10)
                    .build();
            result.add(act);
        }
        return result;
    }

    private int parseTimeToMinutes(String timeStr, java.time.LocalDateTime tripStart) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return hour * 60 + min;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return true;
        // 网络错误、超时、临时资源不足可重试
        return msg.contains("timeout") || msg.contains("connection") || msg.contains("unavailable");
    }

    /**
     * 从用户输入推断城市名（排除路名误判）
     */
    private String inferCity(String rawInput) {
        String city = com.tripplanner.common.util.CityOwnershipUtils.extractCity(rawInput);
        return city == null ? "" : city;
    }

    private void publishCompletedEvent(String taskId, String tripId, String versionId, 
                                       String resultVersionId, Map<String, Object> stats) {
        Map<String, Object> event = Map.of(
                "eventType", "PLANNING_COMPLETED",
                "taskId", taskId,
                "tripId", tripId,
                "versionId", versionId,
                "resultVersionId", resultVersionId,
                "solverStats", stats,
                "timestamp", LocalDateTime.now().toString()
        );
        // 发送到 planning.events (通过 KafkaTemplate，这里简化)
        log.info("发布完成事件: {}", event);
    }

    private void publishFailedEvent(String taskId, String tripId, String versionId, 
                                    String error, boolean retryable) {
        Map<String, Object> event = Map.of(
                "eventType", "PLANNING_FAILED",
                "taskId", taskId,
                "tripId", tripId,
                "versionId", versionId,
                "errorMessage", error,
                "retryable", retryable,
                "timestamp", LocalDateTime.now().toString()
        );
        log.info("发布失败事件: {}", event);
    }
}