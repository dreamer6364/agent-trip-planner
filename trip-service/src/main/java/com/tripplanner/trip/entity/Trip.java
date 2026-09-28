package com.tripplanner.trip.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 行程主表实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("trips")
public class Trip {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("title")
    private String title;

    @TableField("raw_input")
    private String rawInput;

    @TableField("parsed_input")
    private String parsedInput; // JSON

    @TableField("time_start")
    private LocalDateTime timeStart;

    @TableField("time_end")
    private LocalDateTime timeEnd;

    @TableField("transport_mode")
    private String transportMode;

    @TableField("pace")
    private String pace; // compact(紧凑), moderate(适中), relaxed(宽松)

    @TableField("preferences")
    private String preferences; // JSON

    @TableField("status")
    private String status; // draft, planning, completed, failed, archived

    @TableField("current_version_id")
    private String currentVersionId;

    @TableField("is_public")
    private Boolean isPublic;

    @TableField("share_token")
    private String shareToken;

    @TableField("view_count")
    private Integer viewCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("version")
    private Long version;
}