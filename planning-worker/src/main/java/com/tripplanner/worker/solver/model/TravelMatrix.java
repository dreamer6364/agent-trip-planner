package com.tripplanner.worker.solver.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 交通时间/距离矩阵
 * 支持多模式：walk, transit, drive, bike
 */
@Data
@Builder
public class TravelMatrix {

    /**
     * 矩阵大小 (活动数)
     */
    private int size;

    /**
     * 时间矩阵: travelTimeMinutes[i][j][mode] = 从 i 到 j 的在途时间(分钟)
     * 使用 Map 存储稀疏矩阵，或三维数组
     */
    private int[][][] travelTimeMinutes; // [i][j][modeIndex]

    /**
     * 距离矩阵: distanceMeters[i][j] = 直线距离/路网距离(米)
     */
    private int[][] distanceMeters;

    /**
     * 模式索引映射
     */
    private Map<String, Integer> modeIndex; // "walk"->0, "transit"->1, "drive"->2, "bike"->3

    /**
     * 获取指定模式下的旅行时间
     */
    public int getTravelTime(int from, int to, String mode) {
        if (mode == null || mode.isBlank()) {
            mode = "transit";
        }
        Integer idx = modeIndex.get(mode.toLowerCase());
        if (idx == null || travelTimeMinutes == null) {
            // 回退：使用直线距离估算
            if (distanceMeters != null && from < distanceMeters.length && to < distanceMeters[0].length) {
                return estimateTimeByDistance(distanceMeters[from][to], mode);
            }
            return 30; // 默认 30 分钟
        }
        return travelTimeMinutes[from][to][idx];
    }

    /**
     * 根据距离和模式估算时间
     */
    private int estimateTimeByDistance(int distanceMeters, String mode) {
        if (mode == null || mode.isBlank()) mode = "transit";
        double speedMps = switch (mode.toLowerCase()) {
            case "walk" -> 1.4;      // ~5 km/h
            case "bike" -> 5.0;      // ~18 km/h
            case "transit" -> 8.3;   // ~30 km/h (含等待)
            case "drive" -> 13.9;    // ~50 km/h
            default -> 5.0;
        };
        return (int) Math.ceil(distanceMeters / speedMps / 60.0); // 分钟
    }

    /**
     * 创建默认矩阵 (仅直线距离)
     */
    public static TravelMatrix createDefault(int size, int[][] distances, Map<String, Integer> modeIndex) {
        int modeCount = modeIndex.size();
        int[][][] times = new int[size][size][modeCount];
        
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i != j && distances != null) {
                    int dist = distances[i][j];
                    for (Map.Entry<String, Integer> entry : modeIndex.entrySet()) {
                        times[i][j][entry.getValue()] = estimateTimeStatic(dist, entry.getKey());
                    }
                }
            }
        }

        return TravelMatrix.builder()
                .size(size)
                .travelTimeMinutes(times)
                .distanceMeters(distances)
                .modeIndex(modeIndex)
                .build();
    }

    private static int estimateTimeStatic(int distanceMeters, String mode) {
        double speedMps = switch (mode.toLowerCase()) {
            case "walk" -> 1.4;
            case "bike" -> 5.0;
            case "transit" -> 8.3;
            case "drive" -> 13.9;
            default -> 5.0;
        };
        return (int) Math.ceil(distanceMeters / speedMps / 60.0);
    }
}