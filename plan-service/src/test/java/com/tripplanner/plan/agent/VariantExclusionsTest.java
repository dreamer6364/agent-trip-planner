package com.tripplanner.plan.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VariantExclusions} 换版排除纯函数工具测试
 * 覆盖排除判定全规则、候选池过滤、修复候选挑选与元数据清理。
 */
class VariantExclusionsTest {

    // ---------- variantNorm / stripDecorations ----------

    @Test
    @DisplayName("名称归一 - 忽略空白与null - 空白全部去除")
    void variantNorm_ignoresWhitespaceAndNull() {
        assertEquals("雷峰塔", VariantExclusions.variantNorm("雷 峰 塔"));
        assertEquals("楼外楼", VariantExclusions.variantNorm(" 楼外 楼\n"));
        assertEquals("", VariantExclusions.variantNorm(null));
    }

    @Test
    @DisplayName("装饰剥离 - 各类括号 - 括号及内容整段去除")
    void stripDecorations_removesBracketedSuffix() {
        assertEquals("楼外楼", VariantExclusions.stripDecorations("楼外楼(总店)"));
        assertEquals("楼外楼", VariantExclusions.stripDecorations("楼外楼（湖滨店）"));
        assertEquals("知味观", VariantExclusions.stripDecorations("知味观【吴山店】"));
        assertEquals("张生记", VariantExclusions.stripDecorations("张生记[总店]"));
        assertEquals("西湖", VariantExclusions.stripDecorations("西湖"));
    }

    // ---------- isExcludedName ----------

    @Test
    @DisplayName("排除判定 - 空输入 - null/空白返回false")
    void isExcludedName_emptyInput_false() {
        assertFalse(VariantExclusions.isExcludedName(null, List.of("西湖")));
        assertFalse(VariantExclusions.isExcludedName("", List.of("西湖")));
        assertFalse(VariantExclusions.isExcludedName("  ", List.of("西湖")));
        assertFalse(VariantExclusions.isExcludedName("西湖", null));
        assertFalse(VariantExclusions.isExcludedName("西湖", new ArrayList<>()));
    }

    @Test
    @DisplayName("排除判定 - 等值匹配 - 忽略空白与括号装饰")
    void isExcludedName_equalsMatch() {
        List<String> exclude = List.of("楼外楼", "雷峰塔", "知味观（吴山店）");
        assertTrue(VariantExclusions.isExcludedName("楼外楼", exclude));
        assertTrue(VariantExclusions.isExcludedName(" 楼外楼 ", exclude));
        assertTrue(VariantExclusions.isExcludedName("楼外楼(总店)", exclude));
        // 「知味观（吴山店）」去装饰后与「知味观」等值
        assertTrue(VariantExclusions.isExcludedName("知味观", exclude));
    }

    @Test
    @DisplayName("排除判定 - 双向前缀（短名≥3字） - 店名变体与细化名命中")
    void isExcludedName_prefixMatchShortNameAtLeastThree() {
        List<String> exclude = List.of("楼外楼", "雷峰塔");
        // 排除名是前缀：楼外楼 → 楼外楼湖滨店
        assertTrue(VariantExclusions.isExcludedName("楼外楼湖滨店", exclude));
        // 排除名更长：雷峰塔遗址公园 → 雷峰塔
        assertTrue(VariantExclusions.isExcludedName("雷峰塔遗址公园", List.of("雷峰塔遗址公园")));
        // 短名方向反向：排除「雷峰塔遗址公园」，名称「雷峰塔」短名3字，前缀成立
        assertTrue(VariantExclusions.isExcludedName("雷峰塔", List.of("雷峰塔遗址公园")));
    }

    @Test
    @DisplayName("排除判定 - 短名（<3字）不触发前缀 - 不误伤不同景点")
    void isExcludedName_shortNameNoPrefixNoFalsePositive() {
        // 排除「西湖」不能误伤「西湖文化广场」（不同景点）
        assertFalse(VariantExclusions.isExcludedName("西湖文化广场", List.of("西湖")));
        // 排除「西湖文化广场」也不能反向误伤主题景点「西湖」
        assertFalse(VariantExclusions.isExcludedName("西湖", List.of("西湖文化广场")));
        // 任意包含关系不再命中（旧实现双向 contains 的缺陷）
        assertFalse(VariantExclusions.isExcludedName("河坊街夜市", List.of("坊街")));
    }

    @Test
    @DisplayName("排除判定 - 列表脏数据 - null与空串逐项跳过")
    void isExcludedName_skipsNullAndBlankEntries() {
        List<String> exclude = new ArrayList<>();
        exclude.add(null);
        exclude.add("");
        exclude.add("   ");
        exclude.add("灵隐寺");
        assertTrue(VariantExclusions.isExcludedName("灵隐寺", exclude));
        assertFalse(VariantExclusions.isExcludedName("断桥", exclude));
    }

    // ---------- removeExcludedFromPool ----------

    @Test
    @DisplayName("候选池过滤 - 排除项剔除与主题保留")
    void removeExcludedFromPool_filtersExcludedKeepsMentioned() {
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.add(place("雷峰塔"));
        pool.add(place("断桥"));
        pool.add(place("龙井村"));
        pool.add(place("宋城"));

        VariantExclusions.removeExcludedFromPool(pool, List.of("雷峰塔", "宋城"), "想逛西湖和断桥");

        List<String> names = pool.stream().map(m -> String.valueOf(m.get("name"))).toList();
        assertEquals(List.of("断桥", "龙井村"), names);
    }

    @Test
    @DisplayName("候选池过滤 - rawInput提到的主题地点保留（即使在排除列表）")
    void removeExcludedFromPool_keepsRawInputMentioned() {
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.add(place("雷峰塔"));
        pool.add(place("断桥"));

        // 「雷峰塔」虽在排除列表，但用户原文明确提到 → 主题保留
        VariantExclusions.removeExcludedFromPool(pool, List.of("雷峰塔"), "带孩子去雷峰塔和断桥");

        assertEquals(2, pool.size());
    }

    @Test
    @DisplayName("候选池过滤 - 空排除列表为no-op（非换版路径零开销）")
    void removeExcludedFromPool_emptyExcludeNoop() {
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.add(place("雷峰塔"));
        pool.add(place("断桥"));

        VariantExclusions.removeExcludedFromPool(pool, List.of(), "任意");
        VariantExclusions.removeExcludedFromPool(pool, null, "任意");
        assertEquals(2, pool.size());

        // null/空池不抛异常
        assertDoesNotThrow(() -> VariantExclusions.removeExcludedFromPool(null, List.of("西湖"), "x"));
        assertDoesNotThrow(() -> VariantExclusions.removeExcludedFromPool(new ArrayList<>(), List.of("西湖"), "x"));
    }

    // ---------- pickVisitRepair ----------

    @Test
    @DisplayName("修复挑选 - 跳过已用与排除 - 返回首个合规候选")
    void pickVisitRepair_skipsUsedAndExcluded() {
        List<Map<String, Object>> pool = List.of(place("西湖"), place("雷峰塔"), place("断桥"));
        Set<String> used = new HashSet<>();
        used.add(VariantExclusions.variantNorm("西湖"));

        Map<String, Object> picked = VariantExclusions.pickVisitRepair(
                pool, List.of("雷峰塔"), used);

        assertNotNull(picked);
        assertEquals("断桥", picked.get("name"));
    }

    @Test
    @DisplayName("修复挑选 - 候选耗尽或池为null - 返回null")
    void pickVisitRepair_exhaustedReturnsNull() {
        // 全部已用
        Set<String> used = new HashSet<>(List.of("断桥", "宋城"));
        assertNull(VariantExclusions.pickVisitRepair(List.of(place("断桥"), place("宋城")), List.of(), used));

        // 全部排除
        assertNull(VariantExclusions.pickVisitRepair(
                List.of(place("雷峰塔")), List.of("雷峰塔"), new HashSet<>()));

        // null 池
        assertNull(VariantExclusions.pickVisitRepair(null, List.of(), new HashSet<>()));
    }

    @Test
    @DisplayName("修复挑选 - 替换不引入重复 - 同名候选只取一次")
    void pickVisitRepair_noDuplicateReplacement() {
        List<Map<String, Object>> pool = List.of(place("断桥"));
        Set<String> used = new HashSet<>();

        Map<String, Object> first = VariantExclusions.pickVisitRepair(pool, List.of(), used);
        assertNotNull(first);
        used.add(VariantExclusions.variantNorm(String.valueOf(first.get("name"))));

        assertNull(VariantExclusions.pickVisitRepair(pool, List.of(), used));
    }

    // ---------- stripStalePoiMeta ----------

    @Test
    @DisplayName("元数据清理 - 旧POI坐标/地址/评分 - 全部移除且不影响其他字段")
    void stripStalePoiMeta_removesStaleFieldsOnly() {
        Map<String, Object> act = new HashMap<>();
        act.put("name", "断桥");
        act.put("type", "visit");
        act.put("lat", 30.25);
        act.put("lng", 120.15);
        act.put("address", "浙江省杭州市");
        act.put("poi_address", "西湖区");
        act.put("rating", 4.8);
        act.put("cost", 100);
        act.put("durationMin", 60);

        VariantExclusions.stripStalePoiMeta(act);

        assertEquals("断桥", act.get("name"));
        assertEquals("visit", act.get("type"));
        assertEquals(60, act.get("durationMin"));
        assertFalse(act.containsKey("lat"));
        assertFalse(act.containsKey("lng"));
        assertFalse(act.containsKey("address"));
        assertFalse(act.containsKey("poi_address"));
        assertFalse(act.containsKey("rating"));
        assertFalse(act.containsKey("cost"));
    }

    private static Map<String, Object> place(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        return m;
    }
}
