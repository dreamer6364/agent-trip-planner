package com.tripplanner.trip.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.trip.entity.TripVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 行程版本数据访问层
 */
@Mapper
public interface TripVersionRepository extends BaseMapper<TripVersion> {

    /**
     * 查询行程的版本历史
     */
    @Select("""
        SELECT * FROM trip_versions 
        WHERE trip_id = #{tripId}
        ORDER BY version_num DESC
        LIMIT #{offset}, #{size}
        """)
    List<TripVersion> findByTripId(@Param("tripId") String tripId, @Param("offset") int offset, @Param("size") int size);

    /**
     * 统计版本数
     */
    @Select("SELECT COUNT(1) FROM trip_versions WHERE trip_id = #{tripId}")
    long countByTripId(@Param("tripId") String tripId);

    /**
     * 获取最新版本
     */
    @Select("SELECT * FROM trip_versions WHERE trip_id = #{tripId} ORDER BY version_num DESC LIMIT 1")
    TripVersion findLatestByTripId(@Param("tripId") String tripId);

    /**
     * 获取指定版本
     */
    @Select("SELECT * FROM trip_versions WHERE trip_id = #{tripId} AND version_num = #{versionNum}")
    TripVersion findByTripIdAndVersionNum(@Param("tripId") String tripId, @Param("versionNum") int versionNum);

    /**
     * 获取当前生效版本
     */
    @Select("SELECT * FROM trip_versions WHERE id = #{versionId}")
    TripVersion findById(@Param("versionId") String versionId);

    /**
     * 查询版本树 (父版本及其子版本)
     */
    @Select("SELECT * FROM trip_versions WHERE trip_id = #{tripId} AND (id = #{versionId} OR parent_version_id = #{versionId}) ORDER BY version_num")
    List<TripVersion> findVersionTree(@Param("tripId") String tripId, @Param("versionId") String versionId);
}