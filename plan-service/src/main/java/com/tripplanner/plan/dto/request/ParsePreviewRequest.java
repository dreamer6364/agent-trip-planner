package com.tripplanner.plan.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 解析预览请求
 */
public class ParsePreviewRequest {

    @NotBlank(message = "行程描述不能为空")
    private String rawInput;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime timeStart;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime timeEnd;

    private String transportMode = "mixed";

    private Map<String, Object> preferences;

    // Getters and Setters
    public String getRawInput() { return rawInput; }
    public void setRawInput(String rawInput) { this.rawInput = rawInput; }

    public LocalDateTime getTimeStart() { return timeStart; }
    public void setTimeStart(LocalDateTime timeStart) { this.timeStart = timeStart; }

    public LocalDateTime getTimeEnd() { return timeEnd; }
    public void setTimeEnd(LocalDateTime timeEnd) { this.timeEnd = timeEnd; }

    public String getTransportMode() { return transportMode; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }

    public Map<String, Object> getPreferences() { return preferences; }
    public void setPreferences(Map<String, Object> preferences) { this.preferences = preferences; }
}