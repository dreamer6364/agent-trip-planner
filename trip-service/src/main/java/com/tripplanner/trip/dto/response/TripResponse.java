package com.tripplanner.trip.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 行程响应
 */
@Data
@Builder
public class TripResponse {

    private String id;
    private String userId;
    private String title;
    private String rawInput;
    private Object parsedInput; // JSON object
    private LocalDateTime timeStart;
    private LocalDateTime timeEnd;
    private String transportMode;
    private String pace; // 活动频率：compact(紧凑 8~10小时/天), moderate(适中 6~8小时/天), relaxed(宽松 3~5小时/天)
    private Object preferences; // JSON object
    private String status;
    private String currentVersionId;
    private Boolean isPublic;
    private String shareToken;
    private Integer viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 列表页轻量展示字段
    private String city;
    private Integer activityCount;
    private List<String> landmarks;

    // 关联的最新版本信息 (可选)
    private TripVersionResponse latestVersion;
}