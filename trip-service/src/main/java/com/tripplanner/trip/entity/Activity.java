package com.tripplanner.trip.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动项实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("activities")
public class Activity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("version_id")
    private String versionId;

    @TableField("seq")
    private Integer seq;

    @TableField("poi_id")
    private String poiId;

    @TableField("poi_name")
    private String poiName;

    @TableField("poi_category")
    private String poiCategory;

    @TableField("poi_location")
    private String poiLocation; // WKT POINT

    @TableField("poi_address")
    private String poiAddress;

    @TableField("activity_type")
    private String activityType; // visit, meal, stay, transit, buffer

    @TableField("priority")
    private String priority; // must, recommended, optional, excluded

    @TableField("time_window_start")
    private LocalDateTime timeWindowStart;

    @TableField("time_window_end")
    private LocalDateTime timeWindowEnd;

    @TableField("scheduled_start")
    private LocalDateTime scheduledStart;

    @TableField("scheduled_end")
    private LocalDateTime scheduledEnd;

    @TableField("duration_min")
    private Integer durationMin;

    @TableField("min_duration")
    private Integer minDuration;

    @TableField("max_duration")
    private Integer maxDuration;

    @TableField("status")
    private String status; // scheduled, removed, conflict

    @TableField("transport_mode")
    private String transportMode;

    @TableField("travel_duration_min")
    private Integer travelDurationMin;

    @TableField("notes")
    private String notes;

    @TableField("slogan")
    private String slogan;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}