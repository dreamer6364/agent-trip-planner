package com.tripplanner.plan.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 规划任务进度响应
 */
public class PlanTaskProgressResponse {

    private String taskId;
    private String tripId;
    private String versionId;
    private String taskType;
    private String status; // pending, running, completed, failed
    private int progress;  // 0-100
    private String stage;  // parse_input, geocode, build_model, solve, route, persist
    private String message; // 当前阶段描述
    private java.util.Map<String, Object> solverStats; // 求解器统计
    private String errorMessage;
    private String resultVersionId;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime completedAt;
    private java.time.LocalDateTime createdAt;
    private int retryCount;

    public PlanTaskProgressResponse() {}

    // Getters and Setters
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public java.util.Map<String, Object> getSolverStats() { return solverStats; }
    public void setSolverStats(java.util.Map<String, Object> solverStats) { this.solverStats = solverStats; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getResultVersionId() { return resultVersionId; }
    public void setResultVersionId(String resultVersionId) { this.resultVersionId = resultVersionId; }

    public java.time.LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(java.time.LocalDateTime startedAt) { this.startedAt = startedAt; }

    public java.time.LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(java.time.LocalDateTime completedAt) { this.completedAt = completedAt; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    // Static factory method
    public static PlanTaskProgressResponse of(String taskId, String tripId, String versionId,
            String taskType, String status, int progress, String stage, String message,
            java.util.Map<String, Object> solverStats, String errorMessage,
            String resultVersionId, java.time.LocalDateTime startedAt,
            java.time.LocalDateTime completedAt, java.time.LocalDateTime createdAt,
            int retryCount) {
        PlanTaskProgressResponse resp = new PlanTaskProgressResponse();
        resp.taskId = taskId;
        resp.tripId = tripId;
        resp.versionId = versionId;
        resp.taskType = taskType;
        resp.status = status;
        resp.progress = progress;
        resp.stage = stage;
        resp.message = message;
        resp.solverStats = solverStats;
        resp.errorMessage = errorMessage;
        resp.resultVersionId = resultVersionId;
        resp.startedAt = startedAt;
        resp.completedAt = completedAt;
        resp.createdAt = createdAt;
        resp.retryCount = retryCount;
        return resp;
    }
}