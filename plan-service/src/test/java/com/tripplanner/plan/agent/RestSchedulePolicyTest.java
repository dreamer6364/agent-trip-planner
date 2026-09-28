package com.tripplanner.plan.agent;

import com.tripplanner.plan.agent.RestSchedulePolicy.Insertion;
import com.tripplanner.plan.agent.RestSchedulePolicy.Item;
import com.tripplanner.plan.agent.RestSchedulePolicy.Plan;
import com.tripplanner.plan.constant.TripPace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智能休息节点调度策略单测（阈值/餐重置/上限/21:00 预演/时段命名）
 */
class RestSchedulePolicyTest {

    private static Item visit(int start, int end, int travelToNext) {
        return new Item("visit", start, end, travelToNext);
    }

    @Test
    @DisplayName("节奏三档参数 - 紧凑150/15/1、适中120/20/2、宽松90/30/2")
    void planDay_paceParameters() {
        Plan compact = RestSchedulePolicy.planDay(List.of(), TripPace.COMPACT);
        assertThat(compact.thresholdMin()).isEqualTo(150);
        assertThat(compact.restMin()).isEqualTo(15);
        assertThat(compact.maxPerDay()).isEqualTo(1);

        Plan moderate = RestSchedulePolicy.planDay(List.of(), TripPace.MODERATE);
        assertThat(moderate.thresholdMin()).isEqualTo(120);
        assertThat(moderate.restMin()).isEqualTo(20);
        assertThat(moderate.maxPerDay()).isEqualTo(2);

        Plan relaxed = RestSchedulePolicy.planDay(List.of(), TripPace.RELAXED);
        assertThat(relaxed.thresholdMin()).isEqualTo(90);
        assertThat(relaxed.restMin()).isEqualTo(30);
        assertThat(relaxed.maxPerDay()).isEqualTo(2);
    }

    @Test
    @DisplayName("适中节奏 - 连续游览165分钟 - 触发活动后插入上午茶歇")
    void planDay_thresholdReached_insertsAfterVisit() {
        // v1 08:00-09:30(90) + travel15 → 105；v2 09:45-10:45(60) → 165 ≥ 120，下一项非餐 → 插入
        List<Item> items = List.of(
                visit(480, 570, 15),
                visit(585, 645, 10),
                visit(660, 720, 15),
                visit(735, 795, 0));
        Plan plan = RestSchedulePolicy.planDay(items, TripPace.MODERATE);

        assertThat(plan.insertions()).hasSize(1);
        Insertion ins = plan.insertions().get(0);
        assertThat(ins.afterIndex()).isEqualTo(1);
        assertThat(ins.durationMin()).isEqualTo(20);
        assertThat(ins.startMin()).isEqualTo(645);
        assertThat(ins.label()).isEqualTo("上午茶歇");
    }

    @Test
    @DisplayName("餐前不插（餐即休息）且餐后计时重置")
    void planDay_mealResetsCursor_noInsertBeforeMeal() {
        // v1 140 分钟到阈值，但下一个是午餐 → 不插且重置；餐后 30 分钟不再触发
        List<Item> items = List.of(
                visit(480, 620, 10),
                new Item("meal", 630, 690, 15),
                visit(705, 735, 0));
        Plan plan = RestSchedulePolicy.planDay(items, TripPace.MODERATE);

        assertThat(plan.insertions()).isEmpty();
    }

    @Test
    @DisplayName("21:00 预演不可行 - 放弃插入")
    void planDay_dayEndOverflow_skipped() {
        // 末活动 20:00-21:00，插入 20 分钟会把末尾推到 21:20 → 放弃
        List<Item> items = List.of(
                visit(1110, 1230, 0),
                visit(1230, 1260, 0));
        Plan plan = RestSchedulePolicy.planDay(items, TripPace.MODERATE);

        assertThat(plan.insertions()).isEmpty();
    }

    @Test
    @DisplayName("当天最后一个活动后不插")
    void planDay_noInsertAfterLastActivity() {
        List<Item> items = List.of(
                visit(420, 450, 0),
                visit(450, 660, 0));
        Plan plan = RestSchedulePolicy.planDay(items, TripPace.MODERATE);

        assertThat(plan.insertions()).isEmpty();
    }

    @Test
    @DisplayName("适中节奏每日至多2次 - 达到上限后不再插入")
    void planDay_maxPerDayCap() {
        List<Item> items = List.of(
                visit(480, 580, 20),
                visit(600, 700, 20),
                visit(720, 820, 20),
                visit(840, 940, 0));
        Plan plan = RestSchedulePolicy.planDay(items, TripPace.MODERATE);

        assertThat(plan.insertions()).hasSize(2);
        assertThat(plan.insertions().get(0).afterIndex()).isEqualTo(1);
        assertThat(plan.insertions().get(0).startMin()).isEqualTo(700);
        assertThat(plan.insertions().get(0).label()).isEqualTo("中场休息");
        assertThat(plan.insertions().get(1).afterIndex()).isEqualTo(2);
        assertThat(plan.insertions().get(1).startMin()).isEqualTo(840);
        assertThat(plan.insertions().get(1).label()).isEqualTo("午后小憩");
    }

    @Test
    @DisplayName("紧凑节奏阈值150未到不插，到达只插一次")
    void planDay_compactThresholdAndSingleCap() {
        // 140 分钟 < 150 不插
        Plan below = RestSchedulePolicy.planDay(
                List.of(visit(480, 620, 0), visit(630, 690, 0)), TripPace.COMPACT);
        assertThat(below.insertions()).isEmpty();

        // 155 分钟 ≥ 150 插 15 分钟，其后到上限不再插
        Plan reached = RestSchedulePolicy.planDay(
                List.of(visit(480, 635, 0), visit(650, 800, 0), visit(815, 900, 0)), TripPace.COMPACT);
        assertThat(reached.insertions()).hasSize(1);
        assertThat(reached.insertions().get(0).durationMin()).isEqualTo(15);
    }

    @Test
    @DisplayName("休息时段命名 - 三档钟点边界")
    void labelFor_timeBuckets() {
        assertThat(RestSchedulePolicy.labelFor(11 * 60 + 29)).isEqualTo("上午茶歇");
        assertThat(RestSchedulePolicy.labelFor(11 * 60 + 30)).isEqualTo("中场休息");
        assertThat(RestSchedulePolicy.labelFor(12 * 60 + 59)).isEqualTo("中场休息");
        assertThat(RestSchedulePolicy.labelFor(13 * 60)).isEqualTo("午后小憩");
        assertThat(RestSchedulePolicy.labelFor(17 * 60 + 29)).isEqualTo("午后小憩");
        assertThat(RestSchedulePolicy.labelFor(17 * 60 + 30)).isEqualTo("中场休息");
    }

    @Test
    @DisplayName("类型判定 - 餐食与休息重置计时")
    void typePredicates() {
        assertThat(RestSchedulePolicy.isMeal("meal")).isTrue();
        assertThat(RestSchedulePolicy.isMeal("breakfast")).isTrue();
        assertThat(RestSchedulePolicy.isMeal("restaurant")).isTrue();
        assertThat(RestSchedulePolicy.isMeal("visit")).isFalse();
        assertThat(RestSchedulePolicy.isRest("rest")).isTrue();
        assertThat(RestSchedulePolicy.isRest("REST")).isTrue();
        assertThat(RestSchedulePolicy.isRest("visit")).isFalse();
    }
}
