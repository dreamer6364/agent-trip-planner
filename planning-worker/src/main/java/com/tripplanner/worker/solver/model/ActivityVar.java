package com.tripplanner.worker.solver.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 求解器中的活动变量
 * 包含约束建模所需的所有属性
 */
@Data
@Builder(toBuilder = true)
public class ActivityVar {

    /**
     * 活动 ID
     */
    private String id;

    /**
     * 在行程中的顺序 (初始顺序，求解后可能调整)
     */
    private int seq;

    /**
     * POI ID
     */
    private String poiId;

    /**
     * 地点名称
     */
    private String name;

    /**
     * 活动类型: visit, meal, stay, transit, buffer
     */
    private String type;

    /**
     * 优先级: must, recommended, optional, excluded
     */
    private String priority;

    /**
     * 坐标 (用于距离计算)
     */
    private double lat;
    private double lng;

    /**
     * 时间窗 (分钟，相对于行程开始)
     * 硬约束：活动必须在此窗口内
     */
    private int earliestMin;   // 最早开始
    private int latestMin;     // 最晚结束

    /**
     * 偏好开始时间 (软约束，用于目标函数)
     */
    private Integer preferredStartMin;

    /**
     * 时长约束 (分钟)
     */
    private int minDurationMin;
    private int preferredDurationMin;
    private int maxDurationMin;

    /**
     * 交通方式 (到达该地点的方式)
     */
    private String transportMode;

    /**
     * 是否为交通活动 (在途)
     */
    private boolean isTransit;

    /**
     * 是否为缓冲活动
     */
    private boolean isBuffer;

    /**
     * 权重 (用于目标函数)
     */
    private int priorityWeight;

    /**
     * 来自高德的路线信息 (enrichRoutes 填充)
     */
    private int travelDurationMin;    // 到下一活动的在途时间(分钟)
    private int travelDistanceM;       // 到下一活动的距离(米)
    private String routePolyline;     // 高德编码的路线坐标串

    /**
     * 餐厅/POI 详情 (v1.15.0 餐厅推荐增强 + 重规划字段透传)
     */
    private String poiAddress;
    private Double rating;
    private String cost;

    /**
     * 固定时间 (增量重规划时使用)
     */
    private FixedTime fixedTime;

    @Data
    @Builder
    public static class FixedTime {
        private int startMin;
        private int endMin;
    }

    /**
     * 判断是否为必去地点
     */
    public boolean isMustVisit() {
        return "must".equalsIgnoreCase(priority);
    }

    /**
     * 判断是否为可选地点
     */
    public boolean isOptional() {
        return "optional".equalsIgnoreCase(priority) || "recommended".equalsIgnoreCase(priority);
    }

    /**
     * 判断是否可被移除
     */
    public boolean isRemovable() {
        return !isMustVisit() && !isTransit && !isBuffer;
    }
}