package com.tripplanner.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 求解器配置
 * 注意: OR-Tools CP-SAT 求解器暂时不可用，目前使用启发式求解器
 */
@Configuration
public class SolverConfig {

    @Value("${solver.max-time-seconds:30}")
    private int maxTimeSeconds;

    @Value("${solver.num-workers:4}")
    private int numWorkers;

    @Value("${solver.log-search-progress:true}")
    private boolean logSearchProgress;

    @Value("${solver.relative-gap-limit:0.01}")
    private double relativeGapLimit;

    @Value("${solver.heuristic-threshold:15}")
    private int heuristicThreshold;

    public int getMaxTimeSeconds() { return maxTimeSeconds; }
    public int getNumWorkers() { return numWorkers; }
    public boolean isLogSearchProgress() { return logSearchProgress; }
    public double getRelativeGapLimit() { return relativeGapLimit; }
    public int getHeuristicThreshold() { return heuristicThreshold; }
}
