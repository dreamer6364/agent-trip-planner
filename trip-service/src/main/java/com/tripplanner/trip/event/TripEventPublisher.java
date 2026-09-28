package com.tripplanner.trip.event;

import com.tripplanner.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 行程事件发布器
 * 异步发送规划任务到 Kafka（Kafka不可用时不影响主流程）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonUtils jsonUtils;

    private static final String PLANNING_JOBS_TOPIC = "planning.jobs";
    private static final String REPLAN_JOBS_TOPIC = "planning.replan.jobs";
    private final ExecutorService kafkaExecutor = Executors.newFixedThreadPool(2);

    /**
     * 异步发布规划任务（不阻塞主流程）
     */
    public void publishPlanningTask(String tripId, String versionId, com.tripplanner.trip.dto.request.CreateTripRequest request) {
        PlanningJob job = new PlanningJob();
        job.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        job.setTripId(tripId);
        job.setVersionId(versionId);
        job.setTaskType("plan");
        job.setCreatedAt(LocalDateTime.now());

        if (request != null) {
            var input = new java.util.HashMap<String, Object>();
            input.put("rawInput", request.getRawInput() != null ? request.getRawInput() : "");
            input.put("timeStart", request.getTimeStart());
            input.put("timeEnd", request.getTimeEnd());
            input.put("transportMode", request.getTransportMode() != null ? request.getTransportMode() : "mixed");
            if (request.getPreferences() != null) input.put("preferences", request.getPreferences());
            if (request.getPlaces() != null) input.put("places", request.getPlaces());
            job.setInput(jsonUtils.toJson(input));
        } else {
            job.setInput("{}");
        }

        kafkaExecutor.submit(() -> {
            try {
                kafkaTemplate.send(PLANNING_JOBS_TOPIC, tripId, job);
                log.info("规划任务已提交(异步): tripId={}", tripId);
            } catch (Exception e) {
                log.warn("Kafka不可用(不影响创建行程): tripId={}, error={}", tripId, e.getMessage());
            }
        });
    }

    /**
     * 异步发布重新规划任务
     */
    public void publishReplanTask(String tripId, String versionId, com.tripplanner.trip.dto.request.CreateVersionRequest request) {
        PlanningJob job = new PlanningJob();
        job.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        job.setTripId(tripId);
        job.setVersionId(versionId);
        job.setTaskType("replan");
        job.setCreatedAt(LocalDateTime.now());
        job.setFeedback(request.getFeedback());
        job.setBaseVersionId(request.getBaseVersionId());
        job.setChanges(request.getChanges());

        kafkaExecutor.submit(() -> {
            try {
                kafkaTemplate.send(REPLAN_JOBS_TOPIC, tripId, job);
                log.info("重新规划任务已提交(异步): tripId={}", tripId);
            } catch (Exception e) {
                log.warn("Kafka不可用(不影响重新规划): tripId={}, error={}", tripId, e.getMessage());
            }
        });
    }

    public static class PlanningJob {
        private String taskId;
        private String tripId;
        private String versionId;
        private String taskType;
        private String input;
        private String feedback;
        private String baseVersionId;
        private Object changes;
        private LocalDateTime createdAt;

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getTripId() { return tripId; }
        public void setTripId(String tripId) { this.tripId = tripId; }
        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
        public String getBaseVersionId() { return baseVersionId; }
        public void setBaseVersionId(String baseVersionId) { this.baseVersionId = baseVersionId; }
        public Object getChanges() { return changes; }
        public void setChanges(Object changes) { this.changes = changes; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}
