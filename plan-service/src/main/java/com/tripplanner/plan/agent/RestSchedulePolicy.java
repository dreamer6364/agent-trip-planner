package com.tripplanner.plan.agent;

import com.tripplanner.plan.constant.TripPace;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能休息节点调度策略（纯函数，无 IO，供单测）
 *
 * 按节奏（pace）给出三档「不同频率」参数：连续游览达到阈值即在活动后插入休息，
 * 餐食与已有休息会重置连续计时，插入结果做 21:00 作息窗口预演（不可行则放弃）。
 *
 * <ul>
 *   <li>紧凑 compact：150 分钟阈值 / 15 分钟休息 / 每日至多 1 次</li>
 *   <li>适中 moderate：120 分钟阈值 / 20 分钟休息 / 每日至多 2 次</li>
 *   <li>宽松 relaxed：90 分钟阈值 / 30 分钟休息 / 每日至多 2 次</li>
 * </ul>
 *
 * @since 1.21.0
 */
public final class RestSchedulePolicy {

    /** 当日 21:00 作息收口线（分钟） */
    private static final int DAY_END_MIN = 21 * 60;

    /** 当天单个活动的时间视图（由 agent 从活动 Map 提取，保持本类无 Map 依赖） */
    public record Item(String type, int startMin, int endMin, int travelToNextMin) {
        public int durationMin() {
            return Math.max(0, endMin - startMin);
        }
    }

    /** 一次休息插入：在 afterIndex 活动之后插入，startMin 为含已接受偏移后的钟点 */
    public record Insertion(int afterIndex, int durationMin, String label, int startMin) {}

    /** 一天的调度结果 */
    public record Plan(int thresholdMin, int restMin, int maxPerDay, List<Insertion> insertions) {}

    private RestSchedulePolicy() {
    }

    /** 连续计时遇到餐食即重置（餐即休息） */
    public static boolean isMeal(String type) {
        String t = normalize(type);
        return t.equals("meal") || t.equals("breakfast") || t.equals("lunch")
                || t.equals("dinner") || t.equals("restaurant");
    }

    /** 已有休息节点（再次遇到时重置计时） */
    public static boolean isRest(String type) {
        return "rest".equals(normalize(type));
    }

    /** 节奏 → 阈值/时长/上限三档参数 */
    public static Plan planDay(List<Item> items, TripPace pace) {
        int threshold;
        int restMin;
        int maxPerDay;
        switch (pace == null ? TripPace.MODERATE : pace) {
            case COMPACT -> { threshold = 150; restMin = 15; maxPerDay = 1; }
            case RELAXED -> { threshold = 90; restMin = 30; maxPerDay = 2; }
            default -> { threshold = 120; restMin = 20; maxPerDay = 2; }
        }
        List<Insertion> insertions = new ArrayList<>();
        if (items == null || items.size() < 2) {
            return new Plan(threshold, restMin, maxPerDay, insertions);
        }
        int cursor = 0;
        int shift = 0;
        int lastEnd = items.get(items.size() - 1).endMin();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (i > 0) {
                cursor += Math.max(0, items.get(i - 1).travelToNextMin());
            }
            if (isMeal(item.type()) || isRest(item.type())) {
                cursor = 0;
                continue;
            }
            cursor += item.durationMin();
            if (cursor < threshold || insertions.size() >= maxPerDay) {
                continue;
            }
            if (i == items.size() - 1) {
                break;
            }
            if (isMeal(items.get(i + 1).type())) {
                cursor = 0;
                continue;
            }
            int start = item.endMin() + shift;
            if (lastEnd + shift + restMin > DAY_END_MIN) {
                break;
            }
            insertions.add(new Insertion(i, restMin, labelFor(start), start));
            shift += restMin;
            cursor = 0;
        }
        return new Plan(threshold, restMin, maxPerDay, insertions);
    }

    /** 按休息开始钟点取名称：上午茶歇 / 午后小憩 / 中场休息 */
    public static String labelFor(int startMin) {
        if (startMin < 11 * 60 + 30) {
            return "上午茶歇";
        }
        if (startMin >= 13 * 60 && startMin < 17 * 60 + 30) {
            return "午后小憩";
        }
        return "中场休息";
    }

    private static String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase();
    }
}
