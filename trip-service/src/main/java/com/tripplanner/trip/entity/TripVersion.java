package com.tripplanner.trip.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 行程版本实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("trip_versions")
public class TripVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("trip_id")
    private String tripId;

    @TableField("version_num")
    private Integer versionNum;

    @TableField("parent_version_id")
    private String parentVersionId;

    @TableField("activities")
    private String activities; // JSON

    @TableField("routes")
    private String routes; // JSON

    @TableField("conflicts")
    private String conflicts; // JSON

    @TableField("stats")
    private String stats; // JSON

    @TableField("feedback")
    private String feedback;

    @TableField("solver_meta")
    private String solverMeta; // JSON

    @TableField("status")
    private String status; // draft, planning, completed, failed

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField("version")
    private Long version;
}