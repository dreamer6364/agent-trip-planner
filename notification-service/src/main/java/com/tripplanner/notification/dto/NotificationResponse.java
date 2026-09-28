package com.tripplanner.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知响应
 */
@Data
@Builder
public class NotificationResponse {

    private String id;
    private String type; // PROGRESS, COMPLETED, FAILED, REPLAN, SYSTEM, SHARE
    private String title;
    private String content;
    private Map<String, Object> data;
    private boolean read;
    private String priority; // low, normal, high, urgent
    private LocalDateTime createdAt;
}