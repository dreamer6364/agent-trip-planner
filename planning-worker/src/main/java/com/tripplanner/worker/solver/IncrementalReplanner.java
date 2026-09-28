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
 * 增量重规划器
 * 仅重新优化受影响的部分，保持未变部分固定
 * 注意: 目前仅使用启发式求解器
 */
@Slf4j
@Component
public class IncrementalReplanner {

    private final HeuristicSolver heuristicSolver;
    private final com.tripplanner.worker.service.GeocodeClient geocodeClient;

    public IncrementalReplanner(HeuristicSolver heuristicSolver,
                                com.tripplanner.worker.service.GeocodeClient geocodeClient) {
        this.heuristicSolver = heuristicSolver;
        this.geocodeClient = geocodeClient;
    }

    /**
     * 增量重规划
     */
    public SolverResult replan(PlanningProblem problem, List<Change> changes) {
        log.info("开始增量重规划: tripId={}, changes={}", problem.getTripId(), changes.size());

        // 1. 识别受影响的活动
        Set<String> affectedIds = computeAffectedActivities(problem, changes);
        log.debug("受影响活动: {}", affectedIds);

        if (affectedIds.isEmpty()) {
            log.info("无受影响活动，直接返回原解");
            // 按原状态切分（原实现按 isRemovable 过滤会误删可移除活动）
            return SolverResult.success(
                    problem.getActivities().stream()
                            .filter(a -> a.getEarliestMin() >= 0)
                            .collect(Collectors.toList()),
                    problem.getActivities().stream()
                            .filter(a -> a.getEarliestMin() < 0)
                            .collect(Collectors.toList()),
                    Map.of("incremental", true, "affectedCount", 0),
                    0
            );
        }

        // 2. 分离固定和可变活动（REMOVE 的活动直接剔除，不进子问题 → 合并时自然被丢弃）
        Set<String> removedIds = changes.stream()
                .filter(c -> c.type() == ChangeType.REMOVE && c.activityId() != null)
                .map(Change::activityId)
                .collect(Collectors.toSet());

        List<ActivityVar> fixedActivities = problem.getActivities().stream()
                .filter(a -> !affectedIds.contains(a.getId()))
                .collect(Collectors.toList());

        List<ActivityVar> variableActivities = problem.getActivities().stream()
                .filter(a -> affectedIds.contains(a.getId()) && !removedIds.contains(a.getId()))
                .map(a -> applyDurationChange(a, changes))
                .collect(Collectors.toList());

        // 3.1 时长增加时顺延下游活动（下游均已被 MODIFY_DURATION 标记为受影响进入变量集，
        // 否则延长会撞上下游时间窗导致活动被丢弃）
        for (Change c : changes) {
            if (c.type() != ChangeType.MODIFY_DURATION || c.activityId() == null
                    || !(c.newValue() instanceof Number newDur)) {
                continue;
            }
            ActivityVar targetOrig = problem.getActivities().stream()
                    .filter(a -> c.activityId().equals(a.getId()))
                    .findFirst().orElse(null);
            if (targetOrig == null) {
                continue;
            }
            int delta = Math.max(0, newDur.intValue() - targetOrig.getPreferredDurationMin());
            if (delta == 0) {
                continue;
            }
            int targetSeq = targetOrig.getSeq();
            for (int i = 0; i < variableActivities.size(); i++) {
                ActivityVar a = variableActivities.get(i);
                if (a.getId().equals(c.activityId()) || a.getSeq() <= targetSeq) {
                    continue;
                }
                variableActivities.set(i, a.toBuilder()
                        .earliestMin(a.getEarliestMin() + delta)
                        .latestMin(a.getLatestMin() + delta)
                        .build());
            }
        }

        // 3. 添加新增的活动
        for (Change change : changes) {
            if (change.type() == ChangeType.ADD && change.activity() != null) {
                variableActivities.add(change.activity());
            }
        }

        // 4. 构建子问题
        PlanningProblem subProblem = buildSubProblem(problem, fixedActivities, variableActivities);
        
        // 5. 求解子问题 (使用启发式求解器)
        SolverResult subResult = heuristicSolver.solve(subProblem);

        // 6. 合并结果
        return mergeSolutions(problem, fixedActivities, subResult);
    }

    /**
     * 应用 MODIFY_DURATION 变更（newValue = 新时长分钟数）
     * 时长增加时同步顺延 latestMin，否则新时长超出原时间窗会被判不可行而丢弃
     */
    private ActivityVar applyDurationChange(ActivityVar act, List<Change> changes) {
        for (Change c : changes) {
            if (c.type() == ChangeType.MODIFY_DURATION && c.activityId() != null
                    && c.activityId().equals(act.getId()) && c.newValue() instanceof Number newDur) {
                int dur = Math.max(15, newDur.intValue());
                int delta = dur - act.getPreferredDurationMin();
                return act.toBuilder()
                        .preferredDurationMin(dur)
                        .maxDurationMin(Math.max(dur, act.getMaxDurationMin()))
                        .latestMin(act.getLatestMin() + Math.max(0, delta))
                        .build();
            }
        }
        return act;
    }

    /**
     * 计算受影响的活动集合
     */
    private Set<String> computeAffectedActivities(PlanningProblem problem, List<Change> changes) {
        Set<String> affected = new HashSet<>();

        for (Change change : changes) {
            switch (change.type()) {
                case ADD -> {
                    if (change.activity() != null) {
                        affected.add(change.activity().getId());
                        // 新增活动会影响其前后的活动
                        addNeighbors(problem, change.activity().getSeq(), affected);
                    }
                }
                case REMOVE -> {
                    affected.add(change.activityId());
                    addNeighbors(problem, findActivitySeq(problem, change.activityId()), affected);
                }
                case MODIFY_DURATION, MODIFY_PRIORITY, MODIFY_TIME_WINDOW, MODIFY_TRANSPORT -> {
                    affected.add(change.activityId());
                    // 修改时长/优先级/时间窗/交通方式会影响后续所有活动
                    int seq = findActivitySeq(problem, change.activityId());
                    addDownstream(problem, seq, affected);
                }
                case SHIFT_TIME_WINDOW -> {
                    // 整体时间窗平移影响所有活动
                    affected.addAll(problem.getActivities().stream()
                            .map(ActivityVar::getId)
                            .collect(Collectors.toSet()));
                }
            }
        }

        return affected;
    }

    private void addNeighbors(PlanningProblem problem, int seq, Set<String> affected) {
        if (seq > 0) {
            problem.getActivities().stream()
                    .filter(a -> a.getSeq() == seq - 1)
                    .findFirst()
                    .ifPresent(a -> affected.add(a.getId()));
        }
        if (seq < problem.getActivities().size() - 1) {
            problem.getActivities().stream()
                    .filter(a -> a.getSeq() == seq + 1)
                    .findFirst()
                    .ifPresent(a -> affected.add(a.getId()));
        }
    }

    private void addDownstream(PlanningProblem problem, int seq, Set<String> affected) {
        problem.getActivities().stream()
                .filter(a -> a.getSeq() >= seq)
                .map(ActivityVar::getId)
                .forEach(affected::add);
    }

    private int findActivitySeq(PlanningProblem problem, String activityId) {
        return problem.getActivities().stream()
                .filter(a -> a.getId().equals(activityId))
                .findFirst()
                .map(ActivityVar::getSeq)
                .orElse(-1);
    }

    /**
     * 构建子问题
     */
    private PlanningProblem buildSubProblem(PlanningProblem original, 
                                             List<ActivityVar> fixed, 
                                             List<ActivityVar> variable) {
        // 固定活动的时间作为硬约束
        Map<String, PlanningProblem.FixedTime> fixedTimes = new HashMap<>();
        for (ActivityVar act : fixed) {
            fixedTimes.put(act.getId(), 
                    PlanningProblem.FixedTime.builder()
                            .startMin(act.getEarliestMin())
                            .endMin(act.getLatestMin())
                            .build());
        }

        // 合并所有活动 (固定+可变)，按 seq 排序后重编 0..n-1
        // TravelMatrix 以列表下标为索引，而基础版本 seq 可能为 1..n，必须重编保证 seq==下标
        List<ActivityVar> allActivities = new ArrayList<>();
        allActivities.addAll(fixed);
        allActivities.addAll(variable);
        allActivities.sort(Comparator.comparingInt(ActivityVar::getSeq));
        List<ActivityVar> reSeqed = new ArrayList<>(allActivities.size());
        for (int i = 0; i < allActivities.size(); i++) {
            reSeqed.add(allActivities.get(i).toBuilder().seq(i).build());
        }

        // 按重编后的列表构建矩阵（位置与 seq 一致；基础问题矩阵为 null 时也走此路径）
        TravelMatrix subMatrix = geocodeClient.getDistanceMatrix(reSeqed, original.getCity());

        return PlanningProblem.builder()
                .tripId(original.getTripId())
                .versionId(original.getVersionId())
                .taskId(original.getTaskId())
                .parsedInput(original.getParsedInput())
                .city(original.getCity())
                .activities(reSeqed)
                .travelMatrix(subMatrix)
                .tripTimeWindow(original.getTripTimeWindow())
                .solverConfig(original.getSolverConfig())
                .fixedActivities(fixedTimes)
                .build();
    }

    /**
     * 合并固定部分和子问题解
     */
    private SolverResult mergeSolutions(PlanningProblem original, 
                                         List<ActivityVar> fixed, 
                                         SolverResult subResult) {
        List<ActivityVar> merged = new ArrayList<>();
        Set<String> originalIds = new HashSet<>();
        original.getActivities().forEach(a -> originalIds.add(a.getId()));
        // 子问题中的固定活动已重编 seq，须按 id 匹配（对象身份不再成立）
        Set<String> fixedIds = new HashSet<>();
        fixed.forEach(a -> fixedIds.add(a.getId()));
        
        // 按原始顺序合并
        for (ActivityVar act : original.getActivities()) {
            if (fixedIds.contains(act.getId())) {
                merged.add(act); // 保持固定
            } else {
                // 从子问题结果中查找
                Optional<ActivityVar> solved = subResult.getScheduled().stream()
                        .filter(a -> a.getId().equals(act.getId()))
                        .findFirst();
                if (solved.isPresent()) {
                    merged.add(solved.get());
                } else {
                    // 子问题中被移除（含 REMOVE 变更）
                    merged.add(act.toBuilder()
                            .earliestMin(-1)
                            .latestMin(-1)
                            .build());
                }
            }
        }

        // 追加子解中原解没有的新增活动（ADD 变更，原实现会丢弃）
        for (ActivityVar act : subResult.getScheduled()) {
            if (!originalIds.contains(act.getId())) {
                merged.add(act);
            }
        }

        // 按开始时间重排并重编 seq
        List<ActivityVar> scheduled = merged.stream()
                .filter(a -> a.getEarliestMin() >= 0)
                .sorted(Comparator.comparingInt(ActivityVar::getEarliestMin)
                        .thenComparingInt(ActivityVar::getSeq))
                .collect(Collectors.toList());
        List<ActivityVar> reScheduled = new ArrayList<>();
        List<ActivityVar> unscheduled = new ArrayList<>();
        int seq = 0;
        for (ActivityVar act : scheduled) {
            reScheduled.add(act.toBuilder().seq(seq++).build());
        }
        for (ActivityVar act : merged) {
            if (act.getEarliestMin() < 0) {
                unscheduled.add(act);
            }
        }

        // 统计
        Map<String, Object> stats = new HashMap<>(subResult.getStats());
        stats.put("incremental", true);
        stats.put("fixedCount", fixed.size());
        stats.put("mergedScheduled", reScheduled.size());
        stats.put("mergedUnscheduled", unscheduled.size());

        return SolverResult.success(
                reScheduled,
                unscheduled,
                stats,
                subResult.getSolveTimeMs()
        );
    }

    /**
     * 变更类型枚举
     */
    public enum ChangeType {
        ADD, REMOVE, MODIFY_DURATION, MODIFY_PRIORITY, MODIFY_TIME_WINDOW, MODIFY_TRANSPORT, SHIFT_TIME_WINDOW
    }

    /**
     * 变更描述
     */
    public record Change(
            ChangeType type,
            String activityId,
            ActivityVar activity,
            Object oldValue,
            Object newValue
    ) {}
}