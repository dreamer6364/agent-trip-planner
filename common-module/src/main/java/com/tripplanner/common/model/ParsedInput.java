package com.tripplanner.common.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 解析后的结构化输入
 * 供 Plan Service 和 Planning Worker 共用
 */
@Data
@Builder
public class ParsedInput {

    /**
     * 地点列表
     */
    private List<PlaceInfo> places;

    /**
     * 餐饮偏好
     */
    private List<MealInfo> meals;

    /**
     * 时间范围
     */
    private TimeRange timeRange;

    /**
     * 交通方式
     */
    private String transportMode;

    /**
     * 用户偏好配置
     */
    private UserPreferences preferences;

    /**
     * 原始文本 (用于调试)
     */
    private String rawInput;

    @Data
    @Builder
    public static class PlaceInfo {
        private String name;
        private String priority;        // must, recommended, optional, excluded
        private String type;            // scenic, hotel, shopping, transport, park, temple, museum（餐厅不进 places）
        private Integer preferredDurationMin;
        private Integer minDurationMin;
        private Integer maxDurationMin;
        private String notes;
        private String address;         // 可选：用户提供的地址
        private Double lat;             // 可选：用户提供的坐标
        private Double lng;
    }

    @Data
    @Builder
    public static class MealInfo {
        private String type;            // breakfast, lunch, dinner
        private String preference;      // 用餐偏好描述
        private String restaurant;      // 用户指定的具体餐厅（归入该餐次，不进 places）
        private Integer durationMin;    // 预留时长
    }

    @Data
    @Builder
    public static class TimeRange {
        private LocalDateTime start;
        private LocalDateTime end;
        private String timezone;        // 目标时区
    }

    @Data
    @Builder
    public static class UserPreferences {
        private Map<String, Integer> visitDurationDefaults;  // 按类型的默认游玩时长
        private Map<String, Integer> mealDurations;          // 各餐时长
        private ActiveWindow activeWindow;                   // 可安排活动窗口
        private List<BlockedPeriod> blockedPeriods;          // 不可安排时段
        private String intensity;                            // relaxed, standard, packed
        private List<String> dietaryTags;                    // 饮食标签
        private Boolean accessibility;                       // 无障碍需求
        private String homeLocation;                         // WKT POINT
        private String workLocation;                         // WKT POINT
        private List<String> preferredTransportModes;        // 偏好交通方式顺序
    }

    @Data
    @Builder
    public static class ActiveWindow {
        private String start;  // HH:mm
        private String end;    // HH:mm
    }

    @Data
    @Builder
    public static class BlockedPeriod {
        private String start;  // HH:mm
        private String end;    // HH:mm
    }
}