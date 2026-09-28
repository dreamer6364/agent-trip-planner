package com.tripplanner.worker.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 求解器结果
 */
@Data
@Builder
public class SolverResult {

    private boolean success;
    private List<com.tripplanner.worker.solver.model.ActivityVar> scheduled;
    private List<com.tripplanner.worker.solver.model.ActivityVar> unscheduled;
    private Map<String, Object> stats;
    private long solveTimeMs;
    private String errorMessage;

    public static SolverResult success(
            List<com.tripplanner.worker.solver.model.ActivityVar> scheduled,
            List<com.tripplanner.worker.solver.model.ActivityVar> unscheduled,
            Map<String, Object> stats,
            long solveTimeMs
    ) {
        return SolverResult.builder()
                .success(true)
                .scheduled(scheduled)
                .unscheduled(unscheduled)
                .stats(stats)
                .solveTimeMs(solveTimeMs)
                .build();
    }

    public static SolverResult failure(String errorMessage) {
        return SolverResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}