package com.tripplanner.plan.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.plan.entity.PlanTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 规划任务数据访问层
 */
@Mapper
public interface PlanTaskRepository extends BaseMapper<PlanTask> {

    /**
     * 查询待处理任务 (Worker 消费)
     */
    @Select("""
        SELECT * FROM planning_tasks 
        WHERE status IN ('pending', 'running') AND task_type = #{taskType}
        ORDER BY created_at ASC
        LIMIT #{limit}
        """)
    List<PlanTask> findPendingTasks(@Param("taskType") String taskType, @Param("limit") int limit);

    /**
     * 统计待处理任务数
     */
    @Select("SELECT COUNT(1) FROM planning_tasks WHERE status IN ('pending', 'running')")
    long countPendingTasks();

    /**
     * 查询失败且可重试的任务
     */
    @Select("""
        SELECT * FROM planning_tasks 
        WHERE status = 'failed' AND retry_count < 3
        ORDER BY created_at ASC
        LIMIT #{limit}
        """)
    List<PlanTask> findRetryableTasks(@Param("limit") int limit);

    /**
     * 按行程查询任务
     */
    @Select("SELECT * FROM planning_tasks WHERE trip_id = #{tripId} ORDER BY created_at DESC")
    List<PlanTask> findByTripId(@Param("tripId") String tripId);

    /**
     * 按版本查询任务
     */
    @Select("SELECT * FROM planning_tasks WHERE version_id = #{versionId} ORDER BY created_at DESC")
    List<PlanTask> findByVersionId(@Param("versionId") String versionId);
}