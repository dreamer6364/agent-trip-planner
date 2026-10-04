package com.tripplanner.trip.service;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 落库餐次时间窗守卫（BUGFIX 1.31.0）
 *
 * <p>入库前校验活动列表：每天午餐/晚餐<b>存在</b>且<b>落在作息窗口</b>
 * （午 11:00-13:30 / 晚 17:00-19:30）。<b>仅告警不改数据、不拦截</b>——
 * 正式修复在 plan-service 管线（ensureDailyMeals 强制入窗 + 末位 checkMealWindowsStep
 * 检查节点），本守卫兜住不经过 postPipeline 的落库路径：
 * TripService AI 直落/回退内联（:185/:595 之后）与 Planning Worker 持久化
 * （InternalController.createVersion）。出窗/缺餐一律 WARN，便于发现绕过管线的回归。</p>
 *
 * <p><b>槽位判定</b>与 plan-service TripPlanningAgent#mealSlotOf 同口径：餐类活动
 * （meal/restaurant，大小写不敏感）按名称含「早/午/晚」判定，否则按时刻推断
 * （&lt;10:00 早 / &gt;16:00 晚 / 其余午）；非餐类活动不参与判定。</p>
 *
 * <p><b>首日退化</b>与 DailyMealPlanner 窗口右缘同口径：首日出发时刻本身晚于窗口右缘时
 * 右缘放宽为午 15:00 / 晚 20:00（保住「出发晚也能用餐」），其余天严格 13:30 / 19:30。</p>
 *
 * <p><b>字段方言</b>：支持 snake_case（TripService 落库映射 activity_type/poi_name/
 * scheduled_start，时刻为 "HH:mm"、day 为 1 基）与 camelCase（InternalController worker
 * 持久化 activityType/poiName/scheduledStart，时刻为 ISO LocalDateTime、按日期归天）。</p>
 */
@Slf4j
public final class MealWindowGuard {

    private MealWindowGuard() {
    }

    /**
     * 餐次时间窗守卫：发现出窗/缺餐即 WARN（仅告警）
     *
     * @param activities 待入库活动列表（两种字段方言均可）
     * @param timeStart  行程出发时刻（ISO/HH:mm，可空；用于首日退化口径与按日期归天）
     * @param traceId    调用点标识（如 tripId / versionId），便于日志检索
     */
    public static void warnIfViolations(List<Map<String, Object>> activities, String timeStart, String traceId) {
        if (activities == null || activities.isEmpty()) {
            return;
        }
        try {
            List<String> violations = findViolations(activities, timeStart);
            if (!violations.isEmpty()) {
                log.warn("餐次时间窗守卫[{}]: {} 处违规（仅告警不拦截）: {}",
                        traceId, violations.size(), String.join("; ", violations));
            }
        } catch (Exception e) {
            log.warn("餐次时间窗守卫[{}] 执行异常: {}", traceId, e.getMessage());
        }
    }

    /**
     * 违规清单：如 {@code day1 午餐 15:11 出窗} / {@code day2 晚餐缺失}；无违规返回空
     */
    static List<String> findViolations(List<Map<String, Object>> activities, String timeStart) {
        List<String> out = new ArrayList<>();
        int firstFloor = firstDayFloor(timeStart);
        LocalDate firstDate = dateOf(timeStart);

        Map<Integer, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> a : activities) {
            byDay.computeIfAbsent(dayOf(a, firstDate), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<Integer, List<Map<String, Object>>> e : byDay.entrySet()) {
            int day = e.getKey();
            int dayFloor = day <= 1 ? firstFloor : 8 * 60;
            for (boolean lunch : new boolean[]{true, false}) {
                String slotWord = lunch ? "午餐" : "晚餐";
                Map<String, Object> found = null;
                for (Map<String, Object> a : e.getValue()) {
                    String slot = mealSlotOf(a);
                    if (slot == null) continue;
                    if (lunch && "l".equals(slot)) {
                        found = a;
                        break;
                    }
                    if (!lunch && "d".equals(slot)) {
                        found = a;
                        break;
                    }
                }
                if (found == null) {
                    out.add("day" + day + " " + slotWord + "缺失");
                    continue;
                }
                Integer s = minuteOfDayOf(found);
                if (s == null) {
                    continue;
                }
                int winEnd = lunch ? 13 * 60 + 30 : 19 * 60 + 30;
                // 首日出发晚于右缘 → 退化为 15:00 / 20:00（与 DailyMealPlanner.windowCap 同口径）
                int cap = dayFloor > winEnd ? (lunch ? 15 * 60 : 20 * 60) : winEnd;
                int winStart = lunch ? 11 * 60 : 17 * 60;
                if (s < winStart || s > cap) {
                    out.add(String.format("day%d %s %02d:%02d 出窗", day, slotWord, s / 60, s % 60));
                }
            }
        }
        return out;
    }

    /** 餐类活动槽位（l/d/b）；非餐类返回 null（与 plan-service mealSlotOf 同口径） */
    private static String mealSlotOf(Map<String, Object> a) {
        String type = String.valueOf(firstOf(a, "activity_type", "activityType", "type", "visit"))
                .toLowerCase();
        if (!"meal".equals(type) && !"restaurant".equals(type)) {
            return null;
        }
        String name = String.valueOf(firstOf(a, "poi_name", "poiName", "name", ""));
        if (name.contains("早")) return "b";
        if (name.contains("晚")) return "d";
        if (name.contains("午")) return "l";
        Integer s = minuteOfDayOf(a);
        if (s == null) return null;
        if (s < 10 * 60) return "b";
        if (s > 16 * 60) return "d";
        return "l";
    }

    /** 活动时刻（分钟）：支持 "HH:mm" 与 ISO "yyyy-MM-ddTHH:mm[:ss]"；解析失败返回 null */
    private static Integer minuteOfDayOf(Map<String, Object> a) {
        Object v = firstOf(a, "scheduled_start", "scheduledStart", "startTime", null);
        if (v == null) return null;
        String s = String.valueOf(v).trim().replace(' ', 'T');
        try {
            // ISO：截取 T 之后的时刻部分
            if (s.length() >= 10 && s.charAt(4) == '-') {
                LocalTime t = LocalTime.parse(s.substring(s.indexOf('T') + 1));
                return t.getHour() * 60 + t.getMinute();
            }
            String[] parts = s.split(":");
            if (parts.length >= 2) {
                return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
            }
        } catch (Exception ignore) {
            // 格式异常按无法解析处理
        }
        return null;
    }

    /** 活动归天（1 基）：优先显式 day 字段；否则按 ISO 日期相对行程首日折算 */
    private static int dayOf(Map<String, Object> a, LocalDate firstDate) {
        Object d = firstOf(a, "day", null);
        if (d instanceof Number n) {
            int day = n.intValue();
            return day <= 0 ? 1 : day;
        }
        if (d != null) {
            try {
                int day = Integer.parseInt(String.valueOf(d).trim());
                return day <= 0 ? 1 : day;
            } catch (NumberFormatException ignore) {
                // 转数字失败时继续按日期归天
            }
        }
        if (firstDate != null) {
            Object st = firstOf(a, "scheduled_start", "scheduledStart", null);
            if (st != null) {
                try {
                    String s = String.valueOf(st).replace(' ', 'T');
                    // 仅 ISO（带日期）时刻可归天
                    if (s.length() >= 10 && s.charAt(4) == '-') {
                        LocalDate d2 = LocalDate.parse(s.substring(0, 10));
                        return (int) (d2.toEpochDay() - firstDate.toEpochDay()) + 1;
                    }
                } catch (Exception ignore) {
                    // 日期解析失败按首日处理
                }
            }
        }
        return 1;
    }

    /** 首日日窗口下界（分钟）：max(08:00, 出发时刻)；缺失按 08:00 */
    private static int firstDayFloor(String timeStart) {
        LocalTime t = timeOf(timeStart);
        if (t == null) return 8 * 60;
        return Math.max(8 * 60, t.getHour() * 60 + t.getMinute());
    }

    /** 行程首日日期；无法解析返回 null */
    private static LocalDate dateOf(String timeStart) {
        if (timeStart == null || timeStart.isBlank()) return null;
        try {
            String s = timeStart.trim().replace(' ', 'T');
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 出发时刻；支持 ISO/带日期与 "HH:mm"，无法解析返回 null */
    private static LocalTime timeOf(String timeStart) {
        if (timeStart == null || timeStart.isBlank()) return null;
        try {
            String s = timeStart.trim().replace(' ', 'T');
            String t = s.contains("T") ? s.substring(s.indexOf('T') + 1) : s;
            String[] hm = t.split(":");
            return LocalTime.of(Integer.parseInt(hm[0].trim()), Integer.parseInt(hm[1].trim()));
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 按键序取首个非空值；keys 中的 null 作为终止哨兵（不再查表） */
    private static Object firstOf(Map<String, Object> a, String... keys) {
        for (String k : keys) {
            if (k == null) {
                return null;
            }
            Object v = a.get(k);
            if (v != null && !"null".equals(String.valueOf(v))) {
                return v;
            }
        }
        return null;
    }
}
