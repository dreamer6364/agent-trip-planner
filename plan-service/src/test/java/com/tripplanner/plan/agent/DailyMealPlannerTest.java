package com.tripplanner.plan.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DailyMealPlanner 单元测试：每日午/晚餐时间保底策略
 */
class DailyMealPlannerTest {

    private static final int T1130 = 11 * 60 + 30;
    private static final int T1730 = 17 * 60 + 30;
    private static final int FLOOR8 = 8 * 60;

    private static DailyMealPlanner.Item act(int start, int dur, int travel) {
        return new DailyMealPlanner.Item(start, dur, travel);
    }

    @Test
    @DisplayName("每日正餐保底 - 尾部空闲 - 午餐零打扰落入目标时刻")
    void plan_lunch_emptyTail_keepsTargetStart() {
        // 早餐 08:00-09:00 → 栈桥 09:15-11:15（尾部空闲到 21:00）
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 120, 15));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(2);
        assertThat(p.startMin()).isEqualTo(T1130);
        assertThat(p.travelMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("每日正餐保底 - 上一活动压过目标时刻 - 落位顺延到空隙起点（零打扰）")
    void plan_lunch_prevActivitySpillsIntoTarget_fallsIntoGap() {
        // 博物馆 09:00-12:00（结束+路程 = 12:15），尾部空闲
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60, 180, 15));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(2);
        // 原始开始为目标时刻，实际落位由 fixTimeOverlaps 顺延到 12:15
        assertThat(p.startMin()).isEqualTo(T1130);
        assertThat(p.travelMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("每日正餐保底 - 空隙不足 - 级联后推且不越 21:00")
    void plan_lunch_gapTooSmall_cascadePushesTail() {
        // 栈桥 09:49-11:49（travel 29 → 12:18），老城区 12:18 起连续到 19:22
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 90, 19),
                act(9 * 60 + 49, 120, 29),
                act(12 * 60 + 18, 120, 39),
                act(14 * 60 + 57, 120, 25),
                act(17 * 60 + 22, 120, 0));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(2);
        assertThat(p.startMin()).isEqualTo(T1130);
        // 推演：餐 12:18 落位 → 老城区 13:33 → 尾部 20:37 收尾，仍在 21:00 内
        assertThat(p.travelMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("每日正餐保底 - 级联越过 21:00 - 判定时间不允许（宁缺餐不删景点）")
    void plan_lunch_tailFullTo2100_returnsNull() {
        // 一天排到 20:30 结束且无空隙：补午餐会把尾部推过 21:00
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 690, 0),   // 09:15 → 20:45 连续大段
                act(20 * 60 + 45, 15, 0));  // 20:45 → 21:00

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNull();
    }

    @Test
    @DisplayName("每日正餐保底 - 上一活动结束晚于午餐最晚落位 - 时间不允许")
    void plan_lunch_prevEndsAfter1500_returnsNull() {
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 465, 15)); // 09:15 → 17:00

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNull();
    }

    @Test
    @DisplayName("每日正餐保底 - 晚餐尾部空闲 - 17:30 零打扰")
    void plan_dinner_emptyTail_startsAt1730() {
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 90, 15),
                act(10 * 60, 120, 15),
                act(13 * 60, 90, 15));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, false, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(T1730);
        assertThat(p.travelMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("每日正餐保底 - 上一活动 15:44 结束 - 晚餐仍按 17:30 安排")
    void plan_dinner_prevEndsLateAfternoon_stillSchedules() {
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(13 * 60 + 44, 120, 15)); // → 15:44 结束

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, false, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(T1730);
        assertThat(p.index()).isEqualTo(2);
    }

    @Test
    @DisplayName("每日正餐保底 - 行程排到 20:00 后 - 晚餐时间不允许")
    void plan_dinner_tailAfter2000_returnsNull() {
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 660, 0)); // 09:15 → 20:15

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, false, FLOOR8);

        assertThat(p).isNull();
    }

    @Test
    @DisplayName("每日正餐保底 - 首日 14:00 出发 - 午餐不早于出发时刻且顺延到活动后")
    void plan_lunch_dayStartsAfternoon_clampedToDeparture() {
        // 14:00 抵达，14:00-14:45 过渡 + 路程 → 午餐落 15:00（最晚落位边界内）
        List<DailyMealPlanner.Item> items = List.of(
                act(14 * 60, 45, 15));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, 14 * 60);

        assertThat(p).isNotNull();
        // 目标 11:30 < 出发 14:00 → 餐次下界抬到 14:00（原始开始值）
        assertThat(p.startMin()).isEqualTo(14 * 60);
        assertThat(p.index()).isEqualTo(1);
    }

    @Test
    @DisplayName("每日正餐保底 - 首日 16:00 出发 - 午餐最晚落位已过，放弃")
    void plan_lunch_departureAfterLatestStart_returnsNull() {
        List<DailyMealPlanner.Item> items = List.of(
                act(16 * 60, 120, 15));

        assertThat(DailyMealPlanner.plan(items, true, 16 * 60)).isNull();
        // 晚餐仍可安排
        assertThat(DailyMealPlanner.plan(items, false, 16 * 60)).isNotNull();
    }

    @Test
    @DisplayName("每日正餐保底 - 当日无任何活动 - 午餐晚餐依次落入日窗口")
    void plan_emptyDay_bothMealsFit() {
        DailyMealPlanner.Placement lunch = DailyMealPlanner.plan(List.of(), true, FLOOR8);
        DailyMealPlanner.Placement dinner = DailyMealPlanner.plan(List.of(), false, FLOOR8);

        assertThat(lunch).isNotNull();
        assertThat(lunch.startMin()).isEqualTo(T1130);
        assertThat(dinner).isNotNull();
        assertThat(dinner.startMin()).isEqualTo(T1730);
    }

    @Test
    @DisplayName("每日正餐保底 - 输入时间轴交叠（上一活动路程越过下一活动） - 按修复后时间轴级联不越界")
    void plan_overlappingInput_cascadeUsesRepairedTimeline() {
        // A 08:00-12:00 travel 30（12:30 才算到达），B 却写 12:15 开始 → 交叠
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 240, 30),
                act(12 * 60 + 15, 90, 15),
                act(14 * 60, 120, 0));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(T1130);
        // 推演口径 = fixTimeOverlaps：餐 12:30-13:30(+15) → B 13:45-15:15(+15) → C 15:30-17:30
        assertThat(p.travelMin()).isEqualTo(15);
    }
}
