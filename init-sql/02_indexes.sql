-- ==========================================
-- 附加索引优化脚本
-- 根据查询模式添加组合索引
-- ==========================================

USE `trip_planner`;

-- ==========================================
-- trips 表额外索引
-- ==========================================

-- 查询用户进行中的行程
CREATE INDEX `idx_user_status_created` ON `trips` (`user_id`, `status`, `created_at` DESC);

-- 公开分享行程查询
CREATE INDEX `idx_public_status_created` ON `trips` (`is_public`, `status`, `created_at` DESC);

-- ==========================================
-- trip_versions 表额外索引
-- ==========================================

-- 查询行程的最新完成版本
CREATE INDEX `idx_trip_status_version` ON `trip_versions` (`trip_id`, `status`, `version_num` DESC);

-- ==========================================
-- activities 表额外索引
-- ==========================================

-- 按版本查询必去地点
CREATE INDEX `idx_version_priority_status` ON `activities` (`version_id`, `priority`, `status`);

-- 时间范围查询（用于冲突检测）
CREATE INDEX `idx_version_time_window` ON `activities` (`version_id`, `time_window_start`, `time_window_end`);

-- ==========================================
-- planning_tasks 表额外索引
-- ==========================================

-- 查询待处理任务（Worker 消费）
CREATE INDEX `idx_status_type_created` ON `planning_tasks` (`status`, `task_type`, `created_at`);

-- ==========================================
-- audit_logs 表额外索引
-- ==========================================

-- 按资源查询操作历史
CREATE INDEX `idx_resource_action_created` ON `audit_logs` (`resource_type`, `resource_id`, `action`, `created_at` DESC);

-- ==========================================
-- POI 空间查询优化
-- ==========================================

-- 组合索引：分类 + 空间（MySQL 8.0 支持 SPATIAL 索引与普通索引组合查询）
-- 注意：MySQL 一个查询只能用一个空间索引，但可以通过 FORCE INDEX 指定

-- ==========================================
-- 分区表建议（数据量大时考虑）
-- ==========================================

-- audit_logs 可按月分区
-- ALTER TABLE `audit_logs` PARTITION BY RANGE (YEAR(`created_at`)*100 + MONTH(`created_at`)) (
--     PARTITION p202401 VALUES LESS THAN (202402),
--     PARTITION p202402 VALUES LESS THAN (202403),
--     ...
-- );

-- planning_tasks 可按周分区

-- ==========================================
-- 统计信息更新
-- ==========================================
ANALYZE TABLE `users`, `trips`, `trip_versions`, `activities`, `pois`, `planning_tasks`, `user_preferences`, `audit_logs`, `system_configs`;

SELECT 'Additional indexes created successfully!' AS message;