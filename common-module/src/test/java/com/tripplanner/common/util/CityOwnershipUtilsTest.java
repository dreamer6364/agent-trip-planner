package com.tripplanner.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 城市识别与景点归属校验单元测试
 */
class CityOwnershipUtilsTest {

    private record Sample(String name, String type) {
    }

    // ==================== extractCity ====================

    @Test
    @DisplayName("城市识别 - 显式城市词 - 应返回该城市")
    void extractCity_explicitCityWord_shouldReturnCity() {
        assertEquals("北京", CityOwnershipUtils.extractCity("北京三日游"));
        assertEquals("杭州", CityOwnershipUtils.extractCity("想去杭州看西湖"));
    }

    @Test
    @DisplayName("城市识别 - 无城市词 - 应返回 null（禁止默认北京）")
    void extractCity_noCityWord_shouldReturnNull() {
        assertNull(CityOwnershipUtils.extractCity("看海吃海鲜，逛老城巷子"));
        assertNull(CityOwnershipUtils.extractCity("海边度假"));
    }

    @Test
    @DisplayName("城市识别 - 空输入 - 应返回 null")
    void extractCity_blankInput_shouldReturnNull() {
        assertNull(CityOwnershipUtils.extractCity(null));
        assertNull(CityOwnershipUtils.extractCity(""));
        assertNull(CityOwnershipUtils.extractCity("   "));
    }

    @Test
    @DisplayName("城市识别 - 外城路名 - 「广州北京路」应识别为广州")
    void extractCity_roadName_shouldNotMisjudgeAsBeijing() {
        assertEquals("广州", CityOwnershipUtils.extractCity("广州北京路怎么走"));
        assertEquals("广州", CityOwnershipUtils.extractCity("去北京路逛逛"));
    }

    @Test
    @DisplayName("城市识别 - 景点反查 - 无城市词但含景点应反查归属")
    void extractCity_byAttraction_shouldReturnOwnerCity() {
        assertEquals("杭州", CityOwnershipUtils.extractCity("想看西湖和灵隐寺"));
        assertEquals("青岛", CityOwnershipUtils.extractCity("栈桥和八大关怎么安排"));
    }

    // ==================== ownerCityOfPlace / belongsToCity ====================

    @Test
    @DisplayName("景点归属 - 精确匹配 - 应返回归属城市")
    void ownerCityOfPlace_exactMatch_shouldReturnOwner() {
        assertEquals("北京", CityOwnershipUtils.ownerCityOfPlace("故宫博物院"));
        assertEquals("杭州", CityOwnershipUtils.ownerCityOfPlace("雷峰塔"));
        assertEquals("青岛", CityOwnershipUtils.ownerCityOfPlace("栈桥"));
    }

    @Test
    @DisplayName("景点归属 - 餐次前缀 - 「午餐·外滩」应剥离前缀反查")
    void ownerCityOfPlace_mealPrefix_shouldStripPrefix() {
        assertEquals("上海", CityOwnershipUtils.ownerCityOfPlace("午餐·外滩"));
    }

    @Test
    @DisplayName("景点归属 - 未知景点 - 应返回 null")
    void ownerCityOfPlace_unknownPlace_shouldReturnNull() {
        assertNull(CityOwnershipUtils.ownerCityOfPlace("某无名小店"));
        assertNull(CityOwnershipUtils.ownerCityOfPlace(null));
    }

    @Test
    @DisplayName("景点归属校验 - 同城与跨城 - 应正确判定")
    void belongsToCity_shouldJudgeOwnership() {
        assertTrue(CityOwnershipUtils.belongsToCity("故宫", "北京"));
        assertFalse(CityOwnershipUtils.belongsToCity("故宫", "杭州"));
        assertTrue(CityOwnershipUtils.belongsToCity("故宫", null), "目标城市为空时放行");
    }

    @Test
    @DisplayName("景点归属校验 - 归属未知 - 应放行")
    void belongsToCity_unknownOwner_shouldBeAllowed() {
        assertTrue(CityOwnershipUtils.belongsToCity("某无名小店", "杭州"));
    }

    // ==================== filterActivitiesForCity ====================

    @Test
    @DisplayName("跨城过滤 - 全类型 - 应剔除异地 visit 且跳过 transit")
    void filterActivitiesForCity_shouldRejectCrossCityVisit() {
        List<Sample> items = List.of(
                new Sample("故宫", "visit"),
                new Sample("西湖", "visit"),
                new Sample("午餐·楼外楼", "meal"),
                new Sample("地铁", "transit"));

        CityOwnershipUtils.CityVerifyResult<Sample> result =
                CityOwnershipUtils.filterActivitiesForCity(items, Sample::name, Sample::type, "杭州");

        assertEquals(List.of("故宫"), result.rejected());
        assertEquals(3, result.visitTotal(), "visit+meal 参与校验，transit 跳过");
        assertEquals(List.of("西湖", "午餐·楼外楼", "地铁"),
                result.kept().stream().map(Sample::name).toList());
    }

    @Test
    @DisplayName("跨城过滤 - 目标城市为空 - 应原样返回")
    void filterActivitiesForCity_blankCity_shouldReturnAll() {
        List<Sample> items = List.of(new Sample("故宫", "visit"), new Sample("西湖", "visit"));
        CityOwnershipUtils.CityVerifyResult<Sample> result =
                CityOwnershipUtils.filterActivitiesForCity(items, Sample::name, Sample::type, null);
        assertEquals(2, result.kept().size());
        assertTrue(result.rejected().isEmpty());
    }

    @Test
    @DisplayName("跨城过滤 - visit 校验 - meal 不计入 visitTotal")
    void filterVisitsForCity_shouldSkipMeal() {
        List<Sample> items = List.of(
                new Sample("故宫", "visit"),
                new Sample("午餐·全聚德", "meal"),
                new Sample("西湖", "visit"));

        CityOwnershipUtils.CityVerifyResult<Sample> result =
                CityOwnershipUtils.filterVisitsForCity(items, Sample::name, Sample::type, "杭州");

        assertEquals(2, result.visitTotal(), "meal 不计入 visitTotal");
        assertEquals(List.of("故宫"), result.rejected());
    }

    @Test
    @DisplayName("跨城过滤 - 过半被剔除 - 应判定 overwhelmed")
    void cityVerifyResult_overHalfRejected_shouldBeOverwhelmed() {
        List<Sample> items = List.of(
                new Sample("故宫", "visit"),
                new Sample("颐和园", "visit"),
                new Sample("西湖", "visit"));

        CityOwnershipUtils.CityVerifyResult<Sample> result =
                CityOwnershipUtils.filterVisitsForCity(items, Sample::name, Sample::type, "杭州");

        assertTrue(result.isOverwhelmed());
        assertEquals("故宫、颐和园", result.rejectedSummary());
    }

    @Test
    @DisplayName("跨城过滤 - null 入参 - 应返回空结果")
    void filterActivitiesForCity_nullItems_shouldReturnEmpty() {
        CityOwnershipUtils.CityVerifyResult<Sample> result =
                CityOwnershipUtils.filterActivitiesForCity(null, Sample::name, Sample::type, "杭州");
        assertTrue(result.kept().isEmpty());
        assertEquals(0, result.visitTotal());
    }
}
