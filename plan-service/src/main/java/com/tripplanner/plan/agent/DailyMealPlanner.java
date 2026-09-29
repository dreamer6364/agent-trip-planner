package com.tripplanner.plan.agent;

import java.util.List;

/**
 * 每日午/晚餐时间保底策略（纯函数，无外部依赖）
 *
 * <p>只要时间允许，每一天都必须有午餐与晚餐。本策略为缺失的正餐计算插入位置：</p>
 * <ol>
 *   <li><b>零打扰优先</b>：目标时刻（午 11:30 / 晚 17:30）落入现有活动间的空隙
 *       且能容纳 60 分钟正餐时，直接安插，不动任何既有活动；</li>
 *   <li><b>级联推演兜底</b>：空隙不足时，按与 {@code fixTimeOverlaps} 同构的规则
 *       后推后续活动（起点 ≥ 上一活动结束 + 上一段路程），推演越过 21:00 即判定
 *       「时间不允许」并放弃（宁缺一餐，不删景点）。</li>
 * </ol>
 *
 * <p>结果供 {@code TripPlanningAgent#ensureDailyMeals} 落位；命名、去重口径、
 * 餐厅挑选等有状态逻辑留在 Agent 内。</p>
 */
public final class DailyMealPlanner {

    /** 正餐时长（分钟） */
    public static final int MEAL_DURATION_MIN = 60;
    /** 每日作息结束时刻（分钟） */
    public static final int DAY_END_MIN = 21 * 60;
    /** 默认日窗口起点 08:00（分钟） */
    public static final int DEFAULT_FLOOR_MIN = 8 * 60;
    /** 到下一段的默认路程分钟（与 newAct 默认一致） */
    public static final int DEFAULT_TRAVEL_MIN = 15;

    private DailyMealPlanner() {
    }

    /**
     * 当日一条活动（按 startMin 升序传入）
     *
     * @param startMin  开始分钟（0-1439）
     * @param durMin    时长分钟（&gt; 0）
     * @param travelMin 该活动到下一段的路程分钟
     */
    public record Item(int startMin, int durMin, int travelMin) {
    }

    /**
     * 插入方案
     *
     * @param index     插入下标（新餐排在第 index 位活动之前；按 startMin 稳定重排即可落位）
     * @param startMin  正餐原始开始时刻（目标时刻；与上一活动的空隙不足时由
     *                  fixTimeOverlaps 按「上一活动结束 + 路程」顺延到实际位置——
     *                  原始值恒小于下一活动开始，保证重排顺序与推演一致）
     * @param travelMin 正餐到下一段的路程分钟（零打扰时按剩余空隙收缩，可为 0）
     */
    public record Placement(int index, int startMin, int travelMin) {
    }

    /**
     * 为缺失的正餐计算插入位置
     *
     * @param items       当日活动，须按 startMin 升序
     * @param lunch       true=午餐（目标 11:30，最晚 15:00 落位）；false=晚餐（17:30 / 20:00）
     * @param dayFloorMin 当日最早可安排时刻：首日取 max(08:00, 出发时刻)，其余天 08:00
     * @return 插入方案；时间不允许返回 null
     */
    public static Placement plan(List<Item> items, boolean lunch, int dayFloorMin) {
        int target = lunch ? 11 * 60 + 30 : 17 * 60 + 30;
        int latestStart = lunch ? 15 * 60 : 20 * 60;
        int floor = Math.max(0, dayFloorMin);

        int start0 = Math.max(target, floor);
        if (start0 > latestStart) return null;
        if (start0 + MEAL_DURATION_MIN > DAY_END_MIN) return null;

        int n = items == null ? 0 : items.size();
        int idx = 0;
        while (idx < n && items.get(idx).startMin() <= start0) {
            idx++;
        }
        // 空隙：[上一活动结束 + 其到下一段路程, 下一活动开始]；首/尾活动分别以日窗口为界
        int gapStart = idx == 0 ? floor
                : items.get(idx - 1).startMin() + items.get(idx - 1).durMin()
                        + Math.max(0, items.get(idx - 1).travelMin());
        int gapEnd = idx == n ? DAY_END_MIN : items.get(idx).startMin();

        // 实际落位 = max(目标, 空隙起点)：用于时间允许判定与空隙余量计算
        int eff = Math.max(start0, gapStart);
        if (eff > latestStart || eff + MEAL_DURATION_MIN > DAY_END_MIN) {
            return null;
        }
        // 零打扰：空隙容得下一整餐（到下一段的路程按剩余空隙收缩，绝不挤动后续活动）
        if (eff + MEAL_DURATION_MIN <= gapEnd) {
            int slack = gapEnd - (eff + MEAL_DURATION_MIN);
            return new Placement(idx, start0, Math.min(DEFAULT_TRAVEL_MIN, Math.max(0, slack)));
        }
        // 级联推演：先按默认路程试，放不下再按 0 路程试；仍越过 21:00 则时间不允许
        for (int travel : new int[]{DEFAULT_TRAVEL_MIN, 0}) {
            if (cascadeFits(items, idx, eff, travel)) {
                return new Placement(idx, start0, travel);
            }
        }
        return null;
    }

    /** 按 fixTimeOverlaps 同构规则推演：插入正餐后，后续活动只后推且均能在 21:00 前结束 */
    private static boolean cascadeFits(List<Item> items, int idx, int startMin, int travelMin) {
        int cur = startMin + MEAL_DURATION_MIN + travelMin;
        for (int j = idx; j < items.size(); j++) {
            Item it = items.get(j);
            int st = Math.max(it.startMin(), cur);
            if (st + it.durMin() > DAY_END_MIN) {
                return false;
            }
            cur = st + it.durMin() + Math.max(0, it.travelMin());
        }
        return true;
    }
}
