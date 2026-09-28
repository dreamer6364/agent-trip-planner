package com.tripplanner.auth.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好响应
 */
@Data
@Builder
public class UserPreferenceResponse {

    private String userId;
    private Map<String, Integer> visitDurationDefaults;
    private Map<String, Integer> mealDurations;
    private ActiveWindowResponse activeWindow;
    private List<BlockedPeriodResponse> blockedPeriods;
    private String intensity;
    private List<String> dietaryTags;
    private Boolean accessibility;
    private String homeLocation;
    private String workLocation;
    private List<String> preferredTransportModes;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class ActiveWindowResponse {
        private String start;
        private String end;
    }

    @Data
    @Builder
    public static class BlockedPeriodResponse {
        private String start;
        private String end;
    }
}