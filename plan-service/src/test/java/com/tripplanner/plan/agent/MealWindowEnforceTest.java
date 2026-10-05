package com.tripplanner.plan.agent;

import com.tripplanner.plan.constant.TripPace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 餐次时间窗强制入窗与检查节点测试（1.31.0）
 *
 * <p>覆盖：出窗餐强制重放进窗、过满日缺餐强制补入并裁尾（≤21:00）、
 * {@code checkMealWindowsStep} 修复/干净输入 no-op、{@code mealWindowViolations}
 * 违规清单口径（出窗/缺失/首日退化右缘）。</p>
 */
class MealWindowEnforceTest {

    private final TripPlanningAgent agent = new TripPlanningAgent(null, null, null, null, null);

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ensure(List<Map<String, Object>> acts, String city, String timeStart)
            throws Exception {
        Method m = TripPlanningAgent.class.getDeclaredMethod("ensureDailyMeals",
                List.class, String.class, List.class, List.class, String.class, List.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(agent, acts, city, null, null, timeStart, null);
    }

    @SuppressWarnings("unchecked")
    private List<String> violations(List<Map<String, Object>> acts, String timeStart) throws Exception {
        Method m = TripPlanningAgent.class.getDeclaredMethod("mealWindowViolations", List.class, String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(agent, acts, timeStart);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checkStep(List<Map<String, Object>> acts, String timeStart) throws Exception {
        Method m = TripPlanningAgent.class.getDeclaredMethod("checkMealWindowsStep",
                List.class, PlanningContext.class);
        m.setAccessible(true);
        PlanningContext ctx = new PlanningContext("", null, null, timeStart, null, "青岛",
                TripPace.MODERATE, false, null, null);
        return (List<Map<String, Object>>) m.invoke(agent, acts, ctx);
    }

    private static Map<String, Object> visit(String name, int startMin, int durMin, int travelMin) {
        Map<String, Object> a = new HashMap<>();
        a.put("day", 1);
        a.put("type", "visit");
        a.put("name", name);
        a.put("startTime", hhmm(startMin));
        a.put("endTime", hhmm(startMin + durMin));
        a.put("durationMin", durMin);
        a.put("travelTimeMin", travelMin);
        return a;
    }

    private static Map<String, Object> mealAct(String name, int startMin) {
        Map<String, Object> a = new HashMap<>();
        a.put("day", 1);
        a.put("type", "meal");
        a.put("name", name);
        a.put("startTime", hhmm(startMin));
        a.put("endTime", hhmm(startMin + 60));
        a.put("durationMin", 60);
        a.put("travelTimeMin", 15);
        return a;
    }

    private static String hhmm(int minute) {
        return String.format("%02d:%02d", minute / 60, minute % 60);
    }

    private static int startMin(Map<String, Object> a) {
        String s = String.valueOf(a.getOrDefault("startTime", a.getOrDefault("scheduled_start", "08:00")));
        return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3, 5));
    }

    private static int endMin(Map<String, Object> a) {
        return startMin(a) + ((Number) a.getOrDefault("durationMin",
                a.getOrDefault("duration_min", 60))).intValue();
    }

    private static Map<String, Object> findSlot(List<Map<String, Object>> acts, String slotWord) {
        for (Map<String, Object> a : acts) {
            String name = String.valueOf(a.getOrDefault("name", ""));
            if ("meal".equals(String.valueOf(a.getOrDefault("type", ""))) && name.contains(slotWord)) {
                return a;
            }
        }
        return null;
    }

    @Test
    @DisplayName("检查节点 - 出窗午餐 + 缺失晚餐 - 一次修复后全部合规")
    void checkStep_outOfWindowLunchAndMissingDinner_repairsBoth() throws Exception {
        // 08:00-09:00 栈桥 → 09:15-20:45 八大关（连续大段排满） → 20:45-21:00 奥帆中心
        // 午餐 15:11 出窗：常规 21:00 收口无槽 → 强制入窗重放，晚餐补入，尾部超时游览裁掉
        List<Map<String, Object>> acts = new ArrayList<>(List.of(
                visit("栈桥", 8 * 60, 60, 15),
                visit("八大关", 9 * 60 + 15, 690, 0),
                visit("奥帆中心", 20 * 60 + 45, 15, 0),
                mealAct("老街午餐", 15 * 60 + 11)));

        List<String> before = violations(acts, "2026-10-05");
        assertThat(before).hasSize(2);
        assertThat(before.get(0)).contains("午餐", "15:11");
        assertThat(before.get(1)).contains("晚餐缺失");

        List<Map<String, Object>> out = checkStep(acts, "2026-10-05");

        assertThat(violations(out, "2026-10-05")).isEmpty();
        // 午餐重放保留原名且入窗（11:00-13:30）
        Map<String, Object> lunch = findSlot(out, "午");
        assertThat(lunch).isNotNull();
        assertThat(lunch.get("name")).isEqualTo("老街午餐");
        assertThat(startMin(lunch)).isBetween(11 * 60, 13 * 60 + 30);
        // 晚餐补入且入窗（17:00-19:30）
        Map<String, Object> dinner = findSlot(out, "晚");
        assertThat(dinner).isNotNull();
        assertThat(startMin(dinner)).isBetween(17 * 60, 19 * 60 + 30);
        // 强制入窗收口：全程无活动越过 21:00（尾部奥帆中心被裁）
        for (Map<String, Object> a : out) {
            assertThat(endMin(a)).as(String.valueOf(a.get("name"))).isLessThanOrEqualTo(21 * 60);
        }
        assertThat(out).extracting(a -> a.get("name")).doesNotContain("奥帆中心");
    }

    @Test
    @DisplayName("检查节点 - 干净输入 - no-op 返回原列表")
    void checkStep_cleanInput_returnsSameList() throws Exception {
        List<Map<String, Object>> acts = new ArrayList<>(List.of(
                visit("栈桥", 8 * 60, 60, 15),
                mealAct("老街午餐", 12 * 60),
                visit("八大关", 13 * 60 + 15, 120, 15),
                mealAct("海边晚餐", 18 * 60),
                visit("奥帆中心", 19 * 60 + 15, 45, 0)));

        assertThat(violations(acts, "2026-10-05")).isEmpty();

        List<Map<String, Object>> out = checkStep(acts, "2026-10-05");

        assertThat(out).isSameAs(acts);
    }

    @Test
    @DisplayName("违规清单 - 出窗 / 缺失 / 首日退化右缘 - 与 windowCap 同口径")
    void violations_windowSemantics() throws Exception {
        // 出窗：午餐 15:11 > 13:30（晚餐在窗 → 仅 1 条）
        List<Map<String, Object>> outOfWindow = new ArrayList<>(List.of(
                visit("栈桥", 8 * 60, 60, 15),
                mealAct("老街午餐", 15 * 60 + 11),
                mealAct("海边晚餐", 18 * 60)));
        assertThat(violations(outOfWindow, "2026-10-05"))
                .containsExactly("day1 午餐 15:11 出窗");

        // 缺失：仅午餐 → 晚餐缺失
        List<Map<String, Object>> onlyLunch = new ArrayList<>(List.of(
                visit("栈桥", 8 * 60, 60, 15),
                mealAct("老街午餐", 12 * 60)));
        assertThat(violations(onlyLunch, "2026-10-05"))
                .containsExactly("day1 晚餐缺失");

        // 首日 14:00 出发：右缘退化为 14:00（1.34.1 午间收紧）—— 14:00 合规、14:30 即出窗
        List<Map<String, Object>> lateDeparture = new ArrayList<>(List.of(
                visit("栈桥", 14 * 60, 60, 15),
                mealAct("老街午餐", 14 * 60),
                mealAct("海边晚餐", 18 * 60)));
        assertThat(violations(lateDeparture, "2026-10-05T14:00")).isEmpty();

        List<Map<String, Object>> lateLunch = new ArrayList<>(List.of(
                visit("栈桥", 14 * 60, 60, 15),
                mealAct("老街午餐", 14 * 60 + 30),
                mealAct("海边晚餐", 18 * 60)));
        assertThat(violations(lateLunch, "2026-10-05T14:00"))
                .containsExactly("day1 午餐 14:30 出窗");
    }

    @Test
    @DisplayName("检查节点 - timeStart 与日程脱节（出发 16:57、day1 排 08:00 起） - floor 以日程为准修复出窗午餐")
    void checkStep_timeStartScheduleMismatch_repairsOutWindowLunch() throws Exception {
        // 宁夏案例（BUGFIX 1.34.1）：用户未指定出发时刻 → parse 默认时分 16:57，而 LLM 把
        // day1 排为 08:00 起。旧逻辑 firstFloor=16:57 > 13:30 → 退化分支唯一候选 16:57 恒
        // 晚于右缘，plan/planForced 必然判空、15:45 出窗午餐无法回收。floor 与日程对齐后
        // 午餐零打扰重放进窗
        List<Map<String, Object>> acts = new ArrayList<>(List.of(
                visit("沙坡头", 8 * 60, 240, 0),
                visit("中场休息", 12 * 60, 20, 145),
                visit("邮政博物馆", 14 * 60 + 45, 43, 17),
                mealAct("午餐·老毛手抓", 15 * 60 + 45),
                mealAct("晚餐·国强手抓", 17 * 60 + 33),
                visit("地质博物馆", 19 * 60 + 10, 85, 0)));

        List<String> before = violations(acts, "2026-10-06T16:57");
        assertThat(before).containsExactly("day1 午餐 15:45 出窗");

        List<Map<String, Object>> out = checkStep(acts, "2026-10-06T16:57");

        assertThat(violations(out, "2026-10-06T16:57")).isEmpty();
        Map<String, Object> lunch = findSlot(out, "午");
        assertThat(lunch).isNotNull();
        assertThat(lunch.get("name")).isEqualTo("午餐·老毛手抓");
        assertThat(startMin(lunch)).isBetween(11 * 60, 13 * 60 + 30);
    }

    @Test
    @DisplayName("强制入窗 - 过满日缺失正餐 - 强制补入双餐且尾部裁至 21:00")
    void ensure_overfullDayMissingMeals_forcesBothMealsInWindow() throws Exception {
        List<Map<String, Object>> acts = new ArrayList<>(List.of(
                visit("栈桥", 8 * 60, 60, 15),
                visit("八大关", 9 * 60 + 15, 690, 0),
                visit("奥帆中心", 20 * 60 + 45, 15, 0)));

        List<Map<String, Object>> out = ensure(acts, "青岛", "2026-10-05");

        assertThat(violations(out, "2026-10-05")).isEmpty();
        Map<String, Object> lunch = findSlot(out, "午");
        assertThat(lunch).isNotNull();
        assertThat(startMin(lunch)).isEqualTo(11 * 60 + 30);
        assertThat(String.valueOf(lunch.get("name")).startsWith("午餐·")).isTrue();
        Map<String, Object> dinner = findSlot(out, "晚");
        assertThat(dinner).isNotNull();
        assertThat(startMin(dinner)).isEqualTo(17 * 60 + 30);
        // 连续大段被强制级联右推后按 21:00 口径截断收口（保景不删景）
        Map<String, Object> baguan = null;
        for (Map<String, Object> a : out) {
            if ("八大关".equals(a.get("name"))) baguan = a;
            assertThat(endMin(a)).as(String.valueOf(a.get("name"))).isLessThanOrEqualTo(21 * 60);
        }
        assertThat(baguan).isNotNull();
        assertThat(endMin(baguan)).isEqualTo(21 * 60);
        // 无空间的尾部游览被裁掉
        assertThat(out).extracting(a -> a.get("name")).doesNotContain("奥帆中心");
    }
}
