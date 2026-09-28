package com.tripplanner.worker.solver;

import com.tripplanner.worker.dto.SolverResult;
import com.tripplanner.worker.solver.model.PlanningProblem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CP-SAT 精确求解器 (占位)
 * 注意: OR-Tools CP-SAT 暂未集成，此为占位实现
 * 实际求解由 HeuristicSolver 完成
 */
@Slf4j
@Component
public class CpSatSolver {

    public SolverResult solve(PlanningProblem problem) {
        log.warn("CP-SAT 求解器暂不可用，降级到启发式求解器");
        throw new UnsupportedOperationException("OR-Tools CP-SAT 暂未集成，请使用 HeuristicSolver");
    }
}
