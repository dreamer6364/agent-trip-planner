package com.tripplanner.auth.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 更新偏好请求 (全量/部分更新通用)
 * 字段为 null 表示不更新该字段
 */
@Data
public class UpdatePreferenceRequest {

    // 游玩时长默认值：{ "scenic": 120, "museum": 180 }
    private Map<String, Integer> visitDurationDefaults;

    // 餐饮时长：{ "breakfast": 60, "lunch": 90, "dinner": 120 }
    private Map<String, Integer> mealDurations;

    // 活动时间窗：{ "start": "08:00", "end": "23:00" }
    private ActiveWindowRequest activeWindow;

    // 不可安排时段：[{"start": "13:00", "end": "14:00"}]
    private List<BlockedPeriodRequest> blockedPeriods;

    // 强度：relaxed, standard, packed
    @Pattern(regexp = "^(relaxed|standard|packed)$", message = "强度必须是 relaxed/standard/packed 之一")
    private String intensity;

    // 饮食标签
    private List<String> dietaryTags;

    // 无障碍需求
    private Boolean accessibility;

    // 家庭位置 WKT: "POINT(120.15 30.24)"
    private String homeLocation;

    // 工作位置 WKT
    private String workLocation;

    // 偏好交通方式顺序
    private List<String> preferredTransportModes;

    @Data
    public static class ActiveWindowRequest {
        @NotNull(message = "开始时间不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间格式必须为 HH:mm")
        private String start;

        @NotNull(message = "结束时间不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间格式必须为 HH:mm")
        private String end;
    }

    @Data
    public static class BlockedPeriodRequest {
        @NotNull(message = "开始时间不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间格式必须为 HH:mm")
        private String start;

        @NotNull(message = "结束时间不能为空")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "时间格式必须为 HH:mm")
        private String end;
    }
}