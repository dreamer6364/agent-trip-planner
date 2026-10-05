package com.tripplanner.trip.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.trip.entity.Trip;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 行程数据访问层
 */
@Mapper
public interface TripRepository extends BaseMapper<Trip> {

    /**
     * 分页查询用户行程
     */
    @Select("""
        SELECT * FROM trips 
        WHERE user_id = #{userId} AND status NOT IN ('deleted', 'archived')
        ORDER BY created_at DESC
        LIMIT #{offset}, #{size}
        """)
    List<Trip> findByUserId(@Param("userId") String userId, @Param("offset") int offset, @Param("size") int size);

    /**
     * 统计用户行程数
     */
    @Select("SELECT COUNT(1) FROM trips WHERE user_id = #{userId} AND status NOT IN ('deleted', 'archived')")
    long countByUserId(@Param("userId") String userId);

    /**
     * 查询公开分享行程
     */
    @Select("""
        SELECT * FROM trips 
        WHERE is_public = true AND status = 'completed'
        ORDER BY created_at DESC
        LIMIT #{offset}, #{size}
        """)
    List<Trip> findPublicTrips(@Param("offset") int offset, @Param("size") int size);

    /**
     * 统计公开行程数
     */
    @Select("SELECT COUNT(1) FROM trips WHERE is_public = true AND status = 'completed'")
    long countPublicTrips();

    /**
     * 搜索公开行程（标题/描述/当前版本行程内地点名模糊匹配）
     */
    @Select("""
        SELECT * FROM trips 
        WHERE is_public = true AND status = 'completed'
        AND (
            title LIKE CONCAT('%', #{keyword}, '%')
            OR raw_input LIKE CONCAT('%', #{keyword}, '%')
            OR EXISTS (
                SELECT 1 FROM trip_versions v
                WHERE v.id = trips.current_version_id
                AND JSON_SEARCH(v.activities, 'one', CONCAT('%', #{keyword}, '%')) IS NOT NULL
            )
        )
        ORDER BY created_at DESC
        LIMIT #{offset}, #{size}
        """)
    List<Trip> searchPublicTrips(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    /**
     * 统计搜索公开行程结果数
     */
    @Select("""
        SELECT COUNT(1) FROM trips 
        WHERE is_public = true AND status = 'completed'
        AND (
            title LIKE CONCAT('%', #{keyword}, '%')
            OR raw_input LIKE CONCAT('%', #{keyword}, '%')
            OR EXISTS (
                SELECT 1 FROM trip_versions v
                WHERE v.id = trips.current_version_id
                AND JSON_SEARCH(v.activities, 'one', CONCAT('%', #{keyword}, '%')) IS NOT NULL
            )
        )
        """)
    long countPublicTripsByKeyword(@Param("keyword") String keyword);

    /**
     * 根据分享 token 查询
     */
    @Select("SELECT * FROM trips WHERE share_token = #{token} AND is_public = true")
    Trip findByShareToken(@Param("token") String token);

    /**
     * 搜索行程 (标题/原始输入模糊匹配)
     */
    @Select("""
        SELECT * FROM trips 
        WHERE user_id = #{userId} AND status NOT IN ('deleted', 'archived')
        AND (title LIKE CONCAT('%', #{keyword}, '%') OR raw_input LIKE CONCAT('%', #{keyword}, '%'))
        ORDER BY created_at DESC
        LIMIT #{offset}, #{size}
        """)
    List<Trip> searchByKeyword(@Param("userId") String userId, @Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    /**
     * 查询用户名下标题以指定城市开头的行程（自动生成默认名称时用于同城序号排号，1.36.0）
     */
    @Select("""
        SELECT id, title FROM trips
        WHERE user_id = #{userId} AND status NOT IN ('deleted', 'archived')
        AND title LIKE CONCAT(#{city}, '%')
        """)
    List<Trip> findTitlesByUserCity(@Param("userId") String userId, @Param("city") String city);

    /**
     * 统计搜索结果
     */
    @Select("SELECT COUNT(1) FROM trips WHERE user_id = #{userId} AND status NOT IN ('deleted', 'archived') AND (title LIKE CONCAT('%', #{keyword}, '%') OR raw_input LIKE CONCAT('%', #{keyword}, '%'))")
    long countSearchByKeyword(@Param("userId") String userId, @Param("keyword") String keyword);
}