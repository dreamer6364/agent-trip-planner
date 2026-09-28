package com.tripplanner.worker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进度发布器
 * 向 Redis 发布进度 (供 WebSocket 推送)、向 Kafka 发布事件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String PROGRESS_PREFIX = "planning:progress:";
    private static final String EVENTS_TOPIC = "planning.events";

    /**
     * 发布进度更新 (backward-compatible overload)
     */
    public void publishProgress(String taskId, int percent, String stage, String message) {
        publishProgress(taskId, null, percent, stage, message);
    }

    /**
     * 发布进度更新
     */
    public void publishProgress(String taskId, String tripId, int percent, String stage, String message) {
        Map<String, Object> progress = Map.of(
                "taskId", taskId,
                "tripId", tripId != null ? tripId : "",
                "percent", percent,
                "stage", stage,
                "message", message,
                "timestamp", LocalDateTime.now().toString()
        );

        // 1. Redis 存储 (供轮询/WebSocket 查询)
        String key = PROGRESS_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, progress, 2, TimeUnit.HOURS);
        
        // 2. 存储 tripId -> taskId 映射 (供 plan-service 按 tripId 查询进度)
        if (tripId != null) {
            redisTemplate.opsForValue().set("planning:trip:" + tripId + ":taskId", taskId, 2, TimeUnit.HOURS);
        }
        
        // 3. 发布到 Redis Channel (供实时订阅)
        redisTemplate.convertAndSend("planning:progress:channel", progress);

        log.debug("发布进度: taskId={}, percent={}%, stage={}", taskId, percent, stage);
    }

    /**
     * 发布规划完成事件
     */
    public void publishCompletedEvent(String taskId, String tripId, String versionId, 
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
        
        kafkaTemplate.send(EVENTS_TOPIC, tripId, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("发布完成事件失败: {}", ex.getMessage());
                    } else {
                        log.debug("完成事件已发送: taskId={}, partition={}, offset={}", 
                                taskId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * 发布规划失败事件
     */
    public void publishFailedEvent(String taskId, String tripId, String versionId, 
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
        
        kafkaTemplate.send(EVENTS_TOPIC, tripId, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("发布失败事件失败: {}", ex.getMessage());
                    }
                });
    }

    /**
     * 查询进度 (供 HTTP 轮询)
     */
    public Map<String, Object> getProgress(String taskId) {
        String key = PROGRESS_PREFIX + taskId;
        return (Map<String, Object>) redisTemplate.opsForValue().get(key);
    }
}