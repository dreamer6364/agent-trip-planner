package com.tripplanner.plan.service;

import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.plan.entity.PlanTask;
import com.tripplanner.plan.repository.PlanTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 规划事件消费者
 * 消费 planning.events (完成/失败事件)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanEventConsumer {

    private final PlanTaskRepository taskRepository;
    private final PlanTaskService taskService;
    private final JsonUtils jsonUtils;

    @KafkaListener(topics = "planning.events", groupId = "plan-service-events-group")
    public void handlePlanningEvent(Map<String, Object> event, Acknowledgment ack) {
        try {
            String eventType = (String) event.get("eventType"); // PLANNING_COMPLETED, PLANNING_FAILED
            String taskId = (String) event.get("taskId");
            
            log.info("收到规划事件: eventType={}, taskId={}", eventType, taskId);

            PlanTask task = taskRepository.selectById(taskId);
            if (task == null) {
                log.warn("事件对应的任务不存在: taskId={}", taskId);
                ack.acknowledge();
                return;
            }

            switch (eventType) {
                case "PLANNING_COMPLETED" -> {
                    String resultVersionId = (String) event.get("resultVersionId");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> solverStats = (Map<String, Object>) event.get("solverStats");
                    taskService.markCompleted(taskId, resultVersionId, solverStats);
                }
                case "PLANNING_FAILED" -> {
                    String error = (String) event.get("errorMessage");
                    boolean retryable = Boolean.TRUE.equals(event.get("retryable"));
                    taskService.markFailed(taskId, error);
                    if (retryable && task.getRetryCount() < 3) {
                        taskService.incrementRetryAndReset(taskId);
                    }
                }
                default -> log.warn("未知事件类型: {}", eventType);
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("处理规划事件失败: {}", e.getMessage(), e);
            // 不 ack，让 Kafka 重试
        }
    }

    @KafkaListener(topics = "planning.jobs", groupId = "plan-service-jobs-group")
    public void handlePlanningJob(Map<String, Object> job, Acknowledgment ack) {
        // 这个消费者主要由 Planning Worker 使用
        // Plan Service 这里不处理，仅作记录
        log.debug("Plan Service 收到规划任务 (仅记录): taskId={}", job.get("taskId"));
        ack.acknowledge();
    }
}