package com.tripplanner.trip.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.service.PermissionEvaluationService;
import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.response.Meta;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.client.AuthUserClient;
import com.tripplanner.trip.client.PlanServiceClient;
import com.tripplanner.trip.dto.request.CreateTripRequest;
import com.tripplanner.trip.dto.request.UpdateTripRequest;
import com.tripplanner.trip.dto.response.TripListResponse;
import com.tripplanner.trip.dto.response.TripResponse;
import com.tripplanner.trip.entity.Activity;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.event.TripEventPublisher;
import com.tripplanner.trip.repository.ActivityRepository;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 行程核心业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TripService {

    private final TripRepository tripRepository;
    private final TripVersionRepository versionRepository;
    private final ActivityRepository activityRepository;
    private final TripVersionService versionService;
    private final TripEventPublisher eventPublisher;
    private final PermissionEvaluationService permissionService;
    private final JsonUtils jsonUtils;
    private final ObjectMapper objectMapper;
    private final InlinePlanningService inlinePlanner;
    private final PlanServiceClient planServiceClient;
    private final AuthUserClient authUserClient;

    /**
     * 创建行程 (AI Agent 规划)
     */
    public TripResponse createTrip(String userId, CreateTripRequest request) {
        // 1. 创建 Trip
        Trip trip = new Trip();
        trip.setId(UUID.randomUUID().toString().replace("-", ""));
        trip.setUserId(userId);
        trip.setTitle(request.getTitle());
        trip.setRawInput(request.getRawInput());
        trip.setParsedInput("{}");
        trip.setTimeStart(request.getTimeStart());
        trip.setTimeEnd(request.getTimeEnd());
        trip.setTransportMode(request.getTransportMode());
        trip.setPace(normalizePace(request.getPace()));
        trip.setPreferences(jsonUtils.toJson(request.getPreferences()));
        trip.setStatus("planning");
        trip.setViewCount(0);

        tripRepository.insert(trip);

        // 2. 创建初始版本
        TripVersion version = versionService.createInitialVersion(trip.getId(), request);
        trip.setCurrentVersionId(version.getId());
        tripRepository.updateById(trip);

        // 3. 执行 AI Agent 规划 (调用 plan-service)
        try {
            log.info("开始 AI Agent 规划: tripId={}", trip.getId());
            
            // 构造内部服务调用 Header
            String authHeader = "Bearer internal-trip-service";
            
            // 先解析输入（cityHint = 表单显式城市，优先级最高）
            Map<String, String> parseRequest = new HashMap<>();
            parseRequest.put("rawInput", request.getRawInput());
            parseRequest.put("timeStart", request.getTimeStart().toString());
            parseRequest.put("timeEnd", request.getTimeEnd().toString());
            parseRequest.put("transportMode", request.getTransportMode());
            parseRequest.put("pace", trip.getPace());
            if (request.getCity() != null && !request.getCity().isBlank()) {
                parseRequest.put("city", request.getCity());
            }
            
            ApiResponse<Map<String, Object>> parseResult = planServiceClient.parseInput(authHeader, parseRequest);
            
            if (parseResult == null || !parseResult.isSuccess()) {
                throw new RuntimeException("解析失败: " + (parseResult != null ? parseResult.getError() : "null response"));
            }
            
            Map<String, Object> parseData = parseResult.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> places = (List<Map<String, Object>>) parseData.get("places");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> meals = (List<Map<String, Object>>) parseData.get("meals");
            String city = (String) parseData.get("city");
            
            // 解析结构（含 city/summary/quote）落库，供前端展示与重规划复用
            trip.setParsedInput(jsonUtils.toJson(parseData));
            tripRepository.updateById(trip);
            
            log.info("解析完成: city={}, places={}, meals={}", city, places.size(), meals.size());
            
            // 生成详细行程（city = 解析后的目标城市，plan-service 按此过滤跨城 POI）
            Map<String, Object> itineraryRequest = new HashMap<>();
            itineraryRequest.put("rawInput", request.getRawInput());
            itineraryRequest.put("places", places);
            itineraryRequest.put("meals", meals);
            itineraryRequest.put("timeStart", request.getTimeStart().toString());
            itineraryRequest.put("timeEnd", request.getTimeEnd().toString());
            itineraryRequest.put("distanceMatrix", "无");
            itineraryRequest.put("pace", trip.getPace());
            if (city != null && !city.isBlank()) {
                itineraryRequest.put("city", city);
            }
            
            ApiResponse<Map<String, Object>> itineraryResult = planServiceClient.planItinerary(authHeader, itineraryRequest);
            
            if (itineraryResult == null || !itineraryResult.isSuccess()) {
                throw new RuntimeException("行程生成失败: " + (itineraryResult != null ? itineraryResult.getError() : "null response"));
            }
            
            Map<String, Object> itineraryData = itineraryResult.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawActivities = (List<Map<String, Object>>) itineraryData.get("activities");

            // 字段名映射：plan-service 返回 name/type/startTime -> poi_name/activity_type/scheduled_start
            List<Map<String, Object>> activities = new ArrayList<>();
            int seq = 1;
            for (Map<String, Object> raw : rawActivities) {
                Map<String, Object> act = new java.util.LinkedHashMap<>();
                act.put("id", "act" + seq);
                act.put("seq", seq);
                act.put("day", raw.getOrDefault("day", ((seq - 1) / 7) + 1));
                act.put("poi_name", raw.getOrDefault("name", raw.get("poi_name")));
                act.put("activity_type", mapActivityType(raw.getOrDefault("type", raw.get("activity_type"))));
                act.put("scheduled_start", raw.getOrDefault("startTime", raw.get("scheduled_start")));
                act.put("scheduled_end", raw.getOrDefault("endTime", raw.get("scheduled_end")));
                act.put("duration_min", raw.getOrDefault("durationMin", raw.getOrDefault("duration_min", 60)));
                act.put("priority", raw.getOrDefault("priority", "recommended"));
                act.put("status", "scheduled");
                act.put("transport_mode", raw.getOrDefault("transportToNext", raw.getOrDefault("transport_mode", "transit")));
                act.put("travel_duration_min", raw.getOrDefault("travelTimeMin", raw.getOrDefault("travel_duration_min", 0)));
                // 持久化真实路网距离（km），前端 travel_distance_km / routes.distance_km 共用
                Object distKm = raw.containsKey("travelDistanceKm")
                        ? raw.get("travelDistanceKm")
                        : raw.get("travel_distance_km");
                if (distKm != null) {
                    act.put("travel_distance_km", distKm);
                }
                if (raw.containsKey("notes")) act.put("slogan", raw.get("notes"));
                // 地图坐标透传（有则存，前端可直接画点/线）
                copyCoordIfPresent(raw, act, "lat");
                copyCoordIfPresent(raw, act, "lng");
                if (raw.containsKey("poi_address")) act.put("poi_address", raw.get("poi_address"));
                // 餐厅评分/人均（v1.15.0 餐厅推荐增强，前端评分与人均展示）
                if (raw.containsKey("rating")) act.put("rating", raw.get("rating"));
                if (raw.containsKey("cost")) act.put("cost", raw.get("cost"));
                activities.add(act);
                seq++;
            }

            // VERIFY_CITY: 校验推荐景点是否在目标城市范围内
            activities = verifyCityOwnership(activities, city, trip.getId());
            // 入库前二次去重：景点/餐食不得重复
            activities = dedupeActivitiesForPersist(activities);

            // 生成路线信息 (基于活动序列)
            List<Map<String, Object>> routes = buildRoutesFromActivities(activities);
            
            // 统计信息（含时长/景点数，供详情页底部汇总展示）
            Map<String, Object> stats = buildStatsFromActivities(
                activities, city, "ai-agent", calculateDays(request.getTimeStart(), request.getTimeEnd()));
            
            versionService.updateVersionResult(
                version.getId(),
                objectMapper.writeValueAsString(activities),
                objectMapper.writeValueAsString(routes),
                "[]",
                objectMapper.writeValueAsString(stats),
                "{\"planner\":\"ai-agent\"}",
                "completed"
            );
            
            trip.setStatus("completed");
            tripRepository.updateById(trip);
            log.info("创建行程并完成 AI Agent 规划: tripId={}, activities={}", trip.getId(), activities.size());
        } catch (Exception e) {
            log.error("AI Agent 规划失败，回退到内联规划: tripId={}", trip.getId(), e);
            // 写入调试文件便于排查
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("agent-fallback-debug.txt"),
                    "Time: " + java.time.LocalDateTime.now() + "\n" +
                    "TripId: " + trip.getId() + "\n" +
                    "Exception: " + e.getClass().getName() + "\n" +
                    "Message: " + e.getMessage() + "\n" +
                    "Stack:\n" + getStackTrace(e) + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {}

            // 回退到内联规划
            try {
                Map<String, Object> result = inlinePlanner.planTrip(trip);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> activities = (List<Map<String, Object>>) result.get("activities");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routes");
                @SuppressWarnings("unchecked")
                Map<String, Object> stats = (Map<String, Object>) result.get("stats");

                versionService.updateVersionResult(
                    version.getId(),
                    objectMapper.writeValueAsString(activities),
                    objectMapper.writeValueAsString(routes),
                    "[]",
                    objectMapper.writeValueAsString(stats),
                    "{\"planner\":\"inline-fallback\"}",
                    "completed"
                );

                trip.setStatus("completed");
                tripRepository.updateById(trip);
                log.info("回退内联规划完成: tripId={}, activities={}", trip.getId(), activities.size());
            } catch (Exception fallbackEx) {
                log.error("回退规划也失败: tripId={}", trip.getId(), fallbackEx);
                trip.setStatus("failed");
                tripRepository.updateById(trip);
            }
        }

        return toResponse(trip);
    }

    /**
     * 分页查询用户行程
     */
    public TripListResponse listTrips(String userId, int page, int size, String keyword, String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int offset = (safePage - 1) * safeSize;

        List<Trip> trips;
        long total;

        if (keyword != null && !keyword.isBlank()) {
            trips = tripRepository.searchByKeyword(userId, keyword, offset, size);
            total = tripRepository.countSearchByKeyword(userId, keyword);
        } else {
            trips = tripRepository.findByUserId(userId, offset, size);
            total = tripRepository.countByUserId(userId);
        }

        // 状态过滤 (内存过滤，数据量大时建议 SQL 过滤)
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            trips = trips.stream()
                    .filter(t -> status.equals(t.getStatus()))
                    .toList();
            total = trips.size();
        }

        List<TripResponse> items = trips.stream()
                .map(this::toResponse)
                .toList();
        enrichAuthorNames(items);

        return TripListResponse.builder()
                .items(items)
                .total(total)
                .page(safePage)
                .size(safeSize)
                .totalPages((int) Math.ceil((double) total / safeSize))
                .build();
    }

    /**
     * 获取行程详情 (含最新版本)
     */
    public TripResponse getTrip(String userId, String tripId) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null || "deleted".equals(trip.getStatus())) {
            throw BizException.notFound("行程", tripId);
        }

        // 权限检查：本人或公开行程
        if (!trip.getUserId().equals(userId) && !Boolean.TRUE.equals(trip.getIsPublic())) {
            throw BizException.forbidden("无权访问该行程");
        }

        // 增加查看次数
        if (!trip.getUserId().equals(userId)) {
            trip.setViewCount(trip.getViewCount() + 1);
            tripRepository.updateById(trip);
        }

        return toResponseWithLatestVersion(trip);
    }

    /**
     * 更新行程基本信息
     */
    public TripResponse updateTrip(String userId, String tripId, UpdateTripRequest request) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null || "deleted".equals(trip.getStatus())) {
            throw BizException.notFound("行程", tripId);
        }

        // 权限检查
        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权修改该行程");
        }

        if (request.getTitle() != null) {
            trip.setTitle(request.getTitle());
        }
        if (request.getTimeStart() != null) {
            trip.setTimeStart(request.getTimeStart());
        }
        if (request.getTimeEnd() != null) {
            trip.setTimeEnd(request.getTimeEnd());
        }
        if (request.getTransportMode() != null) {
            trip.setTransportMode(request.getTransportMode());
        }
        if (request.getPace() != null) {
            trip.setPace(normalizePace(request.getPace()));
        }
        if (request.getStatus() != null) {
            trip.setStatus(request.getStatus());
        }
        if (request.getPreferences() != null) {
            trip.setPreferences(jsonUtils.toJson(request.getPreferences()));
        }
        if (request.getIsPublic() != null) {
            trip.setIsPublic(request.getIsPublic());
        }

        tripRepository.updateById(trip);

        return toResponse(trip);
    }

    /**
     * 删除行程（软删除，列表不再展示）
     */
    public void deleteTrip(String userId, String tripId) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权删除该行程");
        }

        trip.setStatus("deleted");
        trip.setIsPublic(false);
        tripRepository.updateById(trip);

        log.info("删除行程: tripId={}", tripId);
    }

    /**
     * 保存Agent规划结果（活动列表）
     */
    @SuppressWarnings("unchecked")
    public void saveActivities(String userId, String tripId, Map<String, Object> body) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权修改该行程");
        }

        List<Map<String, Object>> activityList = (List<Map<String, Object>>) body.get("activities");
        if (activityList == null || activityList.isEmpty()) {
            throw BizException.internalError("活动列表不能为空");
        }

        try {
            // 查找最新版本，或创建新版本
            TripVersion latest = versionRepository.findLatestByTripId(tripId);
            int nextNum = (latest != null ? latest.getVersionNum() : 0) + 1;

            TripVersion version = new TripVersion();
            version.setId(UUID.randomUUID().toString().replace("-", ""));
            version.setTripId(tripId);
            version.setVersionNum(nextNum);
            version.setParentVersionId(latest != null ? latest.getId() : null);
            version.setActivities(objectMapper.writeValueAsString(activityList));
            version.setStatus("completed");
            versionRepository.insert(version);

            // 填充 activities 表（扁平化存储，便于查询）
            for (Map<String, Object> am : activityList) {
                Activity act = new Activity();
                act.setId(UUID.randomUUID().toString().replace("-", ""));
                act.setVersionId(version.getId());
                act.setSeq(am.get("seq") != null ? ((Number) am.get("seq")).intValue() : 0);
                act.setPoiName(am.get("poiName") != null ? (String) am.get("poiName") : "Unknown");
                act.setActivityType(am.get("activityType") != null ? (String) am.get("activityType") : "visit");
                act.setPriority(am.get("priority") != null ? (String) am.get("priority") : "recommended");
                act.setDurationMin(am.get("durationMin") != null ? ((Number) am.get("durationMin")).intValue() : 60);
                act.setTransportMode(am.get("transportMode") != null ? (String) am.get("transportMode") : "transit");
                act.setTravelDurationMin(am.get("travelDurationMin") != null ? ((Number) am.get("travelDurationMin")).intValue() : null);
                if (am.get("scheduledStart") != null) {
                    try { act.setScheduledStart(LocalDateTime.parse((String) am.get("scheduledStart"))); } catch (Exception ignored) {}
                }
                if (am.get("scheduledEnd") != null) {
                    try { act.setScheduledEnd(LocalDateTime.parse((String) am.get("scheduledEnd"))); } catch (Exception ignored) {}
                }
                act.setNotes(am.get("notes") != null ? (String) am.get("notes") : null);
                activityRepository.insert(act);
            }

            // 更新行程状态和当前版本
            trip.setCurrentVersionId(version.getId());
            trip.setStatus("completed");
            tripRepository.updateById(trip);

            log.info("保存Agent规划结果: tripId={}, versionNum={}, activities={}", tripId, nextNum, activityList.size());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存规划结果失败: tripId={}", tripId, e);
            throw BizException.internalError("保存规划结果失败: " + e.getMessage());
        }
    }

    /**
     * 触发/重新规划 (内联同步)
     *
     * @param variant true=换版规划：主题不变（城市/日期/节奏/用户指定地点），排除基准版本已用 POI，生成内容不同的新路线
     */
    public void triggerPlanning(String userId, String tripId, String baseVersionId) {
        triggerPlanning(userId, tripId, baseVersionId, false);
    }

    public void triggerPlanning(String userId, String tripId, String baseVersionId, boolean variant) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权操作该行程");
        }

        trip.setStatus("planning");
        tripRepository.updateById(trip);

        // 创建新版本
        String versionId = baseVersionId != null ? baseVersionId : trip.getCurrentVersionId();
        // 换版规划：先收集基准版本已用 POI 与每日行程摘要（创建新版本前，一次遍历）
        VariantBase variantBase = variant ? collectVariantBase(versionId) : VariantBase.empty();
        TripVersion newVersion = new TripVersion();
        newVersion.setId(UUID.randomUUID().toString().replace("-", ""));
        newVersion.setTripId(tripId);
        TripVersion latestVersion = versionRepository.findLatestByTripId(tripId);
        newVersion.setVersionNum(latestVersion != null ? latestVersion.getVersionNum() + 1 : 1);
        newVersion.setParentVersionId(versionId);
        newVersion.setActivities("[]");
        newVersion.setRoutes("[]");
        newVersion.setConflicts("[]");
        newVersion.setStats("{}");
        newVersion.setStatus("draft");
        versionRepository.insert(newVersion);

        trip.setCurrentVersionId(newVersion.getId());
        tripRepository.updateById(trip);

        // 优先 AI Agent 规划，失败回退内联
        try {
            log.info("开始 AI Agent 重新规划: tripId={}", tripId);
            String authHeader = "Bearer internal-trip-service";

            Map<String, String> parseRequest = new HashMap<>();
            parseRequest.put("rawInput", trip.getRawInput() != null ? trip.getRawInput() : trip.getTitle());
            parseRequest.put("timeStart", trip.getTimeStart().toString());
            parseRequest.put("timeEnd", trip.getTimeEnd().toString());
            parseRequest.put("transportMode", trip.getTransportMode() != null ? trip.getTransportMode() : "transit");
            parseRequest.put("pace", normalizePace(trip.getPace()));
            String priorCity = extractParsedCity(trip.getParsedInput());
            if (priorCity != null && !priorCity.isBlank()) {
                parseRequest.put("city", priorCity);
            }

            ApiResponse<Map<String, Object>> parseResult = planServiceClient.parseInput(authHeader, parseRequest);
            if (parseResult == null || !parseResult.isSuccess()) {
                throw new RuntimeException("解析失败");
            }

            Map<String, Object> parseData = parseResult.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> places = (List<Map<String, Object>>) parseData.get("places");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> meals = (List<Map<String, Object>>) parseData.get("meals");
            String city = (String) parseData.get("city");

            // 重新解析结果覆盖落库，保持 city/summary/quote 与最新一致
            trip.setParsedInput(jsonUtils.toJson(parseData));
            tripRepository.updateById(trip);

            Map<String, Object> itineraryRequest = new HashMap<>();
            itineraryRequest.put("rawInput", trip.getRawInput() != null ? trip.getRawInput() : trip.getTitle());
            itineraryRequest.put("places", places != null ? places : List.of());
            itineraryRequest.put("meals", meals != null ? meals : List.of());
            itineraryRequest.put("timeStart", trip.getTimeStart().toString());
            itineraryRequest.put("timeEnd", trip.getTimeEnd().toString());
            itineraryRequest.put("distanceMatrix", "无");
            itineraryRequest.put("pace", normalizePace(trip.getPace()));
            if (city != null && !city.isBlank()) {
                itineraryRequest.put("city", city);
            }
            // 换版规划：排除基准版本已用 POI + 基准每日行程摘要，让 LLM 换内容不换主题
            if (!variantBase.pois().isEmpty()) {
                itineraryRequest.put("variant", "true");
                itineraryRequest.put("excludePois", variantBase.pois());
                if (variantBase.summary() != null && !variantBase.summary().isBlank()) {
                    itineraryRequest.put("basePlanSummary", variantBase.summary());
                }
                log.info("换版规划启动: tripId={}, 排除POI={}个, 摘要={}字",
                        tripId, variantBase.pois().size(),
                        variantBase.summary() == null ? 0 : variantBase.summary().length());
            }

            ApiResponse<Map<String, Object>> itineraryResult = planServiceClient.planItinerary(authHeader, itineraryRequest);
            if (itineraryResult == null || !itineraryResult.isSuccess()) {
                throw new RuntimeException("行程生成失败");
            }

            Map<String, Object> itineraryData = itineraryResult.getData();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawActivities = (List<Map<String, Object>>) itineraryData.get("activities");

            List<Map<String, Object>> activities = new ArrayList<>();
            int seq = 1;
            for (Map<String, Object> raw : rawActivities) {
                Map<String, Object> act = new java.util.LinkedHashMap<>();
                act.put("id", "act" + seq);
                act.put("seq", seq);
                act.put("day", raw.getOrDefault("day", ((seq - 1) / 7) + 1));
                act.put("poi_name", raw.getOrDefault("name", raw.get("poi_name")));
                act.put("activity_type", mapActivityType(raw.getOrDefault("type", raw.get("activity_type"))));
                act.put("scheduled_start", raw.getOrDefault("startTime", raw.get("scheduled_start")));
                act.put("scheduled_end", raw.getOrDefault("endTime", raw.get("scheduled_end")));
                act.put("duration_min", raw.getOrDefault("durationMin", raw.getOrDefault("duration_min", 60)));
                act.put("priority", raw.getOrDefault("priority", "recommended"));
                act.put("status", "scheduled");
                act.put("transport_mode", raw.getOrDefault("transportToNext", raw.getOrDefault("transport_mode", "transit")));
                act.put("travel_duration_min", raw.getOrDefault("travelTimeMin", raw.getOrDefault("travel_duration_min", 0)));
                Object distKm = raw.containsKey("travelDistanceKm")
                        ? raw.get("travelDistanceKm")
                        : raw.get("travel_distance_km");
                if (distKm != null) {
                    act.put("travel_distance_km", distKm);
                }
                if (raw.containsKey("notes")) act.put("slogan", raw.get("notes"));
                copyCoordIfPresent(raw, act, "lat");
                copyCoordIfPresent(raw, act, "lng");
                if (raw.containsKey("poi_address")) act.put("poi_address", raw.get("poi_address"));
                // 餐厅评分/人均（v1.15.0 餐厅推荐增强，前端评分与人均展示）
                if (raw.containsKey("rating")) act.put("rating", raw.get("rating"));
                if (raw.containsKey("cost")) act.put("cost", raw.get("cost"));
                activities.add(act);
                seq++;
            }

            // VERIFY_CITY: 校验推荐景点是否在目标城市范围内
            activities = verifyCityOwnership(activities, city, tripId);
            // 入库前二次去重：景点/餐食不得重复
            activities = dedupeActivitiesForPersist(activities);

            List<Map<String, Object>> routes = buildRoutesFromActivities(activities);
            Map<String, Object> stats = buildStatsFromActivities(
                activities, city != null ? city : "", "ai-agent",
                calculateDays(trip.getTimeStart(), trip.getTimeEnd()));

            versionService.updateVersionResult(
                newVersion.getId(),
                objectMapper.writeValueAsString(activities),
                objectMapper.writeValueAsString(routes),
                "[]",
                objectMapper.writeValueAsString(stats),
                "{\"planner\":\"ai-agent\"}",
                "completed"
            );

            trip.setStatus("completed");
            tripRepository.updateById(trip);
            log.info("AI Agent 重新规划完成: tripId={}, activities={}", tripId, activities.size());
        } catch (Exception aiEx) {
            log.error("AI Agent 重新规划失败，回退内联: tripId={}", tripId, aiEx);
            try {
                Map<String, Object> result = inlinePlanner.planTrip(trip);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> activities = (List<Map<String, Object>>) result.get("activities");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routes");
                @SuppressWarnings("unchecked")
                Map<String, Object> stats = (Map<String, Object>) result.get("stats");

                versionService.updateVersionResult(
                    newVersion.getId(),
                    objectMapper.writeValueAsString(activities),
                    objectMapper.writeValueAsString(routes),
                    "[]",
                    objectMapper.writeValueAsString(stats),
                    "{\"planner\":\"inline-fallback\"}",
                    "completed"
                );

                trip.setStatus("completed");
                tripRepository.updateById(trip);
                log.info("回退内联重新规划完成: tripId={}", tripId);
            } catch (Exception fallbackEx) {
                log.error("内联重新规划也失败: tripId={}", tripId, fallbackEx);
                trip.setStatus("failed");
                tripRepository.updateById(trip);
            }
        }
    }

    /** 换版基准信息：排除列表 + 每日行程摘要 */
    private record VariantBase(List<String> pois, String summary) {
        static VariantBase empty() {
            return new VariantBase(List.of(), null);
        }
    }

    /**
     * 收集基准版本已用 POI 与每日行程摘要（换版规划用，一次遍历）
     * 景点取 poi_name；餐饮「午餐·餐厅名」额外拆出餐厅名，避免换版后仍用同一餐厅；
     * 摘要按天聚合为「Day1: 西湖 → 午餐·楼外楼 → …」，供 LLM 显式避开相同组合
     */
    private VariantBase collectVariantBase(String baseVersionId) {
        if (baseVersionId == null || baseVersionId.isBlank()) {
            return VariantBase.empty();
        }
        TripVersion base = versionRepository.findById(baseVersionId);
        if (base == null || base.getActivities() == null || base.getActivities().isBlank()) {
            return VariantBase.empty();
        }
        try {
            List<Map<String, Object>> acts = objectMapper.readValue(
                    base.getActivities(), new TypeReference<List<Map<String, Object>>>() {});
            Set<String> names = new LinkedHashSet<>();
            Map<Integer, List<String>> byDay = new TreeMap<>();
            for (Map<String, Object> act : acts) {
                Object raw = act.getOrDefault("poi_name", act.getOrDefault("poiName", act.get("name")));
                if (raw == null) {
                    continue;
                }
                String poiName = String.valueOf(raw).trim();
                if (poiName.isEmpty() || "null".equals(poiName)) {
                    continue;
                }
                names.add(poiName);
                int sep = poiName.indexOf('·');
                if (sep > 0 && sep < poiName.length() - 1) {
                    names.add(poiName.substring(sep + 1).trim());
                }
                Object rawType = act.getOrDefault("activity_type", act.getOrDefault("activityType", act.get("type")));
                String type = rawType == null ? "" : String.valueOf(rawType);
                if (!"transit".equals(type)) {
                    int day = act.get("day") instanceof Number n ? n.intValue() : 1;
                    byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(poiName);
                }
            }
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<Integer, List<String>> entry : byDay.entrySet()) {
                if (summary.length() > 0) {
                    summary.append("；\n");
                }
                summary.append("Day").append(entry.getKey()).append(": ")
                        .append(String.join(" → ", entry.getValue()));
            }
            return new VariantBase(new ArrayList<>(names), summary.toString());
        } catch (Exception e) {
            log.warn("收集基准版本POI失败: versionId={}, err={}", baseVersionId, e.getMessage());
            return VariantBase.empty();
        }
    }

    /**
     * 获取公开行程列表（keyword 非空时按标题/描述/行程内地点名搜索）
     */
    public TripListResponse listPublicTrips(int page, int size, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();
        List<Trip> trips;
        long total;
        if (kw.isEmpty()) {
            trips = tripRepository.findPublicTrips(offset, safeSize);
            total = tripRepository.countPublicTrips();
        } else {
            trips = tripRepository.searchPublicTrips(kw, offset, safeSize);
            total = tripRepository.countPublicTripsByKeyword(kw);
        }
        List<TripResponse> items = trips.stream().map(this::toResponse).toList();
        enrichAuthorNames(items);

        return TripListResponse.builder()
                .items(items)
                .total(total)
                .page(safePage)
                .size(safeSize)
                .totalPages((int) Math.ceil((double) total / safeSize))
                .build();
    }

    /**
     * 批量补充作者显示名（公开行程卡片展示用户名）
     * 跨服务尽力而为：auth-service 不可用时降级为不返回名字（卡片回退展示 userId），绝不影响列表主流程
     */
    private void enrichAuthorNames(List<TripResponse> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> userIds = items.stream()
                .map(TripResponse::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        try {
            ApiResponse<Map<String, String>> resp = authUserClient.getUserNames(new ArrayList<>(userIds));
            Map<String, String> names = (resp != null && resp.getData() != null) ? resp.getData() : Map.of();
            for (TripResponse item : items) {
                item.setAuthorName(names.get(item.getUserId()));
            }
        } catch (Exception e) {
            log.warn("获取作者显示名失败，降级为无作者名: {}", e.getMessage());
        }
    }

    /**
     * 通过分享 Token 访问
     */
    public TripResponse getByShareToken(String token) {
        Trip trip = tripRepository.findByShareToken(token);
        if (trip == null || !Boolean.TRUE.equals(trip.getIsPublic())) {
            throw BizException.notFound("分享链接无效或已过期");
        }

        trip.setViewCount(trip.getViewCount() + 1);
        tripRepository.updateById(trip);

        return toResponseWithLatestVersion(trip);
    }

    /**
     * 活动频率归一：仅接受 compact(紧凑)/moderate(适中)/relaxed(宽松)，非法或空值回落到「适中」
     */
    private static String normalizePace(String pace) {
        if (pace == null || pace.isBlank()) {
            return "moderate";
        }
        String p = pace.trim().toLowerCase();
        return "compact".equals(p) || "relaxed".equals(p) ? p : "moderate";
    }

    private TripResponse toResponse(Trip trip) {
        TripResponse response = TripResponse.builder()
                .id(trip.getId())
                .userId(trip.getUserId())
                .title(trip.getTitle())
                .rawInput(trip.getRawInput())
                .parsedInput(jsonUtils.fromJson(trip.getParsedInput(), Map.class))
                .timeStart(trip.getTimeStart())
                .timeEnd(trip.getTimeEnd())
                .transportMode(trip.getTransportMode())
                .pace(normalizePace(trip.getPace()))
                .preferences(jsonUtils.fromJson(trip.getPreferences(), Map.class))
                .status(trip.getStatus())
                .currentVersionId(trip.getCurrentVersionId())
                .isPublic(trip.getIsPublic())
                .shareToken(trip.getShareToken())
                .viewCount(trip.getViewCount())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .build();
        enrichListMeta(response, trip);
        return response;
    }

    /**
     * 填充列表页展示用的城市、活动数、地标景点
     */
    @SuppressWarnings("unchecked")
    private void enrichListMeta(TripResponse response, Trip trip) {
        String city = null;
        int activityCount = 0;
        List<String> landmarks = new ArrayList<>();

        if (trip.getCurrentVersionId() != null) {
            TripVersion version = versionRepository.findById(trip.getCurrentVersionId());
            if (version != null) {
                try {
                    Map<String, Object> stats = jsonUtils.fromJson(version.getStats(), Map.class);
                    if (stats != null && stats.get("city") != null) {
                        city = String.valueOf(stats.get("city"));
                    }
                } catch (Exception ignored) {}

                try {
                    List<Map<String, Object>> activities = jsonUtils.fromJson(version.getActivities(), List.class);
                    if (activities != null) {
                        activityCount = activities.size();
                        for (Map<String, Object> act : activities) {
                            String type = String.valueOf(act.getOrDefault(
                                    "activity_type", act.getOrDefault("type", "visit")));
                            if (!"visit".equals(type) && !"shopping".equals(type) && !"museum".equals(type)) {
                                continue;
                            }
                            String name = String.valueOf(act.getOrDefault(
                                    "poi_name", act.getOrDefault("poiName", act.getOrDefault("name", ""))));
                            if (!name.isBlank() && !landmarks.contains(name) && landmarks.size() < 6) {
                                landmarks.add(name);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        if (city == null || city.isBlank()) {
            city = extractCityFromText(trip.getRawInput() + " " + trip.getTitle());
        }

        response.setCity(city);
        response.setActivityCount(activityCount);
        response.setLandmarks(landmarks);
    }

    private static final List<String> KNOWN_CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "西安", "南京",
            "重庆", "苏州", "武汉", "长沙", "厦门", "青岛", "昆明", "大理",
            "三亚", "桂林", "丽江", "天津", "郑州", "合肥", "福州", "济南"
    );

    private String extractCityFromText(String text) {
        if (text == null) return null;
        for (String city : KNOWN_CITIES) {
            if (text.contains(city)) return city;
        }
        return null;
    }

    private TripResponse toResponseWithLatestVersion(Trip trip) {
        TripResponse response = toResponse(trip);
        
        if (trip.getCurrentVersionId() != null) {
            TripVersion version = versionRepository.findById(trip.getCurrentVersionId());
            if (version != null) {
                response.setLatestVersion(versionService.toResponse(version));
            }
        }
        return response;
    }

    /**
     * 根据活动列表构建路线信息
     */
    private List<Map<String, Object>> buildRoutesFromActivities(List<Map<String, Object>> activities) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < activities.size() - 1; i++) {
            Map<String, Object> from = activities.get(i);
            Map<String, Object> to = activities.get(i + 1);

            Map<String, Object> route = new java.util.LinkedHashMap<>();
            route.put("from", from.getOrDefault("poi_name", from.get("name")));
            route.put("to", to.getOrDefault("poi_name", to.get("name")));
            route.put("mode", from.getOrDefault("transport_mode", from.getOrDefault("transportToNext", "transit")));
            // 统一 distance_km：优先持久化的 travel_distance_km，兼容旧键 travelDistanceKm
            Object distKm = from.getOrDefault("travel_distance_km",
                    from.getOrDefault("travelDistanceKm", 0.0));
            route.put("distance_km", distKm);
            route.put("duration_min", from.getOrDefault("travel_duration_min", from.getOrDefault("travelTimeMin", 0)));
            // 端点坐标：有则带上，便于前端兜底连线 / 拉取真实路径
            if (from.get("lat") != null && from.get("lng") != null) {
                route.put("from_lat", from.get("lat"));
                route.put("from_lng", from.get("lng"));
            }
            if (to.get("lat") != null && to.get("lng") != null) {
                route.put("to_lat", to.get("lat"));
                route.put("to_lng", to.get("lng"));
            }
            routes.add(route);
        }
        return routes;
    }

    /** 坐标字段透传：raw 里有有限数值 lat/lng 才写入 */
    private void copyCoordIfPresent(Map<String, Object> raw, Map<String, Object> act, String key) {
        Object v = raw.get(key);
        if (v == null) return;
        try {
            double d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
            if (Double.isFinite(d)) {
                act.put(key, d);
            }
        } catch (Exception ignored) {
            // 非数值坐标丢弃
        }
    }

    /**
     * 从已落库的 parsedInput JSON 中读取 city 字段（用于重规划时保持表单/LLM 城市优先级）
     *
     * @param parsedInput trip.parsedInput 原始 JSON
     * @return city 值；缺失或解析失败返回 null
     */
    private String extractParsedCity(String parsedInput) {
        if (parsedInput == null || parsedInput.isBlank() || "{}".equals(parsedInput.trim())) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(parsedInput, Map.class);
            Object city = data.get("city");
            String value = city != null ? String.valueOf(city).trim() : "";
            return value.isEmpty() || "null".equals(value) ? null : value.replace("市", "");
        } catch (Exception e) {
            log.debug("解析 parsedInput.city 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * VERIFY_CITY: 过滤明确属于其他城市的 visit 景点，并重排 seq。
     *
     * <p>外城餐厅（如「午餐·XX(西单店)」在长白山行程）不整条剔除：正餐槽位必须
     * 每日保留（缺午餐破坏用餐兜底），改为清掉餐厅专名与异地坐标降级为通用餐次
     * （BUGFIX 1.30.0）；跨城 visit 仍剔除。</p>
     */
    private List<Map<String, Object>> verifyCityOwnership(
            List<Map<String, Object>> activities, String city, String tripId) {
        if (activities == null || city == null || city.isBlank()) {
            return activities;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> degradedMeals = new ArrayList<>();
        int checked = 0;
        int seq = 1;
        for (Map<String, Object> act : activities) {
            String type = String.valueOf(act.getOrDefault("activity_type", act.getOrDefault("type", "visit")));
            String name = String.valueOf(act.getOrDefault("poi_name", act.getOrDefault("name", "")));
            boolean isTransport = "transit".equals(type) || "buffer".equals(type)
                    || "transport".equals(type) || "rest".equals(type);
            Map<String, Object> copy = new java.util.LinkedHashMap<>(act);
            copy.put("id", "act" + seq);
            copy.put("seq", seq);
            if (!isTransport && !name.isBlank()) {
                checked++;
                if (!com.tripplanner.common.util.CityOwnershipUtils.belongsToCity(name, city)) {
                    if ("meal".equals(type)) {
                        degradeForeignMeal(copy, name);
                        degradedMeals.add(name);
                    } else {
                        rejected.add(name);
                        continue;
                    }
                }
            }
            out.add(copy);
            seq++;
        }
        if (rejected.isEmpty() && degradedMeals.isEmpty()) {
            log.info("VERIFY_CITY 通过: tripId={}, city={}, visitTotal={}",
                    tripId, city, checked);
            return activities;
        }
        if (checked > 0 && rejected.size() * 2 > checked) {
            throw new IllegalStateException(
                    "规划结果景点城市归属校验失败: 目标城市=" + city
                            + ", 剔除=" + String.join("、", rejected));
        }
        if (!degradedMeals.isEmpty()) {
            log.warn("VERIFY_CITY 降级外城餐厅为通用餐次: tripId={}, city={}, meals={}",
                    tripId, city, String.join("、", degradedMeals));
        }
        if (!rejected.isEmpty()) {
            log.warn("VERIFY_CITY 剔除跨城景点: tripId={}, city={}, rejected={}, visitTotal={}",
                    tripId, city, String.join("、", rejected), checked);
        }
        return out;
    }

    /**
     * 外城餐厅降级：只留「午餐/晚餐」餐次名，清掉外城餐厅的描述与坐标，
     * 避免行程里出现异地餐厅名与异地地图钉。
     */
    private void degradeForeignMeal(Map<String, Object> act, String name) {
        int dot = name.indexOf('·');
        String slot = dot > 0 ? name.substring(0, dot).trim() : "餐食";
        if (slot.isEmpty()) {
            slot = "餐食";
        }
        act.put("name", slot);
        act.put("poi_name", slot);
        for (String key : new String[]{"description", "address", "lat", "lng",
                "latitude", "longitude", "location", "rating", "cost"}) {
            act.remove(key);
        }
    }

    /**
     * 根据活动列表汇总统计信息（总时长/游览/交通/用餐/景点数）
     */
    private Map<String, Object> buildStatsFromActivities(
            List<Map<String, Object>> activities, String city, String planner, int totalDays) {
        int visitDuration = 0;
        int transitDuration = 0;
        int mealDuration = 0;
        int restDuration = 0;
        Map<String, Long> placeCount = new java.util.LinkedHashMap<>();
        placeCount.put("must", 0L);
        placeCount.put("recommended", 0L);
        placeCount.put("optional", 0L);

        for (Map<String, Object> act : activities) {
            int duration = toIntQuiet(act.getOrDefault("duration_min", act.getOrDefault("durationMin", 0)));
            int travel = toIntQuiet(act.getOrDefault("travel_duration_min", act.getOrDefault("travelTimeMin", 0)));
            String type = String.valueOf(act.getOrDefault("activity_type", act.getOrDefault("type", "visit")));

            if ("transit".equals(type)) {
                transitDuration += duration + travel;
            } else if ("meal".equals(type)) {
                mealDuration += duration;
                transitDuration += travel;
            } else if ("rest".equals(type)) {
                restDuration += duration;
                transitDuration += travel;
            } else {
                visitDuration += duration;
                transitDuration += travel;
                String priority = String.valueOf(act.getOrDefault("priority", "recommended"));
                placeCount.merge(priority, 1L, Long::sum);
            }
        }

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalDurationMin", visitDuration + transitDuration + mealDuration + restDuration);
        stats.put("visitDurationMin", visitDuration);
        stats.put("transitDurationMin", transitDuration);
        stats.put("mealDurationMin", mealDuration);
        stats.put("restDurationMin", restDuration);
        stats.put("bufferDurationMin", 0);
        stats.put("placeCount", placeCount);
        stats.put("totalActivities", activities.size());
        stats.put("totalDays", totalDays);
        if (city != null) stats.put("city", city);
        if (planner != null) stats.put("planner", planner);
        return stats;
    }

    private int toIntQuiet(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private String mapActivityType(Object type) {
        if (type == null) return "visit";
        String t = type.toString();
        return switch (t) {
            case "restaurant", "meal" -> "meal";
            case "shopping" -> "shopping";
            case "transit", "transport" -> "transit";
            case "rest" -> "rest";
            case "museum", "temple", "park", "scenic", "other", "visit" -> "visit";
            default -> "visit";
        };
    }

    /**
     * 入库前二次去重：同名景点全程只保留首次；同日同餐次只保留一餐；餐食名与景点名等价视为重复
     */
    private List<Map<String, Object>> dedupeActivitiesForPersist(List<Map<String, Object>> activities) {
        if (activities == null || activities.isEmpty()) return activities == null ? new ArrayList<>() : activities;
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> seenVisit = new ArrayList<>();
        List<String> seenMeal = new ArrayList<>();
        java.util.Set<String> keptMealSlots = new java.util.HashSet<>();
        for (Map<String, Object> a : activities) {
            String type = String.valueOf(a.getOrDefault("activity_type", a.getOrDefault("type", "visit")));
            String name = String.valueOf(a.getOrDefault("poi_name", a.getOrDefault("name", ""))).trim();
            boolean transit = "transit".equals(type) || "transport".equals(type);
            boolean meal = "meal".equals(type) || "restaurant".equals(type);
            boolean rest = "rest".equals(type) || "buffer".equals(type);
            if (rest) {
                out.add(a);
                continue;
            }
            if ((meal || (!transit && !name.isEmpty())) && !name.isEmpty()) {
                String key = normalizeActivityName(name);
                boolean dup = false;
                if (meal) {
                    // 同日同餐次只保留一餐：slot 已占用 → 判重剔除
                    int day = toIntQuiet(a.getOrDefault("day", 1));
                    String slot = mealSlotOf(name, a.get("scheduled_start"));
                    if (!keptMealSlots.add(day + ":" + slot)) {
                        dup = true;
                    } else {
                        // 撞名（与全程既有餐厅/景点重名）但当日该餐次仍空缺 → 保留：
                        // 「同名餐厅全程去重」不得删空当日晚/午餐（见 CHANGELOG 1.29.0）
                        boolean nameDup = false;
                        for (String prev : seenMeal) {
                            if (key.equals(normalizeActivityName(prev)) || nameContains(name, prev)) {
                                nameDup = true;
                                break;
                            }
                        }
                        if (!nameDup) {
                            for (String prev : seenVisit) {
                                if (key.equals(normalizeActivityName(prev))) {
                                    nameDup = true;
                                    break;
                                }
                            }
                        }
                        if (nameDup) {
                            log.info("入库去重: 撞名但当日{}餐空缺，保留正餐: day={}, name={}", slot, day, name);
                        }
                    }
                    if (!dup) seenMeal.add(name);
                } else {
                    for (String prev : seenVisit) {
                        if (key.equals(normalizeActivityName(prev)) || nameContains(name, prev)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        for (String prev : seenMeal) {
                            if (key.equals(normalizeActivityName(prev))) {
                                dup = true;
                                break;
                            }
                        }
                    }
                    if (!dup) seenVisit.add(name);
                }
                if (dup) {
                    log.info("入库去重剔除: day={}, type={}, name={}", a.get("day"), type, name);
                    continue;
                }
            }
            out.add(a);
        }
        // 重排 id/seq
        int seq = 1;
        for (Map<String, Object> a : out) {
            a.put("id", "act" + seq);
            a.put("seq", seq);
            seq++;
        }
        if (out.size() != activities.size()) {
            log.info("入库二次去重: {} -> {}", activities.size(), out.size());
        }
        return out;
    }

    private String normalizeActivityName(String name) {
        if (name == null) return "";
        String k = name.trim().replaceAll("\\s+", "");
        return k.replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
    }

    private boolean nameContains(String a, String b) {
        String na = normalizeActivityName(a);
        String nb = normalizeActivityName(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;
        int min = Math.min(na.length(), nb.length());
        return min >= 3 && (na.contains(nb) || nb.contains(na));
    }

    private String mealSlotOf(String name, Object start) {
        if (name != null) {
            if (name.contains("早")) return "b";
            if (name.contains("晚")) return "d";
            if (name.contains("午")) return "l";
        }
        // 时间回退：兼容 "HH:mm" 与 ISO "yyyy-MM-ddTHH:mm:ss"（旧逻辑 substring(11,13)
        // 对 HH:mm 恒越界 → 无餐次字的晚餐被判成午餐，被 slot 去重删空，见 BUGFIX 1.29.0）
        try {
            String t = start == null ? "" : start.toString().trim();
            int ci = t.indexOf(':');
            if (ci > 0) {
                int h = Integer.parseInt(t.substring(Math.max(0, ci - 2), ci));
                if (h < 10) return "b";
                if (h > 16) return "d";
                return "l";
            }
        } catch (Exception ignored) {}
        return "l";
    }

    /**
     * 计算天数
     */
    private int calculateDays(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return 1;
        long days = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
        return (int) Math.max(1, days + 1);
    }

    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}