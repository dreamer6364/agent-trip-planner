package com.tripplanner.plan.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 每日午/晚餐时间保底策略（纯函数，无外部依赖）
 *
 * <p>只要时间允许，每一天都必须有午餐与晚餐。本策略为缺失的正餐计算插入位置：</p>
 * <ol>
 *   <li><b>多候选扫描</b>：在作息窗口（午 11:00-13:30 / 晚 17:00-19:30）内按 5 分钟
 *       步长生成候选时刻（按与目标时刻的距离排序），逐候选尝试两种插入位——
 *       现有活动之后的空隙位、以及候选时刻落在某活动区间内时该活动之前的跨位；</li>
 *   <li><b>零打扰优先</b>：候选落入现有活动间的空隙且能容纳 60 分钟正餐时直接安插，
 *       不动任何既有活动；</li>
 *   <li><b>级联推演</b>：空隙不足时按与 {@code fixTimeOverlaps} 同构的规则后推后续
 *       活动（起点 ≥ 上一活动结束 + 上一段路程），推演越过 21:00 即放弃该候选；</li>
 *   <li><b>跨活动插入</b>：候选时刻被某个长活动覆盖时，把正餐插到该活动之前并
 *       整段右推——修复「上一活动结束晚于 15:00/20:00 即放弃」导致的丢餐；</li>
 *   <li><b>窗口右缘收紧</b>：正常日历日实际落位不得晚于窗口右缘（午 13:30 /
 *       晚 19:30），防止「上一活动结束 + 路程」把正餐顶出作息窗口；仅当首日
 *       出发时刻本身晚于右缘时才退化为旧口径（午 15:00 / 晚 20:00）；</li>
 *   <li><b>旅行切分</b>：插入空隙取「上一活动结束 + min(原路程, 默认 15 分钟)」——
 *       长路程（如景区 → 市区 58 分钟）不再整段计入正餐之前的空隙起点，
 *       正餐可在窗口内落位（落位侧由 {@code ensureDailyMeals} 同步把上一活动
 *       路程收缩为到正餐的步行段，维持 REST-4 相邻不变式）。</li>
 * </ol>
 *
     * <p>全部候选均无法在 21:00 前收口才判定「时间不允许」（宁缺一餐，不删景点）。
     * 结果供 {@code TripPlanningAgent#ensureDailyMeals} 落位；命名、去重口径、
     * 餐厅挑选等有状态逻辑留在 Agent 内。</p>
     *
     * <p>1.31.0 起提供 {@link #planForced} 强制入窗变体：常规 {@link #plan} 判空后调用，
     * 级联允许越过 21:00（由调用侧裁掉尾部游览），正餐实际落位仍不得晚于窗口右缘
     * （午 13:30 / 晚 19:30，首日晚出发退化为 15:00 / 20:00），即
     * 「宁越 21:00 收口，不出作息窗口、不丢正餐」（见 BUGFIX 1.31.0）。</p>
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
    /** 候选时刻步长（分钟） */
    private static final int CANDIDATE_STEP_MIN = 5;

    private DailyMealPlanner() {
    }

    /**
     * 作息窗口起点（分钟）
     *
     * @param lunch true=午餐 11:00；false=晚餐 17:00
     */
    public static int winStart(boolean lunch) {
        return lunch ? 11 * 60 : 17 * 60;
    }

    /**
     * 作息窗口右缘（分钟）：正常日历日正餐实际落位上限，也是出窗判定的边界
     *
     * @param lunch true=午餐 13:30；false=晚餐 19:30
     */
    public static int winEnd(boolean lunch) {
        return lunch ? 13 * 60 + 30 : 19 * 60 + 30;
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
     * @param startMin  正餐原始开始时刻（候选时刻；与上一活动的空隙不足或跨位插入时由
     *                  fixTimeOverlaps 按「上一活动结束 + 路程」顺延到实际位置——
     *                  原始值恒小于下一活动开始，保证重排顺序与推演一致）
     * @param travelMin 正餐到下一段的路程分钟（零打扰时按剩余空隙收缩，可为 0）
     * @param forced    true=非零打扰落位（级联右推或跨活动插入后重排得到）
     */
    public record Placement(int index, int startMin, int travelMin, boolean forced) {
        public Placement(int index, int startMin, int travelMin) {
            this(index, startMin, travelMin, false);
        }
    }

    /**
     * 为缺失的正餐计算插入位置
     *
     * @param items       当日活动，须按 startMin 升序
     * @param lunch       true=午餐（窗口 11:00-13:30，目标 11:30）；false=晚餐（17:00-19:30 / 17:30）
     * @param dayFloorMin 当日最早可安排时刻：首日取 max(08:00, 出发时刻)，其余天 08:00
     * @return 插入方案；窗口右缘内无槽且 21:00 前无任何 60 分钟槽时返回 null
     */
    public static Placement plan(List<Item> items, boolean lunch, int dayFloorMin) {
        return planInternal(items, lunch, dayFloorMin, false);
    }

    /**
     * 强制入窗变体：与 {@link #plan} 同候选、同窗口右缘约束，但级联允许越过 21:00
     *
     * <p>供 {@code ensureDailyMeals} 在常规判空（21:00 前无任何可收口槽）后兜底：
     * 正餐强制落位到窗口内，越界的尾部游览由调用侧裁掉（宁越 21:00 不出窗、不丢餐）。
     * 仍无法满足（首个落位时刻已晚于窗口右缘，如首日出发过晚）才返回 null。</p>
     *
     * @param items       当日活动，须按 startMin 升序
     * @param lunch       true=午餐；false=晚餐
     * @param dayFloorMin 当日最早可安排时刻
     * @return 插入方案（forced=true 或零打扰落位）；窗口右缘内无法落位返回 null
     */
    public static Placement planForced(List<Item> items, boolean lunch, int dayFloorMin) {
        return planInternal(items, lunch, dayFloorMin, true);
    }

    /**
     * 插入方案主实现
     *
     * @param allowOverrun true=强制入窗模式：级联与候选的 21:00 收口约束放开（窗口右缘仍生效）
     */
    private static Placement planInternal(List<Item> items, boolean lunch, int dayFloorMin,
            boolean allowOverrun) {
        int target = lunch ? 11 * 60 + 30 : 17 * 60 + 30;
        int winStart = winStart(lunch);
        int winEnd = winEnd(lunch);
        int latestStart = lunch ? 15 * 60 : 20 * 60;
        int floor = Math.max(0, dayFloorMin);
        // 窗口右缘约束：正常日历日实际落位 ≤ 窗口右缘（午 13:30 / 晚 19:30），
        // 防止「上一活动结束 + 路程」把正餐顶出作息窗口；首日出发已晚于右缘时
        // 退化为旧口径（午 15:00 / 晚 20:00），保住「出发晚也能补餐」的行为
        int windowCap = floor > winEnd ? latestStart : winEnd;

        int n = items == null ? 0 : items.size();

        // 候选时刻：窗口内 5min 步长 + 目标时刻，按与目标距离排序（目标优先、同距靠前优先）
        List<Integer> candidates = new ArrayList<>();
        int candLo = Math.max(winStart, floor);
        if (candLo <= winEnd) {
            for (int t = candLo; t <= winEnd; t += CANDIDATE_STEP_MIN) {
                candidates.add(t);
            }
            if (target >= candLo && target <= winEnd && !candidates.contains(target)) {
                candidates.add(target);
            }
        } else {
            // 首日出发晚于窗口：退化为「不早于出发时刻」的单一下界候选（保持旧行为）
            candidates.add(Math.max(target, floor));
        }
        candidates.sort(Comparator.comparingInt(t -> Math.abs(t - target)));

        Placement bestForced = null;
        for (int t : candidates) {
            if (!allowOverrun && t + MEAL_DURATION_MIN > DAY_END_MIN) continue;

            // 插入位 A：候选之后的空隙（idx = 首个 start > t 的活动）
            int idx = 0;
            while (idx < n && items.get(idx).startMin() <= t) {
                idx++;
            }
            Placement p = tryPosition(items, idx, t, floor, n, windowCap, allowOverrun);
            if (p != null && !p.forced()) {
                return p;
            }
            if (p != null && bestForced == null) {
                bestForced = p;
            }

            // 插入位 B：t 落在活动 [start, end) 区间内 → 插到该活动之前并右推
            int straddle = idx - 1;
            if (straddle >= 0 && items.get(straddle).startMin() <= t
                    && t < items.get(straddle).startMin() + items.get(straddle).durMin()) {
                Placement q = tryPosition(items, straddle, t, floor, n, windowCap, allowOverrun);
                if (q != null && !q.forced()) {
                    return q;
                }
                if (q != null && bestForced == null) {
                    bestForced = q;
                }
            }
        }
        return bestForced;
    }

    /**
     * 在插入位 {@code idx} 试落候选时刻 {@code t}：先零打扰，后级联右推
     *
     * @param idx           新餐排在第 idx 位活动之前（idx 可等于 n，即尾部空隙）
     * @param t             候选开始时刻
     * @param floor         当日最早可安排时刻（首间隙下界）
     * @param n             活动总数
     * @param windowCap     最晚落位（实际开始时刻上限：正常日 13:30/19:30；首日晚出发退化为 15:00/20:00）
     * @param allowOverrun  true=强制入窗：级联/候选放开 21:00 收口约束（窗口右缘仍生效）
     * @return 方案；实际落位晚于窗口右缘（或非强制模式下无法在 21:00 前收口）返回 null
     */
    private static Placement tryPosition(List<Item> items, int idx, int t, int floor, int n,
            int windowCap, boolean allowOverrun) {
        // 空隙：[上一活动结束 + 到正餐的路程, 下一活动开始]；首/尾活动分别以日窗口为界
        // 旅行切分：到正餐的路程取 min(原路程, 默认 15 分钟)——长路程（景区→市区）
        // 不再整段压在正餐之前，剩余段由正餐到下一段的路程承担（落位侧同步收缩）
        int gapStart = idx == 0 ? floor
                : items.get(idx - 1).startMin() + items.get(idx - 1).durMin()
                        + travelToMeal(items.get(idx - 1));
        // 跨位插入时下一活动即被跨活动本身，其 start 早于 t，空隙右界退化为「无右界」由级联承担
        int gapEnd = idx >= n ? DAY_END_MIN : items.get(idx).startMin();

        // 实际落位 = max(候选, 空隙起点)：用于时间允许判定与空隙余量计算
        int eff = Math.max(t, gapStart);
        if (!allowOverrun && eff + MEAL_DURATION_MIN > DAY_END_MIN) {
            return null;
        }
        // 窗口右缘约束：实际开始晚于午 13:30 / 晚 19:30 即该候选不可用
        // （防止「上一活动结束 + 路程」把正餐顶出作息窗口；级联/跨位插入仍可走更早候选）
        if (eff > windowCap) {
            return null;
        }
        // 跨位插入：下一活动 start ≤ t，零打扰不可能，直接走级联（须把该活动整体右推）
        if (idx < n && gapEnd <= eff) {
            for (int travel : new int[]{DEFAULT_TRAVEL_MIN, 0}) {
                if (cascadeFits(items, idx, eff, travel, allowOverrun)) {
                    return new Placement(idx, t, travel, true);
                }
            }
            return null;
        }
        // 零打扰：空隙容得下一整餐（到下一段的路程按剩余空隙收缩，绝不挤动后续活动）
        if (eff + MEAL_DURATION_MIN <= gapEnd) {
            int slack = gapEnd - (eff + MEAL_DURATION_MIN);
            return new Placement(idx, t, Math.min(DEFAULT_TRAVEL_MIN, Math.max(0, slack)), false);
        }
        // 级联推演：先按默认路程试，放不下再按 0 路程试；仍越过 21:00 则该候选不可用
        for (int travel : new int[]{DEFAULT_TRAVEL_MIN, 0}) {
            if (cascadeFits(items, idx, eff, travel, allowOverrun)) {
                return new Placement(idx, t, travel, true);
            }
        }
        return null;
    }

    /** 旅行切分：上一活动到正餐的路程取「原路程与默认 15 分钟」之小者（长路程剩余段由正餐承担） */
    private static int travelToMeal(Item prev) {
        return Math.max(0, Math.min(prev.travelMin(), DEFAULT_TRAVEL_MIN));
    }

    /**
     * 按 fixTimeOverlaps 同构规则推演：插入正餐后，后续活动只后推
     *
     * @param allowOverrun true=强制入窗模式：不校验 21:00 收口（由调用侧裁尾部游览），恒返回 true
     */
    private static boolean cascadeFits(List<Item> items, int idx, int startMin, int travelMin,
            boolean allowOverrun) {
        int cur = startMin + MEAL_DURATION_MIN + travelMin;
        for (int j = idx; j < items.size(); j++) {
            Item it = items.get(j);
            int st = Math.max(it.startMin(), cur);
            if (!allowOverrun && st + it.durMin() > DAY_END_MIN) {
                return false;
            }
            cur = st + it.durMin() + Math.max(0, it.travelMin());
        }
        return true;
    }
}
