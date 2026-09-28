package com.tripplanner.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户数据访问层
 */
@Mapper
public interface UserRepository extends BaseMapper<User> {

    /**
     * 根据邮箱查找用户
     */
    @Select("SELECT * FROM users WHERE email = #{email} AND status != 'deleted'")
    User findByEmail(String email);

    /**
     * 检查邮箱是否已存在
     */
    @Select("SELECT COUNT(1) FROM users WHERE email = #{email} AND status != 'deleted'")
    boolean existsByEmail(String email);

    /**
     * 更新最后登录时间
     */
    @Update("UPDATE users SET last_login_at = #{lastLoginAt} WHERE id = #{id}")
    int updateLastLoginAt(String id, java.time.LocalDateTime lastLoginAt);
}