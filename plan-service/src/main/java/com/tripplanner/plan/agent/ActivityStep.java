package com.tripplanner.plan.agent;

import java.util.List;
import java.util.Map;

/**
 * 行程后处理步骤：后处理管道中的一环
 *
 * 输入活动列表、输出活动列表；通过 {@link TripPlanningAgent} 的后处理管道
 * 顺序执行，主路径与回退路径共用同一步骤序列。
 *
 * @author TripForge Team
 * @since 1.18.0
 */
@FunctionalInterface
interface ActivityStep {

    /**
     * 执行本步骤的后处理
     *
     * @param activities 当前活动列表
     * @param context    管道上下文（只读状态 + 共享修复池）
     * @return 处理后的活动列表（步骤可返回原列表表示无变化）
     */
    List<Map<String, Object>> apply(List<Map<String, Object>> activities, PlanningContext context);
}
