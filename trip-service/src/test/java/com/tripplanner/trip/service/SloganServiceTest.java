package com.tripplanner.trip.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 景点个性化签名服务单元测试
 */
class SloganServiceTest {

    private final SloganService sloganService = new SloganService();

    // ==================== 词库匹配 ====================

    @Test
    @DisplayName("景点签名 - 词库精确匹配 - 应返回该景点的词库文案")
    void generateSlogan_exactMatch_shouldReturnCuratedSlogan() {
        Set<String> expected = Set.of("淡妆浓抹总相宜", "一半湖山一半城", "泛舟湖上，才懂江南");
        String slogan = sloganService.generateSlogan("西湖", "visit", "trip-1");
        assertTrue(expected.contains(slogan), "实际: " + slogan);
    }

    @Test
    @DisplayName("景点签名 - 模糊匹配 - 城市前缀景点名应命中词库")
    void generateSlogan_fuzzyMatch_shouldHitDict() {
        Set<String> expected = Set.of("飞阁回澜，青岛的序章", "栈桥尽头，海鸥起落");
        String slogan = sloganService.generateSlogan("青岛栈桥", "visit", "t1");
        assertTrue(expected.contains(slogan), "实际: " + slogan);
    }

    @Test
    @DisplayName("景点签名 - 餐次前缀 - 「午餐·楼外楼」应剥离前缀命中词库")
    void generateSlogan_mealPrefix_shouldStripAndMatch() {
        Set<String> expected = Set.of("山外青山楼外楼", "一桌杭帮菜，半部西湖史");
        String slogan = sloganService.generateSlogan("午餐·楼外楼", "meal", "t1");
        assertTrue(expected.contains(slogan), "实际: " + slogan);
    }

    // ==================== 同景多签轮换 ====================

    @Test
    @DisplayName("景点签名 - 同 seed 稳定 - 同行程多次生成结果一致")
    void generateSlogan_sameSeed_shouldBeStable() {
        String first = sloganService.generateSlogan("西湖", "visit", "trip-stable");
        for (int i = 0; i < 20; i++) {
            assertEquals(first, sloganService.generateSlogan("西湖", "visit", "trip-stable"));
        }
    }

    @Test
    @DisplayName("景点签名 - 不同 seed 轮换 - 多行程应覆盖多条签名")
    void generateSlogan_differentSeeds_shouldRotateVariants() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            seen.add(sloganService.generateSlogan("西湖", "visit", "trip-" + i));
        }
        assertTrue(seen.size() > 1, "多签未轮换，仅得到: " + seen);
    }

    @Test
    @DisplayName("景点签名 - 单签景点 - 任意 seed 结果一致")
    void generateSlogan_singleVariant_shouldIgnoreSeed() {
        String a = sloganService.generateSlogan("周庄", "visit", "t1");
        String b = sloganService.generateSlogan("周庄", "visit", "t2");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("景点签名 - 无 seed - 两次生成结果一致")
    void generateSlogan_nullSeed_shouldBeDeterministic() {
        assertEquals(sloganService.generateSlogan("西湖", "visit"),
                sloganService.generateSlogan("西湖", "visit"));
    }

    // ==================== 名称特征模板 ====================

    @Test
    @DisplayName("景点签名 - 特征模板 - 老城区应命中街巷组而非海岛组")
    void generateSlogan_featureTemplate_shouldPickLongestKeyword() {
        String slogan = sloganService.generateSlogan("青岛老城区", "visit", "t1");
        assertTrue(slogan.contains("青岛老城区"), "实际: " + slogan);
        assertFalse(slogan.contains("海风"), "「岛」误判命中海岛组: " + slogan);
        assertTrue(slogan.contains("烟火气") || slogan.contains("慢下来"), "实际: " + slogan);
    }

    @Test
    @DisplayName("景点签名 - 特征模板 - 山字公园应命中园林组而非山岳组")
    void generateSlogan_featureTemplate_parkShouldNotMatchMountain() {
        String slogan = sloganService.generateSlogan("小鱼山公园", "visit", "t1");
        assertTrue(slogan.contains("小鱼山公园"), "实际: " + slogan);
        assertFalse(slogan.contains("山高人为峰"), "「山」误判命中山岳组: " + slogan);
    }

    @Test
    @DisplayName("景点签名 - 餐饮模板 - 词库未覆盖餐厅应生成含名餐饮签名")
    void generateSlogan_unknownMeal_shouldUseMealTemplate() {
        String slogan = sloganService.generateSlogan("某弄堂小馆", "meal", "t1");
        assertNotNull(slogan);
        Set<String> allowed = Set.of("把这座城市吃个明白", "某弄堂小馆，一城风味落座", "人间烟火，某弄堂小馆开席");
        assertTrue(allowed.contains(slogan), "实际: " + slogan);
    }

    @Test
    @DisplayName("景点签名 - 类型兜底 - 无特征景点应返回类型签名")
    void generateSlogan_typeFallback_shouldReturnTypeInfo() {
        String slogan = sloganService.generateSlogan("某无名之地", "museum", "t1");
        assertEquals("历史与艺术的殿堂", slogan);
    }

    @Test
    @DisplayName("景点签名 - 空名称 - 应返回 null")
    void generateSlogan_nullPoiName_shouldReturnNull() {
        assertNull(sloganService.generateSlogan(null, "visit"));
    }

    // ==================== 泛化判定 ====================

    @Test
    @DisplayName("泛化判定 - 空值 - 应判定为泛化")
    void isGeneric_blank_shouldBeGeneric() {
        assertTrue(sloganService.isGeneric(null, "西湖"));
        assertTrue(sloganService.isGeneric("", "西湖"));
        assertTrue(sloganService.isGeneric("  ", "西湖"));
    }

    @Test
    @DisplayName("泛化判定 - 等同名称 - 应判定为泛化")
    void isGeneric_sameAsName_shouldBeGeneric() {
        assertTrue(sloganService.isGeneric("西湖", "西湖"));
        assertTrue(sloganService.isGeneric("雷峰塔", "雷峰塔"));
        assertTrue(sloganService.isGeneric("晚餐·张生记", "晚餐·张生记"));
    }

    @Test
    @DisplayName("泛化判定 - 动词+名称结构 - 应判定为泛化")
    void isGeneric_verbPrefixName_shouldBeGeneric() {
        assertTrue(sloganService.isGeneric("游览西湖", "西湖"));
        assertTrue(sloganService.isGeneric("逛河坊街", "河坊街"));
        assertTrue(sloganService.isGeneric("参观灵隐寺", "灵隐寺"));
        assertTrue(sloganService.isGeneric("游览西湖美景", "西湖"));
    }

    @Test
    @DisplayName("泛化判定 - 动词开头短文案 - 应判定为泛化")
    void isGeneric_verbPhraseWithoutName_shouldBeGeneric() {
        assertTrue(sloganService.isGeneric("漫步", "八大关"));
        assertTrue(sloganService.isGeneric("观海景", "青岛栈桥"));
        assertTrue(sloganService.isGeneric("品尝杭帮菜", "午餐·楼外楼"));
        assertTrue(sloganService.isGeneric("了解啤酒文化", "青岛啤酒博物馆"));
    }

    @Test
    @DisplayName("泛化判定 - 提及景点的文案 - 应保留")
    void isGeneric_mentionsPoi_shouldNotBeGeneric() {
        assertFalse(sloganService.isGeneric("骑行古城墙", "城墙"));
        assertFalse(sloganService.isGeneric("张生记，一城风味落座", "张生记"));
        assertFalse(sloganService.isGeneric("海风翻过沙滩", "沙滩"));
    }

    @Test
    @DisplayName("泛化判定 - 词库精选文案 - 应保留")
    void isGeneric_curatedSlogan_shouldNotBeGeneric() {
        assertFalse(sloganService.isGeneric("淡妆浓抹总相宜", "西湖"));
        assertFalse(sloganService.isGeneric("世界第八大奇迹", "兵马俑"));
    }

    @Test
    @DisplayName("泛化判定 - 7 字以上非词库文案 - 应保留")
    void isGeneric_longNonNameSlogan_shouldNotBeGeneric() {
        assertFalse(sloganService.isGeneric("邂逅一场山海之约", "某海湾"));
    }
}
