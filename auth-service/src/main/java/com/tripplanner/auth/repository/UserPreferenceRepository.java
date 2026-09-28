package com.tripplanner.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.auth.entity.UserPreference;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户偏好数据访问层
 */
// @Mapper 注解移除，由 @MapperScan 统一扫描
public interface UserPreferenceRepository extends BaseMapper<UserPreference> {
    // 继承 BaseMapper 已提供基础 CRUD
    // userId 是主键，直接使用 selectById / insert / updateById
}