package com.tripplanner.plan.agent;

import lombok.Getter;

import java.util.List;

/**
 * 换版规划规格（variant spec）
 *
 * 收拢换版相关参数（是否换版 / 排除列表 / 基准行程摘要），
 * 避免 planDetailedItinerary、buildItineraryPrompt 的签名被
 * variant、excludePois、basePlanSummary 等字段层层加长。
 *
 * @author TripForge Team
 * @since 1.18.0
 */
@Getter
public class VariantSpec {

    /** true=换版规划：主题不变、内容不同 */
    private final boolean variant;

    /** 基准版本已用的景点/餐厅名列表（非换版为空表） */
    private final List<String> excludePois;

    /** 基准版本行程摘要（如「Day1: 西湖 → 午餐·楼外楼 → …」），供 LLM 避开相同组合，可为 null */
    private final String basePlanSummary;

    public VariantSpec(boolean variant, List<String> excludePois, String basePlanSummary) {
        this.variant = variant;
        this.excludePois = excludePois == null ? List.of() : excludePois;
        this.basePlanSummary = basePlanSummary;
    }

    /** 非换版规划（默认路径，零开销 no-op） */
    public static VariantSpec nonVariant() {
        return new VariantSpec(false, List.of(), null);
    }

    /** 换版是否真正生效：开关打开且排除列表非空 */
    public boolean isActive() {
        return variant && excludePois != null && !excludePois.isEmpty();
    }
}
