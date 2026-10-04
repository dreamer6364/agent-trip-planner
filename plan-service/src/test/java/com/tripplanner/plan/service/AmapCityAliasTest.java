package com.tripplanner.plan.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 高德 city 参数别名与区域半径测试（BUGFIX 1.30.0）
 */
class AmapCityAliasTest {

    @Test
    @DisplayName("城市别名 - 长白山 - 映射为高德可识别的安图县")
    void toAmapCity_changbaishan_shouldMapToAntuCounty() {
        assertEquals("安图县", AmapCityAlias.toAmapCity("长白山"));
    }

    @Test
    @DisplayName("城市别名 - 普通城市 - 原样返回")
    void toAmapCity_normalCity_shouldReturnAsIs() {
        assertEquals("杭州", AmapCityAlias.toAmapCity("杭州"));
        assertEquals("北京", AmapCityAlias.toAmapCity("北京"));
    }

    @Test
    @DisplayName("城市别名 - 空输入 - 原样返回不抛异常")
    void toAmapCity_blank_shouldReturnAsIs() {
        assertNull(AmapCityAlias.toAmapCity(null));
        assertEquals("", AmapCityAlias.toAmapCity(""));
        assertEquals("  ", AmapCityAlias.toAmapCity("  "));
    }

    @Test
    @DisplayName("区域半径 - 长白山 - 返回 120km 专属半径")
    void radiusKm_regionCity_shouldReturnRegionRadius() {
        assertEquals(120.0, AmapCityAlias.radiusKm("长白山", 20.0));
    }

    @Test
    @DisplayName("区域半径 - 普通城市 - 返回默认半径")
    void radiusKm_normalCity_shouldReturnDefault() {
        assertEquals(20.0, AmapCityAlias.radiusKm("杭州", 20.0));
        assertEquals(150.0, AmapCityAlias.radiusKm(null, 150.0));
    }
}
