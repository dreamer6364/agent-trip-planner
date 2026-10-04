package com.tripplanner.plan.constant;

/**
 * 行程活动频率（节奏）等级
 *
 * 决定每天安排多少游览活动、每日游览总时长落在哪个区间。
 * 小时口径：每日游览活动时长合计（不含用餐与交通）。
 *
 * <ul>
 *   <li>{@link #COMPACT 紧凑} —— 8~10 小时/天，尽量多排景点</li>
 *   <li>{@link #MODERATE 适中} —— 6~8 小时/天，经典行程密度</li>
 *   <li>{@link #RELAXED 宽松} —— 3~5 小时/天，留足自由时间</li>
 * </ul>
 *
 * @author TripForge Team
 * @since 1.12.0
 */
public enum TripPace {

    COMPACT("compact", "紧凑", 8, 10, 4, 6),
    MODERATE("moderate", "适中", 6, 8, 3, 5),
    RELAXED("relaxed", "宽松", 3, 5, 3, 4);

    /** 对外/接口使用的编码 */
    private final String code;
    /** 中文名称 */
    private final String label;
    /** 每日游览时长下限（小时，不含用餐与交通） */
    private final int minHours;
    /** 每日游览时长上限（小时，不含用餐与交通） */
    private final int maxHours;
    /** 每日游览活动数下限 */
    private final int minVisits;
    /** 每日游览活动数上限 */
    private final int maxVisits;

    TripPace(String code, String label, int minHours, int maxHours, int minVisits, int maxVisits) {
        this.code = code;
        this.label = label;
        this.minHours = minHours;
        this.maxHours = maxHours;
        this.minVisits = minVisits;
        this.maxVisits = maxVisits;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getMinHours() {
        return minHours;
    }

    public int getMaxHours() {
        return maxHours;
    }

    public int getMinVisits() {
        return minVisits;
    }

    public int getMaxVisits() {
        return maxVisits;
    }

    /** 每日活动数下限 = 游览活动下限 + 午餐 + 晚餐 */
    public int getMinDailyActivities() {
        return minVisits + 2;
    }

    /** 每日游览时长标注，如「8~10 小时/天」 */
    public String getHoursLabel() {
        return minHours + "~" + maxHours + " 小时/天";
    }

    /** 未知编码一律回落到「适中」，保证规划链路永不收到 null */
    public static TripPace of(String code) {
        if (code == null || code.isBlank()) {
            return MODERATE;
        }
        String key = code.trim().toLowerCase();
        for (TripPace pace : values()) {
            if (pace.code.equals(key)) {
                return pace;
            }
        }
        return MODERATE;
    }

    /** 写入提示词的节奏说明 */
    public String promptLine() {
        return String.format("【%s %s】每天 %d~%d 个游览活动（另加午餐、晚餐），"
                        + "每日游览总时长约 %d~%d 小时（不含用餐与交通）",
                label, code, minVisits, maxVisits, minHours, maxHours);
    }
}
