-- ==========================================
-- Trip Planner 数据库初始化脚本
-- 执行顺序：01_schema.sql -> 02_indexes.sql -> 03_data.sql
-- ==========================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS `trip_planner` 
    DEFAULT CHARACTER SET utf8mb4 
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `trip_planner`;

-- ==========================================
-- 1. 用户表
-- ==========================================
CREATE TABLE IF NOT EXISTS `users` (
    `id` CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    `email` VARCHAR(255) NOT NULL UNIQUE,
    `password_hash` VARCHAR(255) NOT NULL,
    `name` VARCHAR(100),
    `avatar_url` TEXT,
    `preferences` JSON DEFAULT ('{}'),
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active, disabled, deleted',
    `last_login_at` TIMESTAMP(6) NULL,
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX `idx_email` (`email`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

-- ==========================================
-- 2. 行程主表
-- ==========================================
CREATE TABLE IF NOT EXISTS `trips` (
    `id` CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    `user_id` CHAR(36) NULL COMMENT 'NULL 表示游客行程',
    `title` VARCHAR(200) NOT NULL,
    `raw_input` TEXT NOT NULL COMMENT '用户原始自由文本输入',
    `parsed_input` JSON NOT NULL COMMENT '结构化解析结果',
    `time_start` DATETIME(6) NOT NULL COMMENT '行程开始时间',
    `time_end` DATETIME(6) NOT NULL COMMENT '行程结束时间',
    `transport_mode` VARCHAR(20) NOT NULL DEFAULT 'mixed' COMMENT 'walk, transit, drive, bike, mixed',
    `pace` VARCHAR(20) NOT NULL DEFAULT 'moderate' COMMENT '活动频率：compact(紧凑 8~10小时/天), moderate(适中 6~8小时/天), relaxed(宽松 3~5小时/天)',
    `preferences` JSON NOT NULL DEFAULT ('{}') COMMENT '用户偏好配置快照',
    `status` VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft, planning, completed, failed, archived',
    `current_version_id` CHAR(36) NULL COMMENT '当前生效版本',
    `is_public` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开分享',
    `share_token` VARCHAR(64) UNIQUE NULL COMMENT '分享链接 token',
    `view_count` INT NOT NULL DEFAULT 0 COMMENT '查看次数',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX `idx_user_created` (`user_id`, `created_at` DESC),
    INDEX `idx_status` (`status`),
    INDEX `idx_share_token` (`share_token`),
    INDEX `idx_time_range` (`time_start`, `time_end`),
    CONSTRAINT `fk_trips_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行程主表';

-- ==========================================
-- 3. 行程版本表
-- ==========================================
CREATE TABLE IF NOT EXISTS `trip_versions` (
    `id` CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    `trip_id` CHAR(36) NOT NULL,
    `version_num` INT NOT NULL COMMENT '版本号，从 1 开始递增',
    `parent_version_id` CHAR(36) NULL COMMENT '父版本 ID，用于版本树追溯',
    `activities` JSON NOT NULL DEFAULT ('[]') COMMENT '活动列表完整快照',
    `routes` JSON NOT NULL DEFAULT ('[]') COMMENT '路线片段列表',
    `conflicts` JSON NOT NULL DEFAULT ('[]') COMMENT '冲突/降级信息',
    `stats` JSON NOT NULL DEFAULT ('{}') COMMENT '统计信息',
    `feedback` TEXT NULL COMMENT '触发本版本的用户反馈',
    `solver_meta` JSON DEFAULT ('{}') COMMENT '求解器元数据：耗时、迭代次数、目标值等',
    `status` VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft, planning, completed, failed',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY `uk_trip_version` (`trip_id`, `version_num`),
    INDEX `idx_trip_version` (`trip_id`, `version_num` DESC),
    INDEX `idx_parent_version` (`parent_version_id`),
    CONSTRAINT `fk_version_trip` FOREIGN KEY (`trip_id`) REFERENCES `trips`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_version_parent` FOREIGN KEY (`parent_version_id`) REFERENCES `trip_versions`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行程版本表';

-- ==========================================
-- 4. 活动项表 (扁平化存储，便于查询)
-- ==========================================
CREATE TABLE IF NOT EXISTS `activities` (
    `id` CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    `version_id` CHAR(36) NOT NULL,
    `seq` INT NOT NULL COMMENT '在行程中的顺序',
    `poi_id` CHAR(36) NULL COMMENT '关联 POI 表',
    `poi_name` VARCHAR(200) NOT NULL COMMENT '地点名称（冗余，避免连表）',
    `poi_category` VARCHAR(50) NULL COMMENT '地点分类',
    `poi_location` POINT SRID 4326 COMMENT '地点坐标',
    `poi_address` TEXT NULL COMMENT '地点地址',
    `activity_type` VARCHAR(20) NOT NULL COMMENT 'visit, meal, stay, transit, buffer',
    `priority` VARCHAR(20) NOT NULL COMMENT 'must, recommended, optional, excluded',
    `time_window_start` DATETIME(6) NULL COMMENT '最早可开始时间（硬约束）',
    `time_window_end` DATETIME(6) NULL COMMENT '最晚可结束时间（硬约束）',
    `scheduled_start` DATETIME(6) NULL COMMENT '实际安排开始时间（求解结果）',
    `scheduled_end` DATETIME(6) NULL COMMENT '实际安排结束时间（求解结果）',
    `duration_min` INT NOT NULL COMMENT '偏好时长（分钟）',
    `min_duration` INT NULL COMMENT '最小时长（分钟）',
    `max_duration` INT NULL COMMENT '最长时长（分钟）',
    `status` VARCHAR(20) NOT NULL DEFAULT 'scheduled' COMMENT 'scheduled, removed, conflict',
    `transport_mode` VARCHAR(20) NULL COMMENT '到达该地点的交通方式',
    `travel_duration_min` INT NULL COMMENT '从上一地点的在途时长（分钟）',
    `notes` TEXT NULL,
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_version_seq` (`version_id`, `seq`),
    INDEX `idx_scheduled_time` (`scheduled_start`, `scheduled_end`),
    INDEX `idx_priority` (`priority`),
    INDEX `idx_poi_id` (`poi_id`),
    SPATIAL INDEX `idx_location` (`poi_location`),
    CONSTRAINT `fk_act_version` FOREIGN KEY (`version_id`) REFERENCES `trip_versions`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动项表';

-- ==========================================
-- 5. POI 缓存表
-- ==========================================
CREATE TABLE IF NOT EXISTS `pois` (
    `id` CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    `external_id` VARCHAR(100) NULL COMMENT '高德/百度/OSM 原始 ID',
    `source` VARCHAR(20) NOT NULL COMMENT 'amap, baidu, osm, manual',
    `name` VARCHAR(200) NOT NULL,
    `aliases` JSON DEFAULT ('[]') COMMENT '别名列表，用于模糊匹配',
    `category` VARCHAR(50) NULL COMMENT '分类：scenic, restaurant, hotel, shopping, transport 等',
    `location` POINT NOT NULL SRID 4326 COMMENT '坐标',
    `address` TEXT NULL,
    `phone` VARCHAR(50) NULL,
    `opening_hours` JSON NULL COMMENT '营业时间：{"mon": "09:00-18:00", ...}',
    `rating` DECIMAL(2,1) NULL COMMENT '评分',
    `tags` JSON DEFAULT ('[]') COMMENT '标签：wifi, parking, accessible 等',
    `metadata` JSON DEFAULT ('{}') COMMENT '扩展字段：门票价格、推荐时长等',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    SPATIAL INDEX `idx_location` (`location`),
    FULLTEXT INDEX `idx_name` (`name`),
    UNIQUE KEY `uk_external_source` (`external_id`, `source`),
    INDEX `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='POI 缓存表';

-- ==========================================
-- 6. 规划任务表
-- ==========================================
CREATE TABLE IF NOT EXISTS `planning_tasks` (
    `id` CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    `trip_id` CHAR(36) NOT NULL,
    `version_id` CHAR(36) NOT NULL,
    `task_type` VARCHAR(20) NOT NULL DEFAULT 'plan' COMMENT 'plan, replan',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending, running, completed, failed',
    `progress` INT NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    `stage` VARCHAR(50) NULL COMMENT 'parse_input, geocode, build_model, solve, route, persist',
    `input_snapshot` JSON NOT NULL COMMENT '任务输入快照',
    `result_version_id` CHAR(36) NULL COMMENT '完成后生成的版本 ID',
    `error_message` TEXT NULL,
    `solver_stats` JSON NULL COMMENT '求解器统计：objective_value, iterations, solve_time_ms',
    `started_at` TIMESTAMP(6) NULL,
    `completed_at` TIMESTAMP(6) NULL,
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_trip_status` (`trip_id`, `status`),
    INDEX `idx_version` (`version_id`),
    INDEX `idx_status_created` (`status`, `created_at`),
    CONSTRAINT `fk_task_trip` FOREIGN KEY (`trip_id`) REFERENCES `trips`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_task_version` FOREIGN KEY (`version_id`) REFERENCES `trip_versions`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_task_result_version` FOREIGN KEY (`result_version_id`) REFERENCES `trip_versions`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规划任务表';

-- ==========================================
-- 7. 用户偏好表
-- ==========================================
CREATE TABLE IF NOT EXISTS `user_preferences` (
    `user_id` CHAR(36) PRIMARY KEY,
    `visit_duration_defaults` JSON DEFAULT ('{}') COMMENT '按分类的默认游玩时长：{"scenic": 120, "museum": 180}',
    `meal_durations` JSON DEFAULT ('{"breakfast": 60, "lunch": 90, "dinner": 120}') COMMENT '各餐时长（分钟）',
    `active_window` JSON NOT NULL DEFAULT ('{"start": "08:00", "end": "23:00"}') COMMENT '可安排活动时间窗',
    `blocked_periods` JSON DEFAULT ('[]') COMMENT '不可安排时段：[{"start": "13:00", "end": "14:00"}]',
    `intensity` VARCHAR(20) NOT NULL DEFAULT 'standard' COMMENT 'relaxed, standard, packed',
    `dietary_tags` JSON DEFAULT ('[]') COMMENT '饮食偏好标签',
    `accessibility` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '无障碍需求',
    `home_location` POINT NULL SRID 4326 COMMENT '家庭位置',
    `work_location` POINT NULL SRID 4326 COMMENT '工作位置',
    `preferred_transport_modes` JSON DEFAULT ('["transit", "walk"]') COMMENT '偏好交通方式顺序',
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT `fk_prefs_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户偏好表';

-- ==========================================
-- 8. 审计日志表 (可选，生产环境建议单独存储)
-- ==========================================
CREATE TABLE IF NOT EXISTS `audit_logs` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` CHAR(36) NULL,
    `action` VARCHAR(50) NOT NULL COMMENT 'create_trip, update_trip, delete_trip, plan, replan, export, share',
    `resource_type` VARCHAR(30) NOT NULL COMMENT 'trip, version, user',
    `resource_id` CHAR(36) NULL,
    `request_ip` VARCHAR(45) NULL,
    `user_agent` TEXT NULL,
    `request_params` JSON NULL,
    `result` VARCHAR(20) NOT NULL DEFAULT 'success' COMMENT 'success, fail',
    `error_message` TEXT NULL,
    `duration_ms` INT NULL,
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX `idx_user_created` (`user_id`, `created_at` DESC),
    INDEX `idx_resource` (`resource_type`, `resource_id`),
    INDEX `idx_action_created` (`action`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计日志表';

-- ==========================================
-- 9. 系统配置表
-- ==========================================
CREATE TABLE IF NOT EXISTS `system_configs` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `config_key` VARCHAR(100) NOT NULL UNIQUE,
    `config_value` JSON NOT NULL,
    `description` VARCHAR(255) NULL,
    `is_public` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否可前端读取',
    `created_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置表';

-- ==========================================
-- 初始化系统配置
-- ==========================================
INSERT IGNORE INTO `system_configs` (`config_key`, `config_value`, `description`, `is_public`) VALUES
('planning.solver.timeout_seconds', '30', '求解器最大运行时间（秒）', false),
('planning.solver.workers', '4', '求解器并行工作线程数', false),
('planning.heuristic.max_iterations', '1000', '启发式算法最大迭代次数', false),
('planning.max_poi_count_exact', '15', '超过该数量使用启发式算法', false),
('geocode.cache_ttl_days', '30', '地理编码缓存过期天数', false),
('route.cache_ttl_days', '7', '路线规划缓存过期天数', false),
('rate_limit.default.qps', '100', '默认接口限流 QPS', false),
('rate_limit.auth.qps', '10', '认证接口限流 QPS', false),
('frontend.max_trips_per_guest', '5', '游客最大保存行程数', true),
('frontend.guest_data_ttl_days', '7', '游客数据过期天数', true);

-- ==========================================
-- 完成
-- ==========================================
SELECT 'Database schema initialized successfully!' AS message;