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
        // 推演：餐 12:04 落位 → 老城区 13:19 → 尾部 20:23 收尾，仍在 21:00 内
        assertThat(p.travelMin()).isEqualTo(15);
        // 空隙不足 60 分钟（eff+60 > gapEnd）→ 走级联右推而非零打扰
        assertThat(p.forced()).isTrue();
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
    @DisplayName("每日正餐保底 - 上一活动压过整个午餐窗口 - 跨活动插入并右推（B6 丢餐修复）")
    void plan_lunch_prevCoversWholeWindow_insertsBeforeActivity() {
        // 09:15 → 17:00 连续活动覆盖 11:00-13:30 窗口：午餐插到活动之前，活动整段右推
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 465, 15)); // 09:15 → 17:00

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(T1130);
        assertThat(p.travelMin()).isEqualTo(15);
        assertThat(p.forced()).isTrue();
        // 推演：餐 11:30-12:30(+15) → 活动 12:45-20:30(+15) → 20:45 收口 ≤ 21:00
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
    @DisplayName("每日正餐保底 - 首日 14:00 出发（退化右缘 14:00） - 午餐落 14:00 抵达即食")
    void plan_lunch_dayStartsAfternoon_clampedToDeparture() {
        // 14:00 出发（退化右缘 14:00，1.34.1 午间收紧）：餐必须 ≤14:00 起 →
        // 插到过渡活动之前（抵达即食），级联把 14:00-14:45 过渡右推到 15:15 起
        List<DailyMealPlanner.Item> items = List.of(
                act(14 * 60, 45, 15));

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, 14 * 60);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(14 * 60);
        assertThat(p.index()).isEqualTo(0);
        assertThat(p.forced()).isTrue();
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
        // 推演口径 = fixTimeOverlaps：餐 12:15-13:15(+15) → B 13:30-15:00(+15) → C 15:15-17:15
        // （旅行切分后空隙起点 = 12:00 + min(30, 15) = 12:15）
        assertThat(p.travelMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("每日正餐保底 - 首日 12:00 出发（floor 在窗口内） - 午餐候选起点不早于 floor")
    void plan_lunch_floorInsideWindow_startsAtFloor() {
        DailyMealPlanner.Placement p = DailyMealPlanner.plan(List.of(), true, 12 * 60);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(12 * 60);
        assertThat(p.forced()).isFalse();
    }

    @Test
    @DisplayName("每日正餐保底 - 首日 13:30 出发（窗口右缘） - 午餐落 13:30 且 14:30 收口")
    void plan_lunch_floorAtWindowEdge_startsAt1330() {
        DailyMealPlanner.Placement p = DailyMealPlanner.plan(List.of(), true, 13 * 60 + 30);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(13 * 60 + 30);
        assertThat(p.startMin() + DailyMealPlanner.MEAL_DURATION_MIN)
                .isLessThanOrEqualTo(DailyMealPlanner.DAY_END_MIN);
    }

    @Test
    @DisplayName("每日正餐保底 - 首日 19:30 出发（晚餐窗口右缘） - 晚餐落 19:30 且 20:30 收口")
    void plan_dinner_floorAtWindowEdge_startsAt1930() {
        DailyMealPlanner.Placement p = DailyMealPlanner.plan(List.of(), false, 19 * 60 + 30);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(19 * 60 + 30);
        assertThat(p.startMin() + DailyMealPlanner.MEAL_DURATION_MIN).isEqualTo(20 * 60 + 30);
    }

    @Test
    @DisplayName("每日正餐保底 - 跨位插入后尾部恰好 21:00 收口 - 边界内放行")
    void plan_lunch_cascadeEndsExactlyAt2100_allowed() {
        // 09:15 → 17:30 大段活动：午餐插到其前，右推后活动 12:45-21:00 恰好收口（边界 ≤）
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 495, 0)); // 09:15 → 17:30

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(T1130);
        assertThat(p.forced()).isTrue();
    }

    @Test
    @DisplayName("每日正餐保底 - 零打扰落位越过 13:30 窗口右缘 - 拒绝并回落目标时刻强制落位")
    void plan_lunch_zeroOutsideWindowEdge_rejectedFallsBackToTarget() {
        // 11:55-13:55 活动：其后的零打扰落位起点 14:10 越过窗口右缘 13:30（B11 收紧前的违规路径）
        // → 拒绝该候选，回落目标时刻 11:30 级联插入并右推活动
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(11 * 60 + 55, 120, 15)); // 11:55 → 13:55

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(T1130);
        assertThat(p.travelMin()).isEqualTo(15);
        assertThat(p.forced()).isTrue();
    }

    @Test
    @DisplayName("每日正餐保底 - 双候选选优 - 窗口内的后移零打扰候选优先于目标时刻 forced 落位")
    void plan_lunch_laterCandidateZeroDisturbance_winsOverForced() {
        // 11:30 落入 11:55 活动之前的不足空隙（需右推）；11:55-12:55 活动之后的空隙
        // （13:10 起）在窗口内可零打扰 → 后移候选优先
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(11 * 60 + 55, 60, 15)); // 11:55 → 12:55

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.startMin()).isEqualTo(11 * 60 + 55);
        assertThat(p.index()).isEqualTo(2);
        assertThat(p.forced()).isFalse();
    }

    @Test
    @DisplayName("每日正餐保底 - 上一活动路程 80 分钟 - 旅行切分后零打扰落位仍在窗口内")
    void plan_lunch_longPrevTravel_slicedToStayInWindow() {
        // 10:00-12:15 活动路程 80 分钟：切分前空隙起点 13:35 越过 13:30 只能级联（落位 13:35 越窗）；
        // 切分后空隙起点 = 12:15 + min(80, 15) = 12:30，11:30 候选零打扰落入窗口
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(10 * 60, 135, 80),  // 10:00 → 12:15（到下一段路程 80 分钟）
                act(14 * 60, 90, 15));  // 14:00 → 15:30

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(2);
        assertThat(p.startMin()).isEqualTo(T1130);
        assertThat(p.travelMin()).isEqualTo(15);
        assertThat(p.forced()).isFalse();
    }

    @Test
    @DisplayName("每日正餐保底 - 晚餐空隙恰好 60 分钟 - 零打扰落位且路程收缩为 0")
    void plan_dinner_gapExactlyMealLength_zeroDisturbanceWithTravelCollapsed() {
        // 18:00 活动之前恰有 17:00-18:00 空隙：零打扰插入，剩余空隙为 0 → travel 收缩到 0
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(18 * 60, 130, 0)); // 18:00 → 20:10

        DailyMealPlanner.Placement p = DailyMealPlanner.plan(items, false, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(17 * 60);
        assertThat(p.travelMin()).isZero();
        assertThat(p.forced()).isFalse();
        // 推演：餐 17:00-18:00(+0) → 活动 18:00-20:10 不动
    }

    // ===== planForced 强制入窗（1.31.0） =====

    @Test
    @DisplayName("强制入窗 - 尾部排满 21:00 - 常规判空但落位窗口内（forced）")
    void planForced_lunch_tailFullTo2100_forcesIntoWindow() {
        // 同 plan_lunch_tailFullTo2100_returnsNull 输入：常规模式判空，强制模式放开 21:00 收口
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 690, 0),
                act(20 * 60 + 45, 15, 0));

        assertThat(DailyMealPlanner.plan(items, true, FLOOR8)).isNull();

        DailyMealPlanner.Placement p = DailyMealPlanner.planForced(items, true, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.forced()).isTrue();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(T1130);
        // 窗口右缘仍是硬约束：强制入窗也必须 ≤ 13:30
        assertThat(p.startMin()).isLessThanOrEqualTo(13 * 60 + 30);
        assertThat(p.travelMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("强制入窗 - 晚餐尾部排过 20:00 - 常规判空但强制落位 17:30")
    void planForced_dinner_tailAfter2000_forcesIntoWindow() {
        // 同 plan_dinner_tailAfter2000_returnsNull 输入
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 660, 0)); // 09:15 → 20:15

        assertThat(DailyMealPlanner.plan(items, false, FLOOR8)).isNull();

        DailyMealPlanner.Placement p = DailyMealPlanner.planForced(items, false, FLOOR8);

        assertThat(p).isNotNull();
        assertThat(p.forced()).isTrue();
        assertThat(p.index()).isEqualTo(1);
        assertThat(p.startMin()).isEqualTo(T1730);
        assertThat(p.startMin()).isLessThanOrEqualTo(19 * 60 + 30);
    }

    @Test
    @DisplayName("强制入窗 - 正常日 - 与 plan 结果一致（零打扰优先，不误走强制）")
    void planForced_lunch_emptyTail_sameAsPlan() {
        List<DailyMealPlanner.Item> items = List.of(
                act(8 * 60, 60, 15),
                act(9 * 60 + 15, 120, 15));

        DailyMealPlanner.Placement normal = DailyMealPlanner.plan(items, true, FLOOR8);
        DailyMealPlanner.Placement forced = DailyMealPlanner.planForced(items, true, FLOOR8);

        assertThat(normal).isNotNull();
        assertThat(forced).isNotNull();
        assertThat(forced.index()).isEqualTo(normal.index());
        assertThat(forced.startMin()).isEqualTo(normal.startMin());
        assertThat(forced.forced()).isFalse();
    }

    @Test
    @DisplayName("强制入窗 - 首日 16:00 出发（窗口右缘已过） - 仍返回 null（右缘为硬约束）")
    void planForced_lunch_departureAfterWindowEdge_stillNull() {
        List<DailyMealPlanner.Item> items = List.of(act(16 * 60, 120, 15));

        assertThat(DailyMealPlanner.plan(items, true, 16 * 60)).isNull();
        assertThat(DailyMealPlanner.planForced(items, true, 16 * 60)).isNull();
    }
}
