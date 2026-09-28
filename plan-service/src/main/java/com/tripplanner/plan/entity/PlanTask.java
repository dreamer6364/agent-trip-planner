package com.tripplanner.plan.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 规划任务实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("planning_tasks")
public class PlanTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("trip_id")
    private String tripId;

    @TableField("version_id")
    private String versionId;

    @TableField("task_type")
    private String taskType; // plan, replan

    @TableField("status")
    private String status; // pending, running, completed, failed, dead_letter

    @TableField("progress")
    private Integer progress;

    @TableField("stage")
    private String stage; // parse_input, geocode, build_model, solve, route, persist

    @TableField("input_snapshot")
    private String inputSnapshot; // JSON

    @TableField("result_version_id")
    private String resultVersionId;

    @TableField("error_message")
    private String errorMessage;

    @TableField("solver_stats")
    private String solverStats; // JSON

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @Version
    @TableField("version")
    private Long version;

    // Explicit getters and setters (in case Lombok doesn't generate them)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getInputSnapshot() { return inputSnapshot; }
    public void setInputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; }

    public String getResultVersionId() { return resultVersionId; }
    public void setResultVersionId(String resultVersionId) { this.resultVersionId = resultVersionId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getSolverStats() { return solverStats; }
    public void setSolverStats(String solverStats) { this.solverStats = solverStats; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}