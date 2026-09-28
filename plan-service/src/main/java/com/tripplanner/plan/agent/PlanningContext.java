package com.tripplanner.plan.agent;

import com.tripplanner.plan.constant.TripPace;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 行程后处理管道上下文
 *
 * 承载主路径（LLM 产物）与回退路径（本地基础行程）共用的规划状态，
 * 避免 rawInput/places/meals/city/pace/variant/excludePois 等参数
 * 在步骤链与方法签名间层层穿透。
 *
 * @author TripForge Team
 * @since 1.18.0
 */
@Getter
@AllArgsConstructor
public class PlanningContext {

    /** 用户原始需求文本 */
    private final String rawInput;

    /** 预处理完成的景点列表（换版排除与候选补充已生效） */
    private final List<Map<String, Object>> places;

    /** 预处理完成的餐次列表（口味偏好与换版排除已生效） */
    private final List<Map<String, Object>> meals;

    /** 行程开始日期 yyyy-MM-dd */
    private final String timeStart;

    /** 行程结束日期 yyyy-MM-dd */
    private final String timeEnd;

    /** 规划城市（可能为空串） */
    private final String city;

    /** 活动节奏 */
    private final TripPace pace;

    /** true=换版规划（主题不变、内容不同） */
    private final boolean variant;

    /** 换版排除：基准版本已用的景点/餐厅名列表（非换版为空） */
    private final List<String> excludePois;

    /**
     * 换版修复候选池（可变引用，与调用方共享）：
     * 候选补充的剩余候选供 enforceVariantExclusions 违规替换取用
     */
    private final List<Map<String, Object>> repairPool;
}
