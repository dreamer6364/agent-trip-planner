package com.tripplanner.trip.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 替换景点请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceActivityRequest {

    /**
     * 行程 ID
     */
    private String tripId;

    /**
     * 要替换的活动 ID（可能为空，因JSON活动可能没有id字段）
     */
    private String activityId;

    /**
     * 要替换的活动名称
     */
    private String activityName;

    /**
     * 活动序列号（用于定位）
     */
    private Integer seq;

    /**
     * 备选项 ID
     */
    private String alternativeId;

    /**
     * 备选项名称（冗余存储，便于验证）
     */
    private String alternativeName;

    /**
     * 目标 POI 纬度（搜索换入时携带，可空——空则后端自行地理编码解析）
     */
    private Double lat;

    /**
     * 目标 POI 经度（搜索换入时携带）
     */
    private Double lng;

    /**
     * 目标 POI 地址（搜索换入时携带）
     */
    private String address;

    /**
     * 是否自动调整后续活动时间
     */
    private Boolean autoAdjust;

    /**
     * 用户备注
     */
    private String notes;
}
