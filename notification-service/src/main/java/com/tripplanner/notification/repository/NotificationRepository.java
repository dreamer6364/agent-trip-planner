package com.tripplanner.notification.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 通知数据访问层
 */
@Mapper
public interface NotificationRepository extends BaseMapper<Notification> {

    /**
     * 查询用户未读通知数
     */
    @Select("SELECT COUNT(1) FROM notifications WHERE user_id = #{userId} AND `read` = false")
    long countUnreadByUserId(@Param("userId") String userId);

    /**
     * 分页查询用户通知
     */
    @Select("""
        SELECT * FROM notifications 
        WHERE user_id = #{userId}
        ORDER BY created_at DESC
        LIMIT #{offset}, #{size}
        """)
    List<Notification> findByUserId(@Param("userId") String userId, @Param("offset") int offset, @Param("size") int size);

    /**
     * 查询用户未读通知
     */
    @Select("""
        SELECT * FROM notifications 
        WHERE user_id = #{userId} AND `read` = false
        ORDER BY created_at DESC
        LIMIT #{size}
        """)
    List<Notification> findUnreadByUserId(@Param("userId") String userId, @Param("size") int size);

    /**
     * 批量标记已读
     */
    @Update("""
        UPDATE notifications SET `read` = #{read} WHERE id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">
            #{id}
        </foreach>
        """)
    int updateReadByIds(@Param("ids") List<String> ids, @Param("read") boolean read);

    /**
     * 删除过期通知 (保留最近 N 条)
     */
    @Select("""
        DELETE FROM notifications 
        WHERE user_id = #{userId} 
        AND id NOT IN (
            SELECT id FROM (
                SELECT id FROM notifications WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{keepCount}
            ) t
        )
        """)
    int deleteOldNotifications(@Param("userId") String userId, @Param("keepCount") int keepCount);
}