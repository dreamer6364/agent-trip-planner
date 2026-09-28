package com.tripplanner.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 活动备选项
 * 用于规划后的景点替换推荐
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityAlternative {

    /**
     * 备选项 ID
     */
    private String id;

    /**
     * 备选项名称
     */
    private String name;

    /**
     * 景点类型: scenic, museum, park, temple, shopping, restaurant, other
     */
    private String type;

    /**
     * 推荐理由
     */
    private String reason;

    /**
     * 预计游玩时长（分钟）
     */
    private Integer durationMin;

    /**
     * 优先级: must, recommended, optional
     */
    private String priority;

    /**
     * 地理位置（WKT POINT 格式）
     */
    private String location;

    /**
     * 纬度
     */
    private Double lat;

    /**
     * 经度
     */
    private Double lng;

    /**
     * 地址
     */
    private String address;

    /**
     * 评分（1-5）
     */
    private Double rating;

    /**
     * 图片 URL
     */
    private String imageUrl;

    /**
     * 到原景点的距离（米）
     */
    private Integer distanceFromOriginalMeters;

    /**
     * 到原景点的预计交通时间（分钟）
     */
    private Integer travelTimeFromOriginalMin;

    /**
     * 替换后到前一个景点的距离（米）
     */
    private Integer distanceFromPrevMeters;

    /**
     * 替换后到前一个景点的交通时间（分钟）
     */
    private Integer travelTimeFromPrevMin;

    /**
     * 替换后到后一个景点的距离（米）
     */
    private Integer distanceToNextMeters;

    /**
     * 替换后到后一个景点的交通时间（分钟）
     */
    private Integer travelTimeToNextMin;

    /**
     * 备选项来源: ai_suggest, nearby, user_history
     */
    private String source;

    /**
     * 标签
     */
    private List<String> tags;

    /**
     * 额外信息
     */
    private Map<String, Object> metadata;
}
