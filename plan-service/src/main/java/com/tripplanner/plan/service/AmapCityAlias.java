package com.tripplanner.plan.service;

import java.util.Map;

/**
 * 高德 city 参数别名与区域检索半径。
 *
 * <p>部分景区型目的地不是高德行政城市（如「长白山」），直接传入会让
 * citylimit 静默失效并返回全国结果（BUGFIX 1.30.0：跨城餐厅/商场混入
 * 行程、地理编码串到新疆的根因）。此类把原名映射到主体行政区，
 * 并给出区域型目的地的距离阈值（公园跨度远大于市区 20km 阈值）。</p>
 */
public final class AmapCityAlias {

    /** 景区型目的地 -> 高德可识别的行政区 city 参数 */
    private static final Map<String, String> AMAP_CITY_ALIAS = Map.of(
            "长白山", "安图县"
    );

    /** 区域型目的地检索/校验半径（km）；普通城市沿用调用方默认值 */
    private static final Map<String, Double> REGION_RADIUS_KM = Map.of(
            "长白山", 120.0
    );

    private AmapCityAlias() {
    }

    /**
     * 转换为高德可识别的 city 参数；无别名时原样返回。
     *
     * @param city 业务城市名（可空）
     * @return 高德 city 参数（city 为空时原样返回）
     */
    public static String toAmapCity(String city) {
        if (city == null || city.isBlank()) {
            return city;
        }
        String trimmed = city.trim();
        return AMAP_CITY_ALIAS.getOrDefault(trimmed, trimmed);
    }

    /**
     * 目的地的检索/距离校验半径。
     *
     * @param city      业务城市名
     * @param defaultKm 普通城市默认半径
     * @return 区域型目的地返回专属半径，否则返回默认半径
     */
    public static double radiusKm(String city, double defaultKm) {
        if (city == null || city.isBlank()) {
            return defaultKm;
        }
        Double radius = REGION_RADIUS_KM.get(city.trim());
        return radius != null ? radius : defaultKm;
    }
}
