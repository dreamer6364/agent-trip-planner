package com.tripplanner.worker.solver.model;

import com.tripplanner.common.model.ParsedInput;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规划问题定义
 * 包含求解器所需的所有输入数据
 */
@Data
@Builder(toBuilder = true)
public class PlanningProblem {

    /**
     * 行程 ID
     */
    private String tripId;

    /**
     * 版本 ID
     */
    private String versionId;

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 解析后的结构化输入
     */
    private ParsedInput parsedInput;

    /**
     * 目标城市（trip.parsedInput.city 优先，缺失回退正则提取；用于 VERIFY_CITY）
     */
    private String city;

    /**
     * 活动列表 (已分配 ID、序号、优先级、时间窗等)
     */
    private List<ActivityVar> activities;

    /**
     * 交通时间矩阵 (分钟)
     * travelTimeMatrix[i][j][mode] = 从活动 i 到活动 j 的在途时间
     */
    private TravelMatrix travelMatrix;

    /**
     * 行程时间范围 (分钟粒度，相对于行程开始)
     */
    private TimeWindow tripTimeWindow;

    /**
     * 求解器配置
     */
    private SolverConfig solverConfig;

    /**
     * 增量重规划时的固定活动 (activityId -> {fixedStart, fixedEnd})
     */
    private Map<String, FixedTime> fixedActivities;

    @Data
    @Builder
    public static class TimeWindow {
        private int startMin;      // 0
        private int endMin;        // 总分钟数
        private int horizon;       // 同 endMin
    }

    @Data
    @Builder
    public static class SolverConfig {
        private int maxTimeSeconds = 30;
        private int numWorkers = 4;
        private boolean logSearchProgress = true;
        private double relativeGapLimit = 0.01;
        private boolean useHeuristic = false; // >15 POI 时自动启用
    }

    @Data
    @Builder
    public static class FixedTime {
        private int startMin;
        private int endMin;
    }
}