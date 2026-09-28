package com.tripplanner.notification.service;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.notification.dto.NotificationResponse;
import com.tripplanner.notification.dto.ProgressMessage;
import com.tripplanner.notification.service.NotificationService;
import com.tripplanner.notification.service.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 规划事件消费者
 * 消费 planning.events (PLANNING_COMPLETED, PLANNING_FAILED, REPLAN_COMPLETED, REPLAN_FAILED)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanningEventConsumer {

    private final WebSocketSessionManager sessionManager;
    private final NotificationService notificationService;
    private final JsonUtils jsonUtils;

    @KafkaListener(topics = "planning.events", groupId = "notification-service-group")
    public void handlePlanningEvent(Map<String, Object> event, Acknowledgment ack) {
        try {
            String eventType = (String) event.get("eventType");
            String taskId = (String) event.get("taskId");
            String tripId = (String) event.get("tripId");
            String versionId = (String) event.get("versionId");
            String userId = (String) event.get("userId"); // 事件中应包含 userId

            log.info("收到规划事件: eventType={}, taskId={}, userId={}", eventType, taskId, userId);

            if (userId == null) {
                log.warn("规划事件缺少 userId: event={}", event);
                ack.acknowledge();
                return;
            }

            switch (eventType) {
                case "PLANNING_COMPLETED" -> handleCompleted(event, userId, taskId, tripId, versionId);
                case "PLANNING_FAILED" -> handleFailed(event, userId, taskId, tripId, versionId);
                case "REPLAN_COMPLETED" -> handleReplanCompleted(event, userId, taskId, tripId, versionId);
                case "REPLAN_FAILED" -> handleReplanFailed(event, userId, taskId, tripId, versionId);
                default -> log.warn("未知规划事件类型: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("处理规划事件失败: {}", e.getMessage(), e);
            // 不 ack，让 Kafka 重试
        }
    }

    private void handleCompleted(Map<String, Object> event, String userId, String taskId, 
                                 String tripId, String versionId) {
        String resultVersionId = (String) event.get("resultVersionId");
        String resultUrl = "/api/trips/" + tripId + "/versions/" + resultVersionId;
        
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) event.get("solverStats");

        // 1. 推送完成进度
        ProgressMessage progress = ProgressMessage.builder()
                .type("COMPLETED")
                .taskId((String) event.get("taskId"))
                .tripId(tripId)
                .versionId(versionId)
                .percent(100)
                .stage("COMPLETED")
                .message("行程规划完成")
                .resultVersionId(resultVersionId)
                .resultUrl(resultUrl)
                .data(Map.of("stats", event.get("solverStats")))
                .timestamp(LocalDateTime.now())
                .build();
        sessionManager.sendToUser(userId, "/queue/progress", progress);

        // 2. 创建持久化通知
        notificationService.createAndPush(
                userId,
                "COMPLETED",
                "行程规划完成",
                "您的行程已生成，包含 " + getPlaceCount(stats) + " 个地点",
                Map.of(
                        "taskId", taskId,
                        "tripId", tripId,
                        "versionId", resultVersionId,
                        "resultUrl", resultUrl,
                        "stats", stats
                ),
                "normal"
        );
    }

    private void handleFailed(Map<String, Object> event, String userId, String taskId, 
                              String tripId, String versionId) {
        String error = (String) event.get("errorMessage");
        Boolean retryable = (Boolean) event.get("retryable");

        // 1. 推送失败进度
        ProgressMessage progress = ProgressMessage.builder()
                .type("FAILED")
                .taskId(taskId)
                .tripId(tripId)
                .versionId(versionId)
                .percent(0)
                .stage("FAILED")
                .message("规划失败: " + error)
                .error(error)
                .retryable(retryable)
                .timestamp(LocalDateTime.now())
                .build();
        sessionManager.sendToUser(userId, "/queue/progress", progress);

        // 2. 创建持久化通知
        notificationService.createAndPush(
                userId,
                "FAILED",
                "行程规划失败",
                error + (Boolean.TRUE.equals(retryable) ? " (系统将自动重试)" : ""),
                Map.of(
                        "taskId", taskId,
                        "tripId", tripId,
                        "error", error,
                        "retryable", retryable
                ),
                "high"
        );
    }

    private void handleReplanCompleted(Map<String, Object> event, String userId, String taskId, 
                                       String tripId, String versionId) {
        String resultVersionId = (String) event.get("resultVersionId");
        String resultUrl = "/api/trips/" + tripId + "/versions/" + resultVersionId;

        ProgressMessage progress = ProgressMessage.builder()
                .type("REPLAN_COMPLETED")
                .taskId(taskId)
                .tripId(tripId)
                .versionId(versionId)
                .percent(100)
                .stage("COMPLETED")
                .message("行程重新规划完成")
                .resultVersionId(resultVersionId)
                .resultUrl(resultUrl)
                .timestamp(LocalDateTime.now())
                .build();
        sessionManager.sendToUser(userId, "/queue/progress", progress);

        notificationService.createAndPush(
                userId,
                "REPLAN_COMPLETED",
                "行程重新规划完成",
                "已根据您的反馈调整行程",
                Map.of(
                        "taskId", taskId,
                        "tripId", tripId,
                        "versionId", resultVersionId,
                        "resultUrl", resultUrl
                ),
                "normal"
        );
    }

    private void handleReplanFailed(Map<String, Object> event, String userId, String taskId, 
                                    String tripId, String versionId) {
        String error = (String) event.get("errorMessage");

        ProgressMessage progress = ProgressMessage.builder()
                .type("REPLAN_FAILED")
                .taskId(taskId)
                .tripId(tripId)
                .versionId(versionId)
                .percent(0)
                .stage("FAILED")
                .message("重规划失败: " + error)
                .error(error)
                .retryable(true)
                .timestamp(LocalDateTime.now())
                .build();
        sessionManager.sendToUser(userId, "/queue/progress", progress);

        notificationService.createAndPush(
                userId,
                "REPLAN_FAILED",
                "行程重新规划失败",
                error,
                Map.of("taskId", taskId, "tripId", tripId, "error", error),
                "high"
        );
    }

    private int getPlaceCount(Map<String, Object> stats) {
        if (stats == null) return 0;
        Object count = stats.get("placeCount");
        if (count instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) count;
            return ((Number) c.getOrDefault("must", 0)).intValue() +
                   ((Number) c.getOrDefault("recommended", 0)).intValue() +
                   ((Number) c.getOrDefault("optional", 0)).intValue();
        }
        return 0;
    }
}