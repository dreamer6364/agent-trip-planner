package com.tripplanner.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 通知实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("type")
    private String type; // PROGRESS, COMPLETED, FAILED, REPLAN, SYSTEM, SHARE

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("data")
    private String data; // JSON 扩展数据

    @TableField("read")
    private Boolean read;

    @TableField("priority")
    private String priority; // low, normal, high, urgent

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @Version
    @TableField("version")
    private Long version;
}