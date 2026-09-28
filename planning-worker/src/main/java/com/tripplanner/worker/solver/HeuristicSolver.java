package com.tripplanner.worker.solver;

import com.tripplanner.worker.dto.SolverResult;
import com.tripplanner.worker.solver.model.ActivityVar;
import com.tripplanner.worker.solver.model.PlanningProblem;
import com.tripplanner.worker.solver.model.TravelMatrix;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 启发式求解器
 * 适用于 >15 个活动的大规模问题
 * 算法：贪心构造 -> 局部搜索 (2-opt, relocate, insert) -> 模拟退火
 */
@Slf4j
@Component
public class HeuristicSolver {

    private static final int MAX_ITERATIONS = 1000;
    private static final double INITIAL_TEMPERATURE = 100.0;
    private static final double COOLING_RATE = 0.995;
    private static final int MIN_ITERATIONS_PER_TEMP = 10;
    private static final Random RANDOM = new Random();

    /**
     * 求解大规模规划问题
     */
    public SolverResult solve(PlanningProblem problem) {
        log.info("启动启发式求解: tripId={}, activities={}", 
                problem.getTripId(), problem.getActivities().size());

        long startTime = System.currentTimeMillis();
        
        // 1. 贪心构造初始解
        Solution solution = greedyConstruct(problem);
        log.debug("贪心构造完成: scheduled={}, objective={}", 
                solution.getScheduled().size(), solution.getObjective());

        // 2. 局部搜索优化
        solution = localSearch(solution, problem);
        log.debug("局部搜索完成: scheduled={}, objective={}", 
                solution.getScheduled().size(), solution.getObjective());

        // 3. 模拟退火跳出局部最优
        solution = simulatedAnnealing(solution, problem);
        log.debug("模拟退火完成: scheduled={}, objective={}", 
                solution.getScheduled().size(), solution.getObjective());

        long solveTimeMs = System.currentTimeMillis() - startTime;
        
        // 4. 转换为 SolverResult
        List<ActivityVar> scheduled = solution.getScheduled();
        List<ActivityVar> unscheduled = solution.getUnscheduled();

        Map<String, Object> stats = Map.of(
                "scheduledCount", scheduled.size(),
                "unscheduledCount", unscheduled.size(),
                "totalDurationMin", scheduled.stream().mapToInt(ActivityVar::getPreferredDurationMin).sum(),
                "solveTimeMs", solveTimeMs,
                "objectiveValue", solution.getObjective(),
                "algorithm", "HEURISTIC"
        );

        return SolverResult.success(scheduled, unscheduled, stats, solveTimeMs);
    }

    /**
     * 贪心构造初始解
     * 按优先级降序，依次插入最佳位置
     */
    private Solution greedyConstruct(PlanningProblem problem) {
        Solution solution = new Solution(problem);
        
        // 按优先级排序：must > recommended > optional
        List<ActivityVar> sortedActivities = problem.getActivities().stream()
                .sorted(Comparator
                        .comparingInt((ActivityVar a) -> a.isMustVisit() ? 0 : (a.getPriorityWeight() >= 50 ? 1 : 2))
                        .thenComparingInt(a -> a.getSeq())
                )
                .collect(Collectors.toList());

        for (ActivityVar act : sortedActivities) {
            if (act.isTransit() || act.isBuffer()) {
                // 交通/缓冲活动直接加入
                solution.addScheduled(act);
                continue;
            }
            
            // 尝试插入最佳位置
            boolean inserted = tryInsertBest(solution, act, problem.getTravelMatrix());
            if (!inserted) {
                solution.addUnscheduled(act);
            }
        }
        
        return solution;
    }

    /**
     * 尝试在最佳位置插入活动
     */
    private boolean tryInsertBest(Solution solution, ActivityVar act, TravelMatrix travelMatrix) {
        int bestPos = -1;
        int bestStart = -1;
        double bestDelta = Double.NEGATIVE_INFINITY;
        
        List<ActivityVar> current = solution.getScheduled();
        
        // 尝试所有可能的插入位置 (包括开头和结尾)
        for (int pos = 0; pos <= current.size(); pos++) {
            int earliest = act.getEarliestMin();
            // 最晚开始时刻 = 最晚结束 - 时长（与 end 上界分开判断，原实现混用导致精确贴合窗口永不可行）
            int latestStart = act.getLatestMin() - act.getPreferredDurationMin();

            int prevEnd = (pos > 0) ? current.get(pos - 1).getLatestMin() : act.getEarliestMin();
            int nextStart = (pos < current.size()) ? current.get(pos).getEarliestMin() : act.getLatestMin();

            int travelFromPrev = (pos > 0) ? travelMatrix.getTravelTime(
                    current.get(pos - 1).getSeq(), act.getSeq(), act.getTransportMode()) : 0;
            int travelToNext = (pos < current.size()) ? travelMatrix.getTravelTime(
                    act.getSeq(), current.get(pos).getSeq(),
                    current.get(pos).getTransportMode()) : 0;

            int feasibleStart = Math.max(earliest, prevEnd + travelFromPrev);
            // 结束上界：插入中间为下一活动开始减在途时间；末尾为本活动最晚结束
            int endLimit = (pos < current.size()) ? (nextStart - travelToNext) : act.getLatestMin();

            if (feasibleStart <= latestStart && feasibleStart + act.getPreferredDurationMin() <= endLimit) {
                // 可行，计算目标函数增量
                double delta = calculateInsertionDelta(solution, act, pos, feasibleStart, travelMatrix);
                if (delta > bestDelta) {
                    bestDelta = delta;
                    bestPos = pos;
                    bestStart = feasibleStart;
                }
            }
        }
        
        if (bestPos >= 0) {
            // 插入
            ActivityVar scheduled = act.toBuilder()
                    .earliestMin(bestStart)
                    .latestMin(bestStart + act.getPreferredDurationMin())
                    .build();
            solution.insertScheduled(bestPos, scheduled);
            return true;
        }
        return false;
    }

    /**
     * 局部搜索
     */
    private Solution localSearch(Solution solution, PlanningProblem problem) {
        TravelMatrix tm = problem.getTravelMatrix();
        boolean improved = true;
        int iter = 0;
        
        while (improved && iter < MAX_ITERATIONS) {
            improved = false;
            iter++;
            
            // 2-opt 交换
            if (tryTwoOpt(solution, tm)) {
                improved = true;
                continue;
            }
            
            // 重新定位单个活动
            if (tryRelocate(solution, tm)) {
                improved = true;
                continue;
            }
            
            // 尝试插入未安排的活动
            if (tryInsertUnscheduled(solution, problem)) {
                improved = true;
            }
        }
        
        log.debug("局部搜索迭代 {} 次", iter);
        return solution;
    }

    /**
     * 2-opt 交换
     */
    private boolean tryTwoOpt(Solution solution, TravelMatrix tm) {
        List<ActivityVar> seq = solution.getScheduled();
        if (seq.size() < 4) return false;
        
        for (int i = 0; i < seq.size() - 1; i++) {
            for (int j = i + 2; j < seq.size(); j++) {
                // 检查交换后是否可行
                if (isFeasibleAfterSwap(seq, i, j, tm)) {
                    // 执行交换
                    Collections.reverse(seq.subList(i + 1, j + 1));
                    // 重新计算时间
                    recomputeSchedule(seq, tm);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 重新定位单个活动
     */
    private boolean tryRelocate(Solution solution, TravelMatrix tm) {
        List<ActivityVar> seq = solution.getScheduled();
        
        for (int i = 0; i < seq.size(); i++) {
            ActivityVar act = seq.get(i);
            if (act.isTransit() || act.isBuffer()) continue;
            
            // 移除
            List<ActivityVar> tempSeq = new ArrayList<>(seq);
            tempSeq.remove(i);
            
            // 尝试插入其他位置
            for (int pos = 0; pos <= tempSeq.size(); pos++) {
                if (pos == i) continue;
                
                if (tryInsertAt(tempSeq, act, pos, tm)) {
                    // 成功，更新解
                    solution.setScheduled(tempSeq);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 尝试插入未安排的活动
     */
    private boolean tryInsertUnscheduled(Solution solution, PlanningProblem problem) {
        List<ActivityVar> unscheduled = solution.getUnscheduled();
        
        for (ActivityVar act : unscheduled) {
            if (tryInsertBest(solution, act, problem.getTravelMatrix())) {
                solution.removeUnscheduled(act);
                return true;
            }
        }
        return false;
    }

    /**
     * 模拟退火
     */
    private Solution simulatedAnnealing(Solution solution, PlanningProblem problem) {
        TravelMatrix tm = problem.getTravelMatrix();
        Solution current = solution.copy();
        Solution best = solution.copy();
        double temperature = INITIAL_TEMPERATURE;
        
        while (temperature > 0.1) {
            for (int i = 0; i < MIN_ITERATIONS_PER_TEMP; i++) {
                Solution neighbor = current.copy();
                
                // 随机邻域操作
                int op = RANDOM.nextInt(3);
                switch (op) {
                    case 0 -> tryTwoOpt(neighbor, tm);
                    case 1 -> tryRelocate(neighbor, tm);
                    case 2 -> tryInsertUnscheduled(neighbor, problem);
                }
                
                double currentObj = current.getObjective();
                double neighborObj = neighbor.getObjective();
                double delta = neighborObj - currentObj;
                
                if (delta > 0 || Math.random() < Math.exp(delta / temperature)) {
                    current = neighbor;
                    if (neighborObj > best.getObjective()) {
                        best = neighbor.copy();
                    }
                }
            }
            temperature *= COOLING_RATE;
        }
        
        return best;
    }

    // ===== 辅助方法 =====

    private boolean isFeasibleAfterSwap(List<ActivityVar> seq, int i, int j, TravelMatrix tm) {
        // 简化检查：交换后重新计算时间，检查是否都在时间窗内
        List<ActivityVar> testSeq = new ArrayList<>(seq);
        Collections.reverse(testSeq.subList(i + 1, j + 1));
        return recomputeAndCheck(testSeq, tm);
    }

    private boolean recomputeAndCheck(List<ActivityVar> seq, TravelMatrix tm) {
        int currentTime = seq.isEmpty() ? 0 : seq.get(0).getEarliestMin();
        
        for (int k = 0; k < seq.size(); k++) {
            ActivityVar act = seq.get(k);
            int travel = (k > 0) ? tm.getTravelTime(seq.get(k-1).getSeq(), act.getSeq(), act.getTransportMode()) : 0;
            int start = Math.max(currentTime + travel, act.getEarliestMin());
            int end = start + act.getPreferredDurationMin();
            
            if (end > act.getLatestMin()) return false;
            currentTime = end;
        }
        return true;
    }

    private void recomputeSchedule(List<ActivityVar> seq, TravelMatrix tm) {
        int currentTime = seq.isEmpty() ? 0 : seq.get(0).getEarliestMin();
        
        for (int k = 0; k < seq.size(); k++) {
            ActivityVar act = seq.get(k);
            int travel = (k > 0) ? tm.getTravelTime(seq.get(k-1).getSeq(), act.getSeq(), act.getTransportMode()) : 0;
            int start = Math.max(currentTime + travel, act.getEarliestMin());
            int end = start + act.getPreferredDurationMin();
            
            act.setEarliestMin(start);
            act.setLatestMin(end);
            currentTime = end;
        }
    }

    private boolean tryInsertAt(List<ActivityVar> seq, ActivityVar act, int pos, TravelMatrix tm) {
        int prevEnd = (pos > 0) ? seq.get(pos - 1).getLatestMin() : act.getEarliestMin();
        int nextStart = (pos < seq.size()) ? seq.get(pos).getEarliestMin() : act.getLatestMin();
        
        int travelFromPrev = (pos > 0) ? tm.getTravelTime(seq.get(pos - 1).getSeq(), act.getSeq(), act.getTransportMode()) : 0;
        int travelToNext = (pos < seq.size()) ? tm.getTravelTime(act.getSeq(), seq.get(pos).getSeq(), seq.get(pos).getTransportMode()) : 0;
        
        int feasibleStart = Math.max(act.getEarliestMin(), prevEnd + travelFromPrev);
        int feasibleEnd = Math.min(act.getLatestMin(), nextStart - travelToNext);
        
        if (feasibleStart + act.getPreferredDurationMin() <= feasibleEnd) {
            ActivityVar scheduled = act.toBuilder()
                    .earliestMin(feasibleStart)
                    .latestMin(feasibleStart + act.getPreferredDurationMin())
                    .build();
            seq.add(pos, scheduled);
            return true;
        }
        return false;
    }

    private double calculateInsertionDelta(Solution solution, ActivityVar act, int pos, int start, TravelMatrix tm) {
        double delta = act.getPriorityWeight();
        
        List<ActivityVar> current = solution.getScheduled();
        
        // Travel time penalty
        if (pos > 0) {
            int travel = tm.getTravelTime(current.get(pos - 1).getSeq(), act.getSeq(), act.getTransportMode());
            delta -= 0.15 * travel;
        }
        if (pos < current.size()) {
            int travel = tm.getTravelTime(act.getSeq(), current.get(pos).getSeq(), current.get(pos).getTransportMode());
            delta -= 0.15 * travel;
        }
        
        // Meal time window bonus
        if ("meal".equals(act.getType())) {
            String name = act.getName().toLowerCase();
            if (name.contains("breakfast") && start >= 7 * 60 && start <= 9 * 60) delta += 200;
            else if (name.contains("lunch") && start >= 11 * 60 + 30 && start <= 13 * 60) delta += 200;
            else if (name.contains("dinner") && start >= 17 * 60 && start <= 19 * 60) delta += 200;
            else delta -= 300;
        }
        
        // Time window tightness bonus (closer to preferred = better)
        int duration = act.getPreferredDurationMin();
        if (duration > 0) {
            delta += Math.min(50, 200.0 / duration);
        }
        
        return delta;
    }

    /**
     * 内部解表示
     */
    private static class Solution {
        private final PlanningProblem problem;
        private final List<ActivityVar> scheduled = new ArrayList<>();
        private final List<ActivityVar> unscheduled = new ArrayList<>();

        public Solution(PlanningProblem problem) {
            this.problem = problem;
        }

        public void addScheduled(ActivityVar act) {
            scheduled.add(act);
        }

        public void insertScheduled(int pos, ActivityVar act) {
            scheduled.add(pos, act);
        }

        public void addUnscheduled(ActivityVar act) {
            unscheduled.add(act);
        }

        public void removeUnscheduled(ActivityVar act) {
            unscheduled.removeIf(a -> a.getId().equals(act.getId()));
        }

        public void setScheduled(List<ActivityVar> acts) {
            scheduled.clear();
            scheduled.addAll(acts);
        }

        public List<ActivityVar> getScheduled() {
            return new ArrayList<>(scheduled);
        }

        public List<ActivityVar> getUnscheduled() {
            return new ArrayList<>(unscheduled);
        }

        public double getObjective() {
            double obj = 0;
            for (ActivityVar act : scheduled) {
                obj += act.getPriorityWeight();
            }
            return obj;
        }

        public Solution copy() {
            Solution copy = new Solution(problem);
            for (ActivityVar a : this.scheduled) {
                copy.scheduled.add(a.toBuilder().build());
            }
            copy.unscheduled.addAll(this.unscheduled);
            return copy;
        }
    }
}