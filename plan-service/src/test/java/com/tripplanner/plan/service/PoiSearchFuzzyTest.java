package com.tripplanner.plan.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POI 模糊搜索匹配打分单元测试（fuzzyScore / levenshtein / matchTypeOf）
 */
class PoiSearchFuzzyTest {

    // ---------- fuzzyScore ----------

    @Test
    @DisplayName("模糊打分 - 完全同名 - 100精确")
    void fuzzyScore_exact_shouldReturn100() {
        assertThat(PoiSearchService.fuzzyScore("西湖", "西湖")).isEqualTo(100);
        assertThat(PoiSearchService.fuzzyScore("杭州宋城", " 杭州宋城 ")).isEqualTo(100);
    }

    @Test
    @DisplayName("模糊打分 - 单字查含字地点「寺」→红螺寺 - 75包含")
    void fuzzyScore_singleCharContain_shouldReturn75() {
        assertThat(PoiSearchService.fuzzyScore("红螺寺", "寺")).isEqualTo(75);
        assertThat(PoiSearchService.fuzzyScore("灵隐寺", "寺")).isEqualTo(75);
    }

    @Test
    @DisplayName("模糊打分 - 双向前缀（字数不全） - 85前缀")
    void fuzzyScore_prefix_shouldReturn85() {
        assertThat(PoiSearchService.fuzzyScore("杭州宋城", "杭州")).isEqualTo(85);
        assertThat(PoiSearchService.fuzzyScore("杭州", "杭州宋城")).isEqualTo(85);
        assertThat(PoiSearchService.fuzzyScore("宋城景区", "宋")).isEqualTo(85);
    }

    @Test
    @DisplayName("模糊打分 - 候选是查询的子串（字数不全/多余字场景）")
    void fuzzyScore_candidateInQuery() {
        // 候选是查询前缀 → 走双向前缀 85
        assertThat(PoiSearchService.fuzzyScore("杭州宋城", "杭州宋城景")).isEqualTo(85);
        assertThat(PoiSearchService.fuzzyScore("灵隐寺", "灵隐寺景区")).isEqualTo(85);
        // 候选是查询的中段子串 → 65补全
        assertThat(PoiSearchService.fuzzyScore("宋城", "杭州宋城景")).isEqualTo(65);
    }

    @Test
    @DisplayName("模糊打分 - 错别字编辑距离1 - 70模糊匹配")
    void fuzzyScore_typoLev1_shouldReturn70() {
        assertThat(PoiSearchService.fuzzyScore("西湖", "西胡")).isEqualTo(70);
        assertThat(PoiSearchService.fuzzyScore("灵隐寺", "灵隐词")).isEqualTo(70);
    }

    @Test
    @DisplayName("模糊打分 - 滑窗子串错别字命中长名 - 70模糊匹配")
    void fuzzyScore_windowTypoOnLongName_shouldReturn70() {
        // 「西胡」与长名整串 lev 远大于阈值，但其连续子串「西湖」lev=1
        assertThat(PoiSearchService.fuzzyScore("杭州西湖风景名胜区", "西胡")).isEqualTo(70);
        assertThat(PoiSearchService.fuzzyScore("红螺寺景区", "红螺寻")).isEqualTo(70);
    }

    @Test
    @DisplayName("模糊打分 - 滑窗不放宽短查询边界（单字/窗口2的lev2）")
    void fuzzyScore_windowBoundary() {
        // 单字查询不启用滑窗编辑距离
        assertThat(PoiSearchService.fuzzyScore("红螺寺", "湖")).isZero();
        // 窗口=查询长度 2 时，lev=2 不启用（防误配），且其他规则不命中
        assertThat(PoiSearchService.fuzzyScore("灵隐寺", "西湖")).isZero();
    }

    @Test
    @DisplayName("模糊打分 - 编辑距离2且双方≥4字 - 55近似匹配")
    void fuzzyScore_typoLev2LongNames_shouldReturn55() {
        assertThat(PoiSearchService.fuzzyScore("宋城景区", "宋城公园")).isEqualTo(55);
    }

    @Test
    @DisplayName("模糊打分 - 编辑距离2但短名不启用 - 0丢弃")
    void fuzzyScore_lev2ShortNames_shouldReturn0() {
        // 双方 min 长度 2 < 4，不启用距离2
        assertThat(PoiSearchService.fuzzyScore("西湖边", "雷峰塔")).isEqualTo(0);
    }

    @Test
    @DisplayName("模糊打分 - 单字查询不启用编辑距离（防形近字误配） - 0")
    void fuzzyScore_singleCharNoLev_shouldReturn0() {
        assertThat(PoiSearchService.fuzzyScore("西湖", "寺")).isEqualTo(0);
        assertThat(PoiSearchService.fuzzyScore("红螺寺", "湖")).isEqualTo(0);
    }

    @Test
    @DisplayName("模糊打分 - 完全无关 - 0丢弃")
    void fuzzyScore_unrelated_shouldReturn0() {
        assertThat(PoiSearchService.fuzzyScore("灵隐寺", "西湖")).isEqualTo(0);
        assertThat(PoiSearchService.fuzzyScore("", "西湖")).isEqualTo(0);
        assertThat(PoiSearchService.fuzzyScore("西湖", "")).isEqualTo(0);
    }

    // ---------- levenshtein ----------

    @Test
    @DisplayName("编辑距离 - 基础用例")
    void levenshtein_basicCases() {
        assertThat(PoiSearchService.levenshtein("西湖", "西湖")).isZero();
        assertThat(PoiSearchService.levenshtein("西胡", "西湖")).isEqualTo(1);
        assertThat(PoiSearchService.levenshtein("", "西湖")).isEqualTo(2);
        assertThat(PoiSearchService.levenshtein("灵隐寺", "")).isEqualTo(3);
        assertThat(PoiSearchService.levenshtein("宋城景区", "宋城公园")).isEqualTo(2);
    }

    // ---------- matchTypeOf ----------

    @Test
    @DisplayName("得分转展示标签")
    void matchTypeOf_scoreBuckets() {
        assertThat(PoiSearchService.matchTypeOf(100)).isEqualTo("精确");
        assertThat(PoiSearchService.matchTypeOf(85)).isEqualTo("前缀");
        assertThat(PoiSearchService.matchTypeOf(75)).isEqualTo("包含");
        assertThat(PoiSearchService.matchTypeOf(70)).isEqualTo("模糊匹配");
        assertThat(PoiSearchService.matchTypeOf(55)).isEqualTo("近似匹配");
    }
}
