package com.tripplanner.trip.dto.response;

import com.tripplanner.common.model.ActivityAlternative;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 景点备选项响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlternativesResponse {

    /**
     * 行程 ID
     */
    private String tripId;

    /**
     * 原活动 ID
     */
    private String activityId;

    /**
     * 原景点名称
     */
    private String originalName;

    /**
     * 原景点类型
     */
    private String originalType;

    /**
     * 备选项列表
     */
    private List<ActivityAlternative> alternatives;

    /**
     * 备选项数量
     */
    private Integer count;

    /**
     * 响应时间
     */
    private LocalDateTime responseTime;
}
