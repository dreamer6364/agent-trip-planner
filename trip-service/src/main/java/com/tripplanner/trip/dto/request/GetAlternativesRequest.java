package com.tripplanner.trip.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取景点备选项请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetAlternativesRequest {

    /**
     * 行程 ID
     */
    private String tripId;

    /**
     * 活动 ID（可能为空）
     */
    private String activityId;

    /**
     * 活动名称
     */
    private String activityName;

    /**
     * 活动序号
     */
    private Integer seq;

    /**
     * 城市
     */
    private String city;

    /**
     * 活动类型
     */
    private String activityType;

    /**
     * 推荐数量
     */
    private Integer limit;

    /**
     * 预算
     */
    private Double budget;

    /**
     * 搜索半径(km)
     */
    private Double radiusKm;
}
