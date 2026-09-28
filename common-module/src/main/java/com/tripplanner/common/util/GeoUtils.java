package com.tripplanner.common.util;

/**
 * 地理空间计算工具类
 * 基于 Haversine 公式计算两点间球面距离
 */
public final class GeoUtils {

    // 地球半径（米）
    private static final double EARTH_RADIUS_M = 6371000.0;

    private GeoUtils() {}

    /**
     * 计算两点间球面距离（米）
     *
     * @param lat1 纬度1
     * @param lon1 经度1
     * @param lat2 纬度2
     * @param lon2 经度2
     * @return 距离（米）
     */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    /**
     * 计算两点间距离（公里）
     */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        return distance(lat1, lon1, lat2, lon2) / 1000.0;
    }

    /**
     * 判断两点是否在指定半径内
     */
    public static boolean withinRadius(double lat1, double lon1, double lat2, double lon2, double radiusMeters) {
        return distance(lat1, lon1, lat2, lon2) <= radiusMeters;
    }

    /**
     * 计算边界框（用于数据库空间索引查询）
     * 返回 [minLon, minLat, maxLon, maxLat]
     */
    public static double[] boundingBox(double lat, double lon, double radiusMeters) {
        // 1度纬度约等于 111km
        double latDelta = radiusMeters / 111000.0;
        // 1度经度在该纬度下的距离
        double lonDelta = radiusMeters / (111000.0 * Math.cos(Math.toRadians(lat)));
        
        return new double[]{
                lon - lonDelta,
                lat - latDelta,
                lon + lonDelta,
                lat + latDelta
        };
    }

    /**
     * 格式化坐标为 WKT POINT 字符串
     */
    public static String toWktPoint(double lon, double lat) {
        return String.format("POINT(%f %f)", lon, lat);
    }

    /**
     * 解析 WKT POINT 字符串
     * 返回 [lon, lat]
     */
    public static double[] parseWktPoint(String wkt) {
        if (wkt == null || !wkt.toUpperCase().startsWith("POINT")) {
            throw new IllegalArgumentException("Invalid WKT POINT: " + wkt);
        }
        String coords = wkt.substring(wkt.indexOf('(') + 1, wkt.indexOf(')')).trim();
        String[] parts = coords.split("\\s+");
        return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    }
}