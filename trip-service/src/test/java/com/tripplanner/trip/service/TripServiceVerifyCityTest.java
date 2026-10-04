package com.tripplanner.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripplanner.trip.client.AuthUserClient;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VERIFY_CITY 跨城过滤测试（BUGFIX 1.30.0）
 * 覆盖：外城餐厅降级保餐次、跨城 visit 剔除、全本地原样返回
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripServiceVerifyCityTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private TripVersionRepository versionRepository;

    @Mock
    private AuthUserClient authUserClient;

    private TripService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new TripService(
                tripRepository, versionRepository,
                null, null, null, null,
                new com.tripplanner.common.util.JsonUtils(mapper), mapper,
                null, null,
                authUserClient);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> verify(List<Map<String, Object>> acts, String city) throws Exception {
        Method m = TripService.class.getDeclaredMethod(
                "verifyCityOwnership", List.class, String.class, String.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(service, acts, city, "trip-t");
    }

    private Map<String, Object> act(String type, String name) {
        Map<String, Object> a = new HashMap<>();
        a.put("activity_type", type);
        a.put("name", name);
        return a;
    }

    @Test
    @DisplayName("VERIFY_CITY - 外城餐厅 - 降级为通用餐次且清除异地坐标，不剔除正餐")
    void verifyCity_foreignMeal_shouldDegradeInsteadOfDrop() throws Exception {
        Map<String, Object> meal = act("meal", "午餐·悦融琥珀·京鲁菜(西单店)");
        meal.put("lat", 39.9);
        meal.put("lng", 116.4);
        meal.put("address", "北京市西城区西单北大街130号");
        List<Map<String, Object>> acts = new ArrayList<>(List.of(meal));

        List<Map<String, Object>> out = verify(acts, "长白山");

        assertEquals(1, out.size());
        Map<String, Object> kept = out.get(0);
        assertEquals("meal", kept.get("activity_type"));
        assertEquals("午餐", kept.get("name"));
        assertEquals("午餐", kept.get("poi_name"));
        assertNull(kept.get("lat"));
        assertNull(kept.get("lng"));
        assertNull(kept.get("address"));
    }

    @Test
    @DisplayName("VERIFY_CITY - 跨城 visit 与本地景点混合 - 剔除跨城 visit 保留本地并重排 seq")
    void verifyCity_mixedVisits_shouldDropForeignKeepLocal() throws Exception {
        List<Map<String, Object>> acts = new ArrayList<>(List.of(
                act("visit", "地下森林"),
                act("visit", "故宫"),
                act("visit", "绿渊潭")));
        acts.get(0).put("activity_type", "visit");

        List<Map<String, Object>> out = verify(acts, "长白山");

        assertEquals(2, out.size());
        assertEquals("地下森林", out.get(0).get("name"));
        assertEquals("绿渊潭", out.get(1).get("name"));
        assertEquals(1, out.get(0).get("seq"));
        assertEquals(2, out.get(1).get("seq"));
        assertTrue(out.stream().noneMatch(a -> "故宫".equals(a.get("name"))));
    }

    @Test
    @DisplayName("VERIFY_CITY - 全部本地活动 - 原列表原样返回（含 rest 交通类跳过校验）")
    void verifyCity_allLocal_shouldReturnSameListInstance() throws Exception {
        List<Map<String, Object>> acts = new ArrayList<>(List.of(
                act("visit", "地下森林"),
                act("rest", "休息"),
                act("transit", "换乘")));
        acts.get(2).put("activity_type", "transit");

        List<Map<String, Object>> out = verify(acts, "长白山");

        assertSame(acts, out);
        assertEquals(3, out.size());
    }

    @Test
    @DisplayName("VERIFY_CITY - 未知归属餐名 - 放行不降级")
    void verifyCity_unknownOwnerMeal_shouldKeepOriginal() throws Exception {
        Map<String, Object> meal = act("meal", "晚餐·山珍馆");
        List<Map<String, Object>> acts = new ArrayList<>(List.of(meal));

        List<Map<String, Object>> out = verify(acts, "长白山");

        assertSame(acts, out);
        assertEquals("晚餐·山珍馆", out.get(0).get("name"));
        assertFalse(out.isEmpty());
    }
}
