package com.tripplanner.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 异步任务响应（用于规划任务提交后的即时返回）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 任务状态：pending, running, completed, failed
     */
    private String status;

    /**
     * 轮询查询进度的 URL
     */
    private String pollUrl;

    /**
     * WebSocket 连接 URL
     */
    private String wsUrl;

    /**
     * 预估耗时（秒）
     */
    private Integer estimatedSeconds;

    /**
     * 进度百分比（0-100）
     */
    private Integer progress;

    /**
     * 当前阶段
     */
    private String stage;
}