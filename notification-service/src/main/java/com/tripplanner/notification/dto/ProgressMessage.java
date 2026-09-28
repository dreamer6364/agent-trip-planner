package com.tripplanner.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 进度消息 (WebSocket 推送)
 */
@Data
@Builder
public class ProgressMessage {

    // 消息类型: PROGRESS, COMPLETED, FAILED, REPLAN
    private String type;

    // 任务 ID
    private String taskId;

    // 行程 ID
    private String tripId;

    // 版本 ID
    private String versionId;

    // 进度 0-100
    private Integer percent;

    // 阶段: PARSE_INPUT, GEOCODE, BUILD_MODEL, SOLVE, ROUTE, PERSIST
    private String stage;

    // 描述信息
    private String message;

    // 结果版本 ID (完成时)
    private String resultVersionId;

    // 结果 URL (完成时)
    private String resultUrl;

    // 错误信息 (失败时)
    private String error;

    // 是否可重试 (失败时)
    private Boolean retryable;

    // 扩展数据
    private Map<String, Object> data;

    // 时间戳
    private LocalDateTime timestamp;
}