package com.tripplanner.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户偏好实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("user_preferences")
public class UserPreference {

    @TableId(type = IdType.INPUT)
    private String userId;

    @TableField("visit_duration_defaults")
    private String visitDurationDefaults; // JSON

    @TableField("meal_durations")
    private String mealDurations; // JSON

    @TableField("active_window")
    private String activeWindow; // JSON

    @TableField("blocked_periods")
    private String blockedPeriods; // JSON

    @TableField("intensity")
    private String intensity; // relaxed, standard, packed

    @TableField("dietary_tags")
    private String dietaryTags; // JSON

    @TableField("accessibility")
    private Boolean accessibility;

    @TableField("home_location")
    private String homeLocation; // WKT POINT

    @TableField("work_location")
    private String workLocation; // WKT POINT

    @TableField("preferred_transport_modes")
    private String preferredTransportModes; // JSON

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("version")
    private Long version;
}