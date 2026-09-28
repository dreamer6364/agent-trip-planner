-- ==========================================================
-- Trip Planner schema migration: 2026-09-26 (v1.14.4)
-- Live trip_planner schema drifted from init-sql/01_schema.sql:
--   1) user_preferences was created by an older DDL and lacks
--      the columns written by UserPreferenceService/register
--      => POST /api/auth/register failed with 500
--   2) planning_tasks table was never created in this DB
--      => plan-service task progress endpoints would 500
-- Run ONCE against trip_planner (MySQL 8.0).
-- ==========================================================

-- ---- 1. missing table: planning_tasks
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

-- ---- 2. user_preferences: columns missing in live schema
ALTER TABLE `user_preferences` ADD COLUMN `intensity` VARCHAR(20) NOT NULL DEFAULT 'standard';
ALTER TABLE `user_preferences` ADD COLUMN `dietary_tags` JSON DEFAULT ('[]');
ALTER TABLE `user_preferences` ADD COLUMN `accessibility` BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE `user_preferences` ADD COLUMN `home_location` POINT NULL SRID 4326;
ALTER TABLE `user_preferences` ADD COLUMN `work_location` POINT NULL SRID 4326;
ALTER TABLE `user_preferences` ADD COLUMN `preferred_transport_modes` JSON DEFAULT ('["transit", "walk"]');
ALTER TABLE `user_preferences` ADD COLUMN `version` BIGINT DEFAULT NULL;
