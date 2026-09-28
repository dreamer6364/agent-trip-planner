package com.tripplanner.plan.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 换版排除·纯函数工具集
 *
 * 换版规划（variant）中所有不依赖服务与日志的判定/挑选逻辑集中于此，
 * 便于单元测试（plan-service 此前无任何测试，本类为首个覆盖对象）。
 *
 * @author TripForge Team
 * @since 1.18.0
 */
public final class VariantExclusions {

    private VariantExclusions() {
    }

    /** 名称归一：忽略所有空白；null 归为空串 */
    public static String variantNorm(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }

    /** 去除名称中的括号装饰：「楼外楼(总店)」→「楼外楼」 */
    public static String stripDecorations(String name) {
        String s = name.replaceAll("[（(【\\[].*?[)）】\\]]", "");
        s = s.replaceAll("[()（）【】\\[\\]]", "");
        return s.trim();
    }

    /**
     * 名称是否命中排除列表
     *
     * 匹配规则（忽略空白与括号装饰）：
     * <ol>
     *   <li>等值（原名或去装饰后）：「楼外楼」=「楼外楼」、「楼外楼(总店)」去装饰后命中</li>
     *   <li>双向前缀且短名 ≥3 字：排除「楼外楼」→命中「楼外楼湖滨店」；排除「雷峰塔遗址公园」→命中「雷峰塔」</li>
     * </ol>
     * 短名 &lt;3 字不触发前缀，避免排除「西湖」误伤「西湖文化广场」这类不同景点。
     *
     * @param name       待判定名称
     * @param excludePois 排除列表（可含 null / 空串，逐项跳过）
     */
    public static boolean isExcludedName(String name, List<String> excludePois) {
        if (name == null || excludePois == null) {
            return false;
        }
        String norm = variantNorm(name);
        if (norm.isEmpty()) {
            return false;
        }
        String bare = stripDecorations(norm);
        if (bare.isEmpty()) {
            bare = norm;
        }
        for (String raw : excludePois) {
            if (raw == null) {
                continue;
            }
            String excluded = variantNorm(raw);
            if (excluded.isEmpty()) {
                continue;
            }
            String excludedBare = stripDecorations(excluded);
            if (excludedBare.isEmpty()) {
                excludedBare = excluded;
            }
            if (bare.equals(excludedBare)) {
                return true;
            }
            String shorter = bare.length() <= excludedBare.length() ? bare : excludedBare;
            String longer = bare.length() <= excludedBare.length() ? excludedBare : bare;
            if (shorter.length() >= 3 && longer.startsWith(shorter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从候选池剔除排除项（rawInput 明确提到的主题地点保留），防缺口填充/完整性兜底重新引入
     *
     * @param pool       候选池（原地修改；null 或空直接返回）
     * @param excludePois 排除列表（null 或空为 no-op，非换版路径零开销）
     * @param rawInput   用户原文；null 视为空串
     */
    public static void removeExcludedFromPool(List<Map<String, Object>> pool,
                                              List<String> excludePois, String rawInput) {
        if (pool == null || pool.isEmpty() || excludePois == null || excludePois.isEmpty()) {
            return;
        }
        String ri = rawInput == null ? "" : rawInput;
        pool.removeIf(m -> {
            String name = String.valueOf(m.getOrDefault("name", ""));
            return isExcludedName(name, excludePois) && !ri.contains(name);
        });
    }

    /**
     * 从修复候选池挑选：未被行程使用且不在排除列表中的合规真实地点
     *
     * @param repairPool  修复候选池（null 返回 null）
     * @param excludePois 排除列表
     * @param usedNorm    行程已用名称（归一后），保证替换不引入重复
     * @return 第一个合规候选；候选耗尽返回 null
     */
    public static Map<String, Object> pickVisitRepair(List<Map<String, Object>> repairPool,
                                                      List<String> excludePois, Set<String> usedNorm) {
        if (repairPool == null) {
            return null;
        }
        for (Map<String, Object> candidate : repairPool) {
            String name = String.valueOf(candidate.getOrDefault("name", "")).trim();
            String norm = variantNorm(name);
            if (name.isEmpty() || usedNorm.contains(norm)) {
                continue;
            }
            if (isExcludedName(name, excludePois)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 清掉改名后残留的旧 POI 元数据（坐标/地址/评分），
     * 改由后续真实路网与注解流程按新名称重新填充
     */
    public static void stripStalePoiMeta(Map<String, Object> act) {
        for (String key : List.of("lat", "lng", "address", "poi_address", "rating", "cost")) {
            act.remove(key);
        }
    }
}
