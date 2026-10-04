package com.tripplanner.worker.consumer;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.worker.dto.SolverResult;
import com.tripplanner.worker.service.GeocodeClient;
import com.tripplanner.worker.service.PlanningOrchestrator;
import com.tripplanner.worker.service.ProgressPublisher;
import com.tripplanner.worker.service.ResultPersistService;
import com.tripplanner.worker.solver.IncrementalReplanner;
import com.tripplanner.worker.solver.model.ActivityVar;
import com.tripplanner.worker.solver.model.PlanningProblem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 重规划任务消费者
 * 消费 planning.replan.jobs (反馈重规划)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplanJobConsumer {

    private final PlanningOrchestrator orchestrator;
    private final GeocodeClient geocodeClient;
    private final ResultPersistService persistService;
    private final ProgressPublisher progressPublisher;
    private final IncrementalReplanner incrementalReplanner;
    private final JsonUtils jsonUtils;

    @KafkaListener(topics = "planning.replan.jobs", groupId = "${kafka.consumer.group.replan:planning-replan-group}", containerFactory = "kafkaListenerContainerFactory")
    public void handleReplanJob(Map<String, Object> job, Acknowledgment ack) {
        String taskId = (String) job.get("taskId");
        String tripId = (String) job.get("tripId");
        String versionId = (String) job.get("versionId");
        String baseVersionId = (String) job.get("baseVersionId");
        String feedback = (String) job.get("feedback");
        Object changes = job.get("changes");

        log.info("收到重规划任务: taskId={}, tripId={}, baseVersionId={}", taskId, tripId, baseVersionId);

        try {
            // 1. 发布进度
            progressPublisher.publishProgress(taskId, 10, "LOAD_BASE", "加载基础版本");
            // 先加载基础版本（parseFeedback 需要基础活动做名称匹配；原顺序为空壳会 NPE）
            PlanningProblem baseProblem = orchestrator.loadBaseProblem(tripId, baseVersionId);

            // 2. 解析反馈 -> 变更集（需 baseProblem.activities）
            List<IncrementalReplanner.Change> changeList = parseFeedback(feedback, changes,
                    baseProblem.getActivities());
            log.info("反馈解析完成: taskId={}, changes={}", taskId,
                    changeList.stream().map(c -> c.type() + (c.activityId() != null ? ":" + c.activityId() : ""))
                            .collect(java.util.stream.Collectors.joining(",")));

            // 3. 发布进度: 地理编码新增地点
            progressPublisher.publishProgress(taskId, 25, "GEOCODE", "解析新增地点坐标");
            List<ActivityVar> newActivities = changeList.stream()
                    .filter(c -> c.type() == IncrementalReplanner.ChangeType.ADD)
                    .map(IncrementalReplanner.Change::activity)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
            if (!newActivities.isEmpty()) {
                newActivities = geocodeClient.batchGeocode(newActivities, null);
            }

            // 5. 发布进度: 增量求解
            progressPublisher.publishProgress(taskId, 50, "INCREMENTAL_SOLVE", "增量重规划");
            
            SolverResult result = incrementalReplanner.replan(baseProblem, changeList);

            if (!result.isSuccess()) {
                throw new RuntimeException("增量求解失败: " + result.getErrorMessage());
            }

            // 6. 发布进度: 路线生成
            progressPublisher.publishProgress(taskId, 80, "ROUTE", "生成详细路线");
            List<ActivityVar> routedActivities = geocodeClient.enrichRoutes(
                    result.getScheduled(), baseProblem.getTravelMatrix()
            );

            // 6.5 VERIFY_CITY: 校验景点是否在目标城市
            progressPublisher.publishProgress(taskId, tripId, 85, "VERIFY_CITY", "校验景点城市归属");
            routedActivities = verifyCityOwnership(routedActivities, baseProblem, taskId, tripId);

            // 6.7 CHECK_MEALS: 餐次时间窗校验进度（1.31.0；plan-service 管线末位
            // checkMealWindowsStep 已强制修复，落库侧 trip-service MealWindowGuard 兜底告警）
            progressPublisher.publishProgress(taskId, tripId, 88, "CHECK_MEALS", "校验正餐时间窗");

            // 7. 发布进度: 持久化（job.versionId = createVersionFromFeedback 预建的目标版本 → 原地更新）
            progressPublisher.publishProgress(taskId, tripId, 90, "PERSIST", "保存规划结果");
            String resultVersionId;
            if (versionId != null && !versionId.isBlank()) {
                resultVersionId = persistService.persistResultToTarget(
                        tripId, versionId, taskId, routedActivities, result.getStats()
                );
            } else {
                resultVersionId = persistService.persistResult(
                        tripId, baseVersionId, taskId, routedActivities, result.getStats()
                );
            }

            // 8. 发布完成事件
            publishCompletedEvent(taskId, tripId, versionId, resultVersionId, result.getStats());

            // 9. 完成
            progressPublisher.publishProgress(taskId, 100, "COMPLETED", "重规划完成");

            log.info("重规划任务完成: taskId={}, versionId={}", taskId, resultVersionId);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("重规划任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            publishFailedEvent(taskId, tripId, versionId, e.getMessage(), true);
            
            if (isRetryable(e)) {
                throw e;
            } else {
                progressPublisher.publishProgress(taskId, 0, "FAILED", "重规划失败: " + e.getMessage());
                ack.acknowledge();
            }
        }
    }

    /**
     * 解析用户反馈为变更集
     *
     * <p>v1.15.0 实现规则化解析（原为桩，且 feedback 为 null 会 NPE）：</p>
     * <ul>
     *   <li>结构化 changes 非空优先（解析失败回退规则）</li>
     *   <li>按标点切分小句：含删除意图动词（不去/移除/删除/取消/跳过/去掉）且
     *       小句中出现基础活动名 → REMOVE</li>
     *   <li>「活动名 多待/多玩/多留 N 小时(分钟)」→ MODIFY_DURATION（newValue=新时长）</li>
     * </ul>
     */
    private List<IncrementalReplanner.Change> parseFeedback(String feedback, Object changes,
                                                            List<ActivityVar> baseActivities) {
        // 1. 前端已发送结构化 changes 优先
        if (changes != null) {
            try {
                List<IncrementalReplanner.Change> structured = jsonUtils.fromJson(jsonUtils.toJson(changes),
                        new com.fasterxml.jackson.core.type.TypeReference<List<IncrementalReplanner.Change>>() {});
                if (structured != null && !structured.isEmpty()) {
                    return structured;
                }
            } catch (Exception e) {
                log.warn("结构化 changes 解析失败，回退规则解析: {}", e.getMessage());
            }
        }

        List<IncrementalReplanner.Change> result = new java.util.ArrayList<>();
        if (feedback == null || feedback.isBlank()) {
            return result;
        }
        List<ActivityVar> acts = baseActivities == null ? java.util.List.of() : baseActivities;

        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Pattern removeIntent = java.util.regex.Pattern.compile(
                "(不去|不用去|不要去|不去了|移除|删除|删掉|取消|跳过|去掉)");
        java.util.regex.Pattern durationPattern = java.util.regex.Pattern.compile(
                "([^，,。.!！?？、；;\\n\\s]{1,20})(?:多待|多玩|多留|多呆|多花)\\s*(\\d+)\\s*(小时|分钟|h|hr|hour|m|min)",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        for (String seg : feedback.split("[，,。.!！?？、；;\\n]")) {
            if (seg.isBlank()) {
                continue;
            }
            // 2. REMOVE: 删除意图 + 小句中出现活动名（避免整句动词误删其他活动）
            if (removeIntent.matcher(seg).find()) {
                for (ActivityVar act : acts) {
                    if (act.isTransit() || act.isBuffer() || act.getName() == null) {
                        continue;
                    }
                    String bare = stripMealPrefix(act.getName());
                    if (bare.length() < 2 || !seg.contains(bare)) {
                        continue;
                    }
                    if (seen.add("REMOVE:" + act.getId())) {
                        result.add(new IncrementalReplanner.Change(
                                IncrementalReplanner.ChangeType.REMOVE, act.getId(), null, null, null));
                    }
                }
            }
            // 3. MODIFY_DURATION: 「名称 多待 N 小时/分钟」
            java.util.regex.Matcher m = durationPattern.matcher(seg);
            while (m.find()) {
                String frag = m.group(1);
                int delta = Integer.parseInt(m.group(2));
                String unit = m.group(3).toLowerCase();
                int deltaMin = delta * (unit.startsWith("h") ? 60 : 1);
                ActivityVar target = matchActivity(acts, frag);
                if (target == null) {
                    continue;
                }
                int newDuration = Math.max(target.getPreferredDurationMin(), 15) + deltaMin;
                if (seen.add("DUR:" + target.getId())) {
                    result.add(new IncrementalReplanner.Change(
                            IncrementalReplanner.ChangeType.MODIFY_DURATION, target.getId(), null,
                            target.getPreferredDurationMin(), newDuration));
                }
            }
        }

        if (result.isEmpty()) {
            log.warn("反馈未识别出可执行变更: feedback={}", feedback);
        }
        return result;
    }

    /** 活动名（去「午餐·」等餐次前缀） */
    private String stripMealPrefix(String name) {
        int idx = name.indexOf('·');
        String bare = idx >= 0 && idx + 1 < name.length() ? name.substring(idx + 1) : name;
        return bare.replace("（", "(").replace("）", ")").trim();
    }

    /** 片段 ↔ 活动名匹配（双向包含，优先最长命中） */
    private ActivityVar matchActivity(List<ActivityVar> acts, String fragment) {
        ActivityVar best = null;
        int bestLen = 0;
        for (ActivityVar act : acts) {
            if (act.isTransit() || act.isBuffer() || act.getName() == null) {
                continue;
            }
            String bare = stripMealPrefix(act.getName());
            if (bare.length() < 2) {
                continue;
            }
            if ((fragment.contains(bare) || bare.contains(fragment)) && bare.length() > bestLen) {
                best = act;
                bestLen = bare.length();
            }
        }
        return best;
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("timeout") || msg.contains("connection"));
    }

    /**
     * VERIFY_CITY: 过滤明确属于其他城市的 visit 景点，并重排 seq
     */
    private List<ActivityVar> verifyCityOwnership(List<ActivityVar> activities, PlanningProblem baseProblem,
                                                  String taskId, String tripId) {
        // 城市优先级：trip.parsedInput.city（loadBaseProblem 已提取）> 正则/rawInput；缺失不过滤
        String city = "";
        if (baseProblem != null && baseProblem.getCity() != null && !baseProblem.getCity().isBlank()) {
            city = baseProblem.getCity();
        } else if (baseProblem != null && baseProblem.getParsedInput() != null
                && baseProblem.getParsedInput().getRawInput() != null) {
            String extracted = com.tripplanner.common.util.CityOwnershipUtils.extractCity(
                    baseProblem.getParsedInput().getRawInput());
            city = extracted == null ? "" : extracted;
        }
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
                    "重规划结果景点城市归属校验失败: 目标城市=" + city
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

    private void publishCompletedEvent(String taskId, String tripId, String versionId, 
                                       String resultVersionId, Map<String, Object> stats) {
        Map<String, Object> event = Map.of(
                "eventType", "REPLAN_COMPLETED",
                "taskId", taskId,
                "tripId", tripId,
                "versionId", versionId,
                "resultVersionId", resultVersionId,
                "solverStats", stats,
                "timestamp", LocalDateTime.now().toString()
        );
        log.info("发布重规划完成事件: {}", event);
    }

    private void publishFailedEvent(String taskId, String tripId, String versionId, 
                                    String error, boolean retryable) {
        Map<String, Object> event = Map.of(
                "eventType", "REPLAN_FAILED",
                "taskId", taskId,
                "tripId", tripId,
                "versionId", versionId,
                "errorMessage", error,
                "retryable", retryable,
                "timestamp", LocalDateTime.now().toString()
        );
        log.info("发布重规划失败事件: {}", event);
    }
}