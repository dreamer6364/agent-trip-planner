package com.tripplanner.trip.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落库餐次时间窗守卫测试（1.31.0 引入，1.34.1 补齐）
 *
 * <p>覆盖：出窗/缺失检出、首日退化右缘 14:00 口径、timeStart 与日程脱节时
 * floor 与日程起点对齐（BUGFIX 1.34.1 宁夏案例）。</p>
 */
class MealWindowGuardTest {

    private static Map<String, Object> visit(String name, int day, String start) {
        Map<String, Object> a = new HashMap<>();
        a.put("day", day);
        a.put("activity_type", "visit");
        a.put("poi_name", name);
        a.put("scheduled_start", start);
        a.put("duration_min", 60);
        return a;
    }

    private static Map<String, Object> meal(String name, int day, String start) {
        Map<String, Object> a = new HashMap<>();
        a.put("day", day);
        a.put("activity_type", "meal");
        a.put("poi_name", name);
        a.put("scheduled_start", start);
        a.put("duration_min", 60);
        return a;
    }

    @Test
    @DisplayName("守卫 - 出窗午餐 + 缺失晚餐 - 检出两条违规")
    void findViolations_outOfWindowLunchAndMissingDinner_reportsBoth() {
        List<Map<String, Object>> acts = List.of(
                visit("沙坡头", 1, "08:00"),
                meal("午餐·老毛手抓", 1, "15:45"));

        List<String> out = MealWindowGuard.findViolations(acts, "2026-10-06T08:00");

        assertThat(out).containsExactly("day1 午餐 15:45 出窗", "day1 晚餐缺失");
    }

    @Test
    @DisplayName("守卫 - 首日 14:00 出发（退化右缘 14:00） - 14:00 合规、14:30 出窗")
    void findViolations_lateDepartureDegradedEdge1400() {
        List<Map<String, Object>> ok = List.of(
                visit("栈桥", 1, "14:00"),
                meal("老街午餐", 1, "14:00"),
                meal("海边晚餐", 1, "18:00"));
        assertThat(MealWindowGuard.findViolations(ok, "2026-10-06T14:00")).isEmpty();

        List<Map<String, Object>> late = List.of(
                visit("栈桥", 1, "14:00"),
                meal("老街午餐", 1, "14:30"),
                meal("海边晚餐", 1, "18:00"));
        assertThat(MealWindowGuard.findViolations(late, "2026-10-06T14:00"))
                .containsExactly("day1 午餐 14:30 出窗");
    }

    @Test
    @DisplayName("守卫 - timeStart 与日程脱节（出发 16:57、day1 08:00 起） - floor 以日程为准按 13:30 判定")
    void findViolations_timeStartScheduleMismatch_alignedWithDay() {
        // 若不对齐：firstFloor=16:57 > 13:30 → 退化 cap 14:00 → 13:45 会被误判合规
        List<Map<String, Object>> acts = List.of(
                visit("沙坡头", 1, "08:00"),
                meal("午餐·后院私房火锅", 1, "13:45"),
                meal("晚餐·天乐百盛饭店", 1, "18:00"));

        assertThat(MealWindowGuard.findViolations(acts, "2026-10-06T16:57"))
                .containsExactly("day1 午餐 13:45 出窗");

        List<Map<String, Object>> inWindow = List.of(
                visit("沙坡头", 1, "08:00"),
                meal("午餐·后院私房火锅", 1, "13:00"),
                meal("晚餐·天乐百盛饭店", 1, "18:00"));

        assertThat(MealWindowGuard.findViolations(inWindow, "2026-10-06T16:57")).isEmpty();
    }
}
