package com.tripplanner.plan.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.plan.dto.response.PlanTaskProgressResponse;
import com.tripplanner.plan.entity.PlanTask;
import com.tripplanner.plan.repository.PlanTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规划任务管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PlanTaskService {

    private final PlanTaskRepository taskRepository;
    private final JsonUtils jsonUtils;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 创建规划任务
     */
    public PlanTask createTask(String tripId, String versionId, String taskType, Object input) {
        PlanTask task = new PlanTask();
        task.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        task.setTripId(tripId);
        task.setVersionId(versionId);
        task.setTaskType(taskType); // plan, replan
        task.setStatus("pending");
        task.setProgress(0);
        task.setStage("parse_input");
        task.setInputSnapshot(jsonUtils.toJson(input));
        task.setRetryCount(0);

        taskRepository.insert(task);
        log.info("创建规划任务: taskId={}, tripId={}, type={}", task.getId(), tripId, taskType);
        return task;
    }

    /**
     * 更新任务进度 (Worker 调用)
     */
    public void updateProgress(String taskId, int progress, String stage, String message) {
        PlanTask task = taskRepository.selectById(taskId);
        if (task == null) {
            log.warn("更新进度失败: 任务不存在 taskId={}", taskId);
            return;
        }

        task.setProgress(progress);
        task.setStage(stage);
        if ("running".equals(task.getStatus())) {
            task.setStartedAt(LocalDateTime.now());
        }
        taskRepository.updateById(task);
        log.debug("任务进度更新: taskId={}, progress={}%, stage={}", taskId, progress, stage);
    }

    /**
     * 标记任务开始运行
     */
    public void markRunning(String taskId) {
        PlanTask task = taskRepository.selectById(taskId);
        if (task != null) {
            task.setStatus("running");
            task.setProgress(0);
            task.setStage("parse_input");
            task.setStartedAt(LocalDateTime.now());
            taskRepository.updateById(task);
        }
    }

    /**
     * 标记任务完成
     */
    public void markCompleted(String taskId, String resultVersionId, Map<String, Object> solverStats) {
        PlanTask task = taskRepository.selectById(taskId);
        if (task != null) {
            task.setStatus("completed");
            task.setProgress(100);
            task.setStage("persist");
            task.setResultVersionId(resultVersionId);
            task.setSolverStats(jsonUtils.toJson(solverStats));
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.updateById(task);
            log.info("规划任务完成: taskId={}, resultVersionId={}", taskId, resultVersionId);
        }
    }

    /**
     * 标记任务失败
     */
    public void markFailed(String taskId, String errorMessage) {
        PlanTask task = taskRepository.selectById(taskId);
        if (task != null) {
            task.setStatus("failed");
            task.setErrorMessage(errorMessage);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.updateById(task);
            log.error("规划任务失败: taskId={}, error={}", taskId, errorMessage);
        }
    }

    /**
     * 增加重试次数并重置状态
     */
    public void incrementRetryAndReset(String taskId) {
        PlanTask task = taskRepository.selectById(taskId);
        if (task != null) {
            int newRetryCount = task.getRetryCount() + 1;
            if (newRetryCount >= 3) {
                task.setStatus("dead_letter");
                task.setErrorMessage("超过最大重试次数");
                log.error("任务进入死信队列: taskId={}, retryCount={}", taskId, newRetryCount);
            } else {
                task.setRetryCount(newRetryCount);
                task.setStatus("pending");
                task.setProgress(0);
                task.setStage("parse_input");
                task.setErrorMessage(null);
                log.info("任务重试: taskId={}, retryCount={}", taskId, newRetryCount);
            }
            taskRepository.updateById(task);
        }
    }

    /**
     * 查询任务进度
     */
    public PlanTaskProgressResponse getProgress(String taskId) {
        PlanTask task = taskRepository.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("规划任务", taskId);
        }
        return toProgressResponse(task);
    }

    /**
     * 查询行程的所有任务
     * 先查 Redis 中 Worker 实时进度，再查数据库
     */
    @SuppressWarnings("unchecked")
    public List<PlanTaskProgressResponse> getTasksByTripId(String tripId) {
        List<PlanTaskProgressResponse> results = new ArrayList<>();

        // 1. Check Redis for real-time progress from worker
        String taskIdKey = "planning:trip:" + tripId + ":taskId";
        Object taskIdObj = redisTemplate.opsForValue().get(taskIdKey);
        if (taskIdObj != null) {
            String taskId = taskIdObj.toString();
            String progressKey = "planning:progress:" + taskId;
            Object progressObj = redisTemplate.opsForValue().get(progressKey);
            if (progressObj instanceof Map<?, ?> progressMap) {
                int percent = progressMap.get("percent") instanceof Number n ? n.intValue() : 0;
                String stage = progressMap.get("stage") != null ? progressMap.get("stage").toString() : "";
                String message = progressMap.get("message") != null ? progressMap.get("message").toString() : "";
                String timestamp = progressMap.get("timestamp") != null ? progressMap.get("timestamp").toString() : "";
                
                String status = "running";
                if (percent >= 100) status = "completed";
                else if ("FAILED".equals(stage)) status = "failed";
                
                results.add(PlanTaskProgressResponse.of(
                        taskId, tripId, "", "plan", status, percent, stage,
                        buildStageMessage(stage, percent), null,
                        "FAILED".equals(stage) ? message : null,
                        null, null, null, LocalDateTime.now(), 0
                ));
            }
        }

        // 2. Fallback: check database
        if (results.isEmpty()) {
            results = taskRepository.findByTripId(tripId).stream()
                    .map(this::toProgressResponse)
                    .toList();
        }

        return results;
    }

    /**
     * 获取待处理任务 (Worker 轮询)
     */
    public List<PlanTask> getPendingTasks(String taskType, int limit) {
        return taskRepository.findPendingTasks(taskType, limit);
    }

    /**
     * 获取可重试任务
     */
    public List<PlanTask> getRetryableTasks(int limit) {
        return taskRepository.findRetryableTasks(limit);
    }

    private PlanTaskProgressResponse toProgressResponse(PlanTask task) {
        return PlanTaskProgressResponse.of(
                task.getId(),
                task.getTripId(),
                task.getVersionId(),
                task.getTaskType(),
                task.getStatus(),
                task.getProgress(),
                task.getStage(),
                buildStageMessage(task.getStage(), task.getProgress()),
                jsonUtils.fromJson(task.getSolverStats(), Map.class),
                task.getErrorMessage(),
                task.getResultVersionId(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getRetryCount()
        );
    }

    private String buildStageMessage(String stage, int progress) {
        return switch (stage) {
            case "PARSE_INPUT", "parse_input", "LLM_PARSE" -> "正在解析输入...";
            case "GEOCODE", "geocode", "BUILD_MATRIX" -> "正在解析地点坐标...";
            case "BUILD_MODEL", "build_model", "LLM_PLAN" -> "正在构建约束模型...";
            case "SOLVE", "solve" -> "正在求解最优路径...";
            case "ROUTE", "route", "CONVERT" -> "正在生成路线...";
            case "VERIFY_CITY", "verify_city" -> "校验景点城市归属...";
            case "PERSIST", "persist", "SAVE_RESULT" -> "正在保存结果...";
            case "COMPLETED", "completed" -> "规划完成";
            case "FAILED" -> "规划失败";
            default -> "处理中...";
        };
    }
}