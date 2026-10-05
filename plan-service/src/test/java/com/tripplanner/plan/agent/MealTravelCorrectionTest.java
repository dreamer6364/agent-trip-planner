package com.tripplanner.plan.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 餐段距离/时间准确性与就近餐厅推荐测试（1.35.0）
 *
 * <p>覆盖：{@code coordBefore} 参考点本地坐标回退（补餐不再因坐标缺失退化为非就近）、
 * {@code pickRestaurant} 距离优先打分、{@code mealLegNeedsRoute} 估算偏离判定、
 * {@code correctMealLegs} 串城餐点跳过与无路线时的原样保留。</p>
 */
class MealTravelCorrectionTest {

    private final TripPlanningAgent agent = new TripPlanningAgent(null, null, null, null, null);

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = TripPlanningAgent.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(agent, args);
    }

    @SuppressWarnings("unchecked")
    private double[] coordBefore(List<Map<String, Object>> dayActs, int index,
                                 String city, List<Map<String, Object>> places) throws Exception {
        return (double[]) invoke("coordBefore",
                new Class<?>[]{List.class, int.class, String.class, List.class},
                dayActs, index, city, places);
    }

    private Map<String, Object> pickRestaurant(String city, String pref, Double lat, Double lng)
            throws Exception {
        return (Map<String, Object>) invoke("pickRestaurant",
                new Class<?>[]{String.class, String.class, Double.class, Double.class, Set.class},
                city, pref, lat, lng, null);
    }

    private boolean needsRoute(Map<String, Object> carrier, double straightKm) throws Exception {
        return (boolean) invoke("mealLegNeedsRoute",
                new Class<?>[]{Map.class, double.class}, carrier, straightKm);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> correctMealLegs(List<Map<String, Object>> acts, String city,
                                                      String timeStart) throws Exception {
        return (List<Map<String, Object>>) invoke("correctMealLegs",
                new Class<?>[]{List.class, String.class, String.class}, acts, city, timeStart);
    }

    @Test
    @DisplayName("补餐参考点 - 活动尚无坐标 - 回退 places 本地坐标")
    void coordBefore_noActCoord_fallsBackToPlaces() throws Exception {
        // Given：correctStep 之前活动还没有 lat/lng，但 places 自带坐标
        Map<String, Object> visit = new HashMap<>();
        visit.put("day", 1);
        visit.put("type", "visit");
        visit.put("name", "万绿园");
        visit.put("startTime", "09:00");
        Map<String, Object> meal = new HashMap<>();
        meal.put("day", 1);
        meal.put("type", "meal");
        meal.put("name", "午餐·本地美食");
        meal.put("startTime", "12:00");
        Map<String, Object> place = new HashMap<>();
        place.put("name", "万绿园");
        place.put("lat", 20.0257);
        place.put("lng", 110.3094);

        // When
        double[] ref = coordBefore(List.of(visit, meal), 1, "海口", List.of(place));

        // Then：拿到 places 里的坐标（修复前返回 null → 挑餐厅退化为非就近）
        assertThat(ref).isNotNull();
        assertThat(ref[0]).isEqualTo(20.0257);
        assertThat(ref[1]).isEqualTo(110.3094);
    }

    @Test
    @DisplayName("补餐参考点 - 全程无坐标 - 返回 null 不抛异常")
    void coordBefore_noCoordAnywhere_returnsNull() throws Exception {
        Map<String, Object> visit = new HashMap<>();
        visit.put("day", 1);
        visit.put("type", "visit");
        visit.put("name", "未知景点");

        assertThat(coordBefore(List.of(visit), 1, "无城", List.of())).isNull();
    }

    @Test
    @DisplayName("餐厅挑选 - 口味命中更远的店 - 仍优先更近的店（距离优先）")
    void pickRestaurant_distanceBeatsTaste() throws Exception {
        // Given：绿茶餐厅(30.2680,120.1450) 就在参考点旁；楼外楼 2.2km 外但命中口味
        double refLat = 30.2680;
        double refLng = 120.1450;

        // When
        Map<String, Object> picked = pickRestaurant("杭州", "西湖醋鱼", refLat, refLng);

        // Then：距离优先——最近的候选胜出，口味只折算 1.5km 优势
        assertThat(picked).isNotNull();
        assertThat(String.valueOf(picked.get("name"))).isEqualTo("绿茶餐厅");
    }

    @Test
    @DisplayName("餐厅挑选 - 无参考点 - 退回口味匹配优先")
    void pickRestaurant_noRef_fallsBackToTaste() throws Exception {
        Map<String, Object> picked = pickRestaurant("杭州", "西湖醋鱼", null, null);

        assertThat(picked).isNotNull();
        assertThat(String.valueOf(picked.get("name"))).isEqualTo("楼外楼");
    }

    @Test
    @DisplayName("餐段选段 - 里程与直线估算明显不符 - 需要真实路网重算")
    void needsRoute_detectsFabricatedDistance() throws Exception {
        // 4km 直线 ≈ 5.4km 路程，现值 1km（旅行切分折算）→ 必须重算
        assertThat(needsRoute(carrier(1.0), 4.0)).isTrue();
        // 里程缺失 → 重算
        assertThat(needsRoute(carrier(0), 4.0)).isTrue();
        // 已是真实里程（误差 <40%）→ 不重复打接口
        assertThat(needsRoute(carrier(5.4), 4.0)).isFalse();
        // 无坐标 → 无法重算
        assertThat(needsRoute(carrier(1.0), -1)).isFalse();
    }

    @Test
    @DisplayName("餐段补正 - 餐点距前后锚点均超限（串城坐标）- 跳过且不改数据")
    void correctMealLegs_crossCityMeal_leftUntouched() throws Exception {
        // Given：景点 A/C 相邻，餐点却在 100km 外的另一座城市
        Map<String, Object> a = visit("甲景点", "08:00", 120, 15, 30.00, 120.00);
        Map<String, Object> meal = meal("午餐·外城餐厅", "12:00", 15, 1, 30.90, 120.90);
        Map<String, Object> c = visit("乙景点", "14:00", 120, 15, 30.01, 120.01);
        List<Map<String, Object>> acts = new ArrayList<>(List.of(a, meal, c));

        // When：restaurantSearchService 为 null → 重定位失败 → 该餐段不可信
        List<Map<String, Object>> out = correctMealLegs(acts, "杭州", "2026-10-08T08:00");

        // Then：原样返回，路程数据未被毒坐标污染
        assertThat(out).isSameAs(acts);
        assertThat(meal.get("travelTimeMin")).isEqualTo(15);
        assertThat(meal.get("travelDistanceKm")).isEqualTo(1);
        assertThat(a.get("travelTimeMin")).isEqualTo(15);
    }

    @Test
    @DisplayName("餐段补正 - 无可用真实路线 - 原样保留不写假数据")
    void correctMealLegs_noRoute_keepsExisting() throws Exception {
        // Given：餐点在锚点 7km 内（可信），里程 1km 与估算 9.9km 不符 → 需重算
        Map<String, Object> a = visit("甲景点", "08:00", 120, 15, 30.00, 120.00);
        Map<String, Object> meal = meal("午餐·绿茶餐厅", "12:00", 15, 1, 30.05, 120.05);
        List<Map<String, Object>> acts = new ArrayList<>(List.of(a, meal));

        // When：routeService 为 null → 真实路线查询失败
        List<Map<String, Object>> out = correctMealLegs(acts, "杭州", "2026-10-08T08:00");

        // Then：失败保留原值（禁止用假数据覆盖）
        assertThat(out).isSameAs(acts);
        assertThat(a.get("travelTimeMin")).isEqualTo(15);
        assertThat(a.get("travelDistanceKm")).isEqualTo(1);
    }

    private static Map<String, Object> carrier(double distanceKm) {
        Map<String, Object> m = new HashMap<>();
        m.put("travelTimeMin", 15);
        m.put("travelDistanceKm", distanceKm);
        return m;
    }

    private static Map<String, Object> visit(String name, String start, int dur, int travel,
                                             double lat, double lng) {
        Map<String, Object> a = new HashMap<>();
        a.put("day", 1);
        a.put("type", "visit");
        a.put("name", name);
        a.put("startTime", start);
        a.put("endTime", plus(start, dur));
        a.put("durationMin", dur);
        a.put("travelTimeMin", travel);
        a.put("travelDistanceKm", 1);
        a.put("lat", lat);
        a.put("lng", lng);
        return a;
    }

    private static Map<String, Object> meal(String name, String start, int travel, int dist,
                                            double lat, double lng) {
        Map<String, Object> a = visit(name, start, 60, travel, lat, lng);
        a.put("type", "meal");
        a.put("travelDistanceKm", dist);
        return a;
    }

    private static String plus(String hhmm, int minutes) {
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5)) + minutes;
        return String.format("%02d:%02d", h + m / 60, m % 60);
    }
}
