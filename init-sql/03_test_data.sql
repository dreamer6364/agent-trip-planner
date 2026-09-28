-- ==========================================
-- 开发/测试环境基础数据
-- 生产环境请勿执行
-- ==========================================

USE `trip_planner`;

-- ==========================================
-- 测试用户
-- ==========================================
-- 密码均为: Test@123 (BCrypt 加密)
INSERT IGNORE INTO `users` (`id`, `email`, `password_hash`, `name`, `preferences`, `status`) VALUES
('11111111-1111-1111-1111-111111111111', 'admin@tripplanner.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iYqiSJFqOQJ8qQ8qQ8qQ8qQ8qQ8q', 'Admin User', '{"theme": "dark"}', 'active'),
('22222222-2222-2222-2222-222222222222', 'test@tripplanner.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iYqiSJFqOQJ8qQ8qQ8qQ8qQ8qQ8q', 'Test User', '{"theme": "light"}', 'active'),
('33333333-3333-3333-3333-333333333333', 'demo@tripplanner.com', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iYqiSJFqOQJ8qQ8qQ8qQ8qQ8qQ8q', 'Demo User', '{}', 'active');

-- ==========================================
-- 测试用户偏好
-- ==========================================
INSERT IGNORE INTO `user_preferences` (`user_id`, `visit_duration_defaults`, `meal_durations`, `active_window`, `blocked_periods`, `intensity`, `dietary_tags`, `accessibility`) VALUES
('22222222-2222-2222-2222-222222222222', 
 '{"scenic": 120, "museum": 180, "park": 90, "temple": 60}', 
 '{"breakfast": 60, "lunch": 90, "dinner": 120}', 
 '{"start": "08:30", "end": "22:00"}', 
 '[{"start": "12:30", "end": "13:30"}]', 
 'standard', 
 '["vegetarian"]', 
 false),
('33333333-3333-3333-3333-333333333333', 
 '{"scenic": 90, "museum": 120, "park": 60}', 
 '{"breakfast": 45, "lunch": 60, "dinner": 90}', 
 '{"start": "09:00", "end": "21:00"}', 
 '[]', 
 'relaxed', 
 '[]', 
 true);

-- ==========================================
-- 常用 POI 测试数据 (杭州示例)
-- ==========================================
INSERT IGNORE INTO `pois` (`id`, `external_id`, `source`, `name`, `aliases`, `category`, `location`, `address`, `rating`, `tags`, `metadata`) VALUES
-- 西湖
('a1111111-1111-1111-1111-111111111111', 'POI_AMAP_WESTLAKE', 'amap', '西湖', '["西湖风景区", "西湖公园"]', 'scenic', ST_PointFromText('POINT(30.244752 120.153576)', 4326), '浙江省杭州市西湖区龙井路1号', 4.8, '["5A", "世界遗产", "免费"]', '{"recommended_duration": 180, "ticket_price": 0}'),
-- 灵隐寺
('a2222222-2222-2222-2222-222222222222', 'POI_AMAP_LINGYIN', 'amap', '灵隐寺', '["灵隐", "云林寺"]', 'temple', ST_PointFromText('POINT(30.238917 120.110917)', 4326), '浙江省杭州市西湖区灵隐路法云弄1号', 4.7, '["佛教", "祈福", "5A"]', '{"recommended_duration": 120, "ticket_price": 30}'),
-- 河坊街
('a3333333-3333-3333-3333-333333333333', 'POI_AMAP_HEFANG', 'amap', '河坊街', '["河坊街历史街区", "清河坊"]', 'shopping', ST_PointFromText('POINT(30.245517 120.168517)', 4326), '浙江省杭州市上城区河坊街', 4.3, '["小吃", "伴手礼", "步行街"]', '{"recommended_duration": 90}'),
-- 楼外楼
('a4444444-4444-4444-4444-444444444444', 'POI_AMAP_LOUWAI', 'amap', '楼外楼', '["楼外楼酒楼"]', 'restaurant', ST_PointFromText('POINT(30.248517 120.142517)', 4326), '浙江省杭州市西湖区孤山路30号', 4.5, '["杭帮菜", "西湖醋鱼", "老字号"]', '{"recommended_duration": 90, "avg_price": 150}'),
-- 杭州东站
('a5555555-5555-5555-5555-555555555555', 'POI_AMAP_HZEAST', 'amap', '杭州东站', '["杭州东站", "高铁站"]', 'transport', ST_PointFromText('POINT(30.292517 120.220517)', 4326), '浙江省杭州市上城区彭埠街道', 4.0, '["高铁", "地铁换乘", "交通枢纽"]', '{}'),
-- 西溪湿地
('a6666666-6666-6666-6666-666666666666', 'POI_AMAP_XIXI', 'amap', '西溪国家湿地公园', '["西溪湿地", "西溪"]', 'park', ST_PointFromText('POINT(30.275517 120.055517)', 4326), '浙江省杭州市西湖区西溪路', 4.6, '["5A", "湿地", "船游"]', '{"recommended_duration": 180, "ticket_price": 80}'),
-- 雷峰塔
('a7777777-7777-7777-7777-777777777777', 'POI_AMAP_LEIFENG', 'amap', '雷峰塔', '["雷峰塔景区"]', 'scenic', ST_PointFromText('POINT(30.235517 120.155517)', 4326), '浙江省杭州市西湖区南山路15号', 4.4, '["登高", "西湖十景", "白蛇传"]', '{"recommended_duration": 60, "ticket_price": 40}'),
-- 断桥
('a8888888-8888-8888-8888-888888888888', 'POI_AMAP_DUANQIAO', 'amap', '断桥', '["断桥残雪"]', 'scenic', ST_PointFromText('POINT(30.255517 120.145517)', 4326), '浙江省杭州市西湖区白堤', 4.6, '["西湖十景", "免费", "拍照打卡"]', '{"recommended_duration": 30}');

-- ==========================================
-- 测试行程数据
-- ==========================================
INSERT IGNORE INTO `trips` (`id`, `user_id`, `title`, `raw_input`, `parsed_input`, `time_start`, `time_end`, `transport_mode`, `preferences`, `status`, `current_version_id`) VALUES
('b1111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '杭州周末两日游', 
 '计划周末两天去杭州，想去西湖、灵隐寺、河坊街，早餐吃包子，午餐尝尝杭帮菜楼外楼，晚上逛逛河坊街',
 '{"places": [{"name": "西湖", "priority": "must", "type": "scenic"}, {"name": "灵隐寺", "priority": "recommended", "type": "temple"}, {"name": "河坊街", "priority": "optional", "type": "shopping"}, {"name": "楼外楼", "priority": "must", "type": "restaurant", "meal": "lunch"}], "meals": [{"type": "breakfast", "preference": "包子"}, {"type": "lunch", "preference": "杭帮菜"}, {"type": "dinner", "preference": "河坊街小吃"}]}',
 '2026-09-21 08:00:00', '2026-09-22 20:00:00', 'mixed',
 '{"visit_duration": {"scenic": 120, "temple": 90}, "mealDuration": {"breakfast": 60, "lunch": 90, "dinner": 90}, "activeWindow": {"start": "08:00", "end": "22:00"}, "blockedPeriods": [{"start": "13:00", "end": "14:00"}], "intensity": "standard"}',
 'completed', 'c1111111-1111-1111-1111-111111111111');

-- ==========================================
-- 测试行程版本
-- ==========================================
INSERT IGNORE INTO `trip_versions` (`id`, `trip_id`, `version_num`, `parent_version_id`, `activities`, `routes`, `conflicts`, `stats`, `feedback`, `solver_meta`, `status`) VALUES
('c1111111-1111-1111-1111-111111111111', 'b1111111-1111-1111-1111-111111111111', 1, NULL,
 '[
   {"id": "act1", "seq": 1, "poi_id": "a5555555-5555-5555-5555-555555555555", "poi_name": "杭州东站", "poi_location": {"type": "Point", "coordinates": [120.220517, 30.292517]}, "activity_type": "transit", "priority": "must", "scheduled_start": "2026-09-21T08:00:00", "scheduled_end": "2026-09-21T08:30:00", "duration_min": 30, "status": "scheduled", "transport_mode": "walk"},
   {"id": "act2", "seq": 2, "poi_id": "a1111111-1111-1111-1111-111111111111", "poi_name": "西湖", "poi_location": {"type": "Point", "coordinates": [120.153576, 30.244752]}, "activity_type": "visit", "priority": "must", "scheduled_start": "2026-09-21T08:30:00", "scheduled_end": "2026-09-21T11:30:00", "duration_min": 180, "status": "scheduled", "transport_mode": "transit", "travel_duration_min": 25},
   {"id": "act3", "seq": 3, "poi_id": "a4444444-4444-4444-4444-444444444444", "poi_name": "楼外楼", "poi_location": {"type": "Point", "coordinates": [120.142517, 30.248517]}, "activity_type": "meal", "priority": "must", "scheduled_start": "2026-09-21T11:30:00", "scheduled_end": "2026-09-21T13:00:00", "duration_min": 90, "status": "scheduled", "transport_mode": "walk", "travel_duration_min": 10},
   {"id": "act4", "seq": 4, "poi_id": "a2222222-2222-2222-2222-222222222222", "poi_name": "灵隐寺", "poi_location": {"type": "Point", "coordinates": [120.110917, 30.238917]}, "activity_type": "visit", "priority": "recommended", "scheduled_start": "2026-09-21T14:00:00", "scheduled_end": "2026-09-21T16:00:00", "duration_min": 120, "status": "scheduled", "transport_mode": "transit", "travel_duration_min": 30},
   {"id": "act5", "seq": 5, "poi_id": "a3333333-3333-3333-3333-333333333333", "poi_name": "河坊街", "poi_location": {"type": "Point", "coordinates": [120.168517, 30.245517]}, "activity_type": "visit", "priority": "optional", "scheduled_start": "2026-09-21T16:30:00", "scheduled_end": "2026-09-21T18:30:00", "duration_min": 120, "status": "scheduled", "transport_mode": "transit", "travel_duration_min": 20},
   {"id": "act6", "seq": 6, "poi_name": "晚餐-河坊街小吃", "poi_location": {"type": "Point", "coordinates": [120.168517, 30.245517]}, "activity_type": "meal", "priority": "must", "scheduled_start": "2026-09-21T18:30:00", "scheduled_end": "2026-09-21T20:00:00", "duration_min": 90, "status": "scheduled"}
 ]',
 '[
   {"from": "origin", "to": "act1", "mode": "walk", "distance": 500, "duration": 300, "polyline": "..."},
   {"from": "act1", "to": "act2", "mode": "transit", "distance": 8000, "duration": 1500, "polyline": "..."},
   {"from": "act2", "to": "act3", "mode": "walk", "distance": 1200, "duration": 600, "polyline": "..."},
   {"from": "act3", "to": "act4", "mode": "transit", "distance": 5000, "duration": 1800, "polyline": "..."},
   {"from": "act4", "to": "act5", "mode": "transit", "distance": 7000, "duration": 1200, "polyline": "..."},
   {"from": "act5", "to": "destination", "mode": "transit", "distance": 10000, "duration": 2000, "polyline": "..."}
 ]',
 '[]',
 '{"total_duration_min": 720, "transit_duration_min": 130, "visit_duration_min": 300, "meal_duration_min": 180, "buffer_duration_min": 110, "place_count": {"must": 3, "recommended": 1, "optional": 1}, "estimated_cost": 250}',
 NULL,
 '{"solve_time_ms": 1250, "iterations": 45, "objective_value": 850.5, "solver_status": "OPTIMAL"}',
 'completed');

-- ==========================================
-- 活动项扁平化数据
-- ==========================================
INSERT IGNORE INTO `activities` (`id`, `version_id`, `seq`, `poi_id`, `poi_name`, `poi_category`, `poi_location`, `poi_address`, `activity_type`, `priority`, `scheduled_start`, `scheduled_end`, `duration_min`, `min_duration`, `max_duration`, `status`, `transport_mode`, `travel_duration_min`) VALUES
('act1', 'c1111111-1111-1111-1111-111111111111', 1, 'a5555555-5555-5555-5555-555555555555', '杭州东站', 'transport', ST_PointFromText('POINT(30.292517 120.220517)', 4326), '浙江省杭州市上城区彭埠街道', 'transit', 'must', '2026-09-21 08:00:00', '2026-09-21 08:30:00', 30, 30, 30, 'scheduled', 'walk', 0),
('act2', 'c1111111-1111-1111-1111-111111111111', 2, 'a1111111-1111-1111-1111-111111111111', '西湖', 'scenic', ST_PointFromText('POINT(30.244752 120.153576)', 4326), '浙江省杭州市西湖区龙井路1号', 'visit', 'must', '2026-09-21 08:30:00', '2026-09-21 11:30:00', 180, 60, 240, 'scheduled', 'transit', 25),
('act3', 'c1111111-1111-1111-1111-111111111111', 3, 'a4444444-4444-4444-4444-444444444444', '楼外楼', 'restaurant', ST_PointFromText('POINT(30.248517 120.142517)', 4326), '浙江省杭州市西湖区孤山路30号', 'meal', 'must', '2026-09-21 11:30:00', '2026-09-21 13:00:00', 90, 45, 120, 'scheduled', 'walk', 10),
('act4', 'c1111111-1111-1111-1111-111111111111', 4, 'a2222222-2222-2222-2222-222222222222', '灵隐寺', 'temple', ST_PointFromText('POINT(30.238917 120.110917)', 4326), '浙江省杭州市西湖区灵隐路法云弄1号', 'visit', 'recommended', '2026-09-21 14:00:00', '2026-09-21 16:00:00', 120, 60, 180, 'scheduled', 'transit', 30),
('act5', 'c1111111-1111-1111-1111-111111111111', 5, 'a3333333-3333-3333-3333-333333333333', '河坊街', 'shopping', ST_PointFromText('POINT(30.245517 120.168517)', 4326), '浙江省杭州市上城区河坊街', 'visit', 'optional', '2026-09-21 16:30:00', '2026-09-21 18:30:00', 120, 60, 180, 'scheduled', 'transit', 20);

-- ==========================================
-- 规划任务记录
-- ==========================================
INSERT IGNORE INTO `planning_tasks` (`id`, `trip_id`, `version_id`, `task_type`, `status`, `progress`, `stage`, `input_snapshot`, `result_version_id`, `solver_stats`, `started_at`, `completed_at`) VALUES
('d1111111-1111-1111-1111-111111111111', 'b1111111-1111-1111-1111-111111111111', 'c1111111-1111-1111-1111-111111111111', 'plan', 'completed', 100, 'persist', '{"places": [{"name": "西湖", "priority": "must"}, {"name": "灵隐寺", "priority": "recommended"}, {"name": "河坊街", "priority": "optional"}], "timeRange": {"start": "2026-09-21T08:00:00", "end": "2026-09-22T20:00:00"}}', 'c1111111-1111-1111-1111-111111111111', '{"solve_time_ms": 1250, "iterations": 45, "objective_value": 850.5}', '2026-09-16 10:00:00', '2026-09-16 10:00:02');

-- ==========================================
-- 完成
-- ==========================================
SELECT 'Test data inserted successfully!' AS message;