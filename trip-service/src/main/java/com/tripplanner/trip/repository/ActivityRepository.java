package com.tripplanner.trip.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.trip.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 活动项数据访问层
 */
@Mapper
public interface ActivityRepository extends BaseMapper<Activity> {

    /**
     * 查询版本的所有活动 (按顺序)
     */
    @Select("SELECT * FROM activities WHERE version_id = #{versionId} ORDER BY seq")
    List<Activity> findByVersionId(@Param("versionId") String versionId);

    /**
     * 查询版本的必去地点
     */
    @Select("SELECT * FROM activities WHERE version_id = #{versionId} AND priority = 'must' AND status = 'scheduled' ORDER BY scheduled_start")
    List<Activity> findMustVisitByVersionId(@Param("versionId") String versionId);

    /**
     * 批量插入活动
     */
    int insertBatch(@Param("list") List<Activity> list);

    /**
     * 删除版本的所有活动
     */
    int deleteByVersionId(@Param("versionId") String versionId);
}