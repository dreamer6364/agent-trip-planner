package com.tripplanner.trip.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.dto.request.CreateVersionRequest;
import com.tripplanner.trip.dto.request.RollbackVersionRequest;
import com.tripplanner.trip.dto.response.TripVersionResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 行程版本管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TripVersionService {

    private final TripVersionRepository versionRepository;
    private final TripRepository tripRepository;
    private final ActivityRepository activityRepository;
    private final TripEventPublisher eventPublisher;
    private final SloganService sloganService;
    private final JsonUtils jsonUtils;

    /**
     * 创建初始版本 (草稿)
     */
    public TripVersion createInitialVersion(String tripId, com.tripplanner.trip.dto.request.CreateTripRequest request) {
        TripVersion version = new TripVersion();
        version.setId(UUID.randomUUID().toString().replace("-", ""));
        version.setTripId(tripId);
        version.setVersionNum(1);
        version.setParentVersionId(null);
        version.setActivities("[]");
        version.setRoutes("[]");
        version.setConflicts("[]");
        version.setStats("{}");
        version.setStatus("draft");

        versionRepository.insert(version);
        return version;
    }

    /**
     * 基于反馈创建新版本 (触发重新规划)
     */
    public TripVersion createVersionFromFeedback(String userId, String tripId, CreateVersionRequest request) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权操作该行程");
        }

        // 获取基础版本
        TripVersion baseVersion = versionRepository.findById(request.getBaseVersionId());
        if (baseVersion == null) {
            throw BizException.notFound("基础版本", request.getBaseVersionId());
        }

        // 创建新版本
        int nextVersionNum = getNextVersionNum(tripId);
        TripVersion newVersion = new TripVersion();
        newVersion.setId(UUID.randomUUID().toString().replace("-", ""));
        newVersion.setTripId(tripId);
        newVersion.setVersionNum(nextVersionNum);
        newVersion.setParentVersionId(request.getBaseVersionId());
        newVersion.setActivities(baseVersion.getActivities()); // 继承基础版本活动
        newVersion.setRoutes(baseVersion.getRoutes());
        newVersion.setConflicts("[]");
        newVersion.setStats("{}");
        newVersion.setFeedback(request.getFeedback());
        newVersion.setSolverMeta("{}");
        newVersion.setStatus("planning");

        versionRepository.insert(newVersion);

        // 更新行程当前版本
        trip.setCurrentVersionId(newVersion.getId());
        trip.setStatus("planning");
        tripRepository.updateById(trip);

        // 发送重新规划任务
        eventPublisher.publishReplanTask(tripId, newVersion.getId(), request);

        log.info("基于反馈创建新版本: tripId={}, versionNum={}, baseVersionId={}", tripId, nextVersionNum, request.getBaseVersionId());

        return newVersion;
    }

    /**
     * 回滚到指定版本
     */
    public TripVersion rollbackToVersion(String userId, String tripId, String versionId, RollbackVersionRequest request) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权操作该行程");
        }

        TripVersion targetVersion = versionRepository.findById(versionId);
        if (targetVersion == null || !targetVersion.getTripId().equals(tripId)) {
            throw BizException.notFound("版本", versionId);
        }

        // 创建新版本，复制目标版本数据
        int nextVersionNum = getNextVersionNum(tripId);
        TripVersion newVersion = new TripVersion();
        newVersion.setId(UUID.randomUUID().toString().replace("-", ""));
        newVersion.setTripId(tripId);
        newVersion.setVersionNum(nextVersionNum);
        newVersion.setParentVersionId(versionId);
        newVersion.setActivities(targetVersion.getActivities());
        newVersion.setRoutes(targetVersion.getRoutes());
        newVersion.setConflicts(targetVersion.getConflicts());
        newVersion.setStats(targetVersion.getStats());
        newVersion.setFeedback("回滚至 v" + targetVersion.getVersionNum() + (request.getReason() != null ? ": " + request.getReason() : ""));
        newVersion.setSolverMeta("{}");
        newVersion.setStatus("completed");

        versionRepository.insert(newVersion);

        // 更新行程当前版本
        trip.setCurrentVersionId(newVersion.getId());
        trip.setStatus("completed");
        tripRepository.updateById(trip);

        log.info("回滚版本: tripId={}, from v{} to v{}", tripId, targetVersion.getVersionNum(), nextVersionNum);

        return newVersion;
    }

    /**
     * 基于历史版本分支新建行程
     */
    public Trip forkFromVersion(String userId, String tripId, String versionId, String newTitle) {
        Trip originalTrip = tripRepository.selectById(tripId);
        if (originalTrip == null) {
            throw BizException.notFound("行程", tripId);
        }

        TripVersion sourceVersion = versionRepository.findById(versionId);
        if (sourceVersion == null) {
            throw BizException.notFound("版本", versionId);
        }

        // 创建新行程
        Trip newTrip = new Trip();
        newTrip.setId(UUID.randomUUID().toString().replace("-", ""));
        newTrip.setUserId(userId);
        newTrip.setTitle(newTitle != null ? newTitle : originalTrip.getTitle() + " (分支)");
        newTrip.setRawInput(originalTrip.getRawInput());
        newTrip.setParsedInput(originalTrip.getParsedInput());
        newTrip.setTimeStart(originalTrip.getTimeStart());
        newTrip.setTimeEnd(originalTrip.getTimeEnd());
        newTrip.setTransportMode(originalTrip.getTransportMode());
        newTrip.setPreferences(originalTrip.getPreferences());
        newTrip.setStatus("completed");
        newTrip.setViewCount(0);

        tripRepository.insert(newTrip);

        // 复制版本数据创建新版本
        TripVersion newVersion = new TripVersion();
        newVersion.setId(UUID.randomUUID().toString().replace("-", ""));
        newVersion.setTripId(newTrip.getId());
        newVersion.setVersionNum(1);
        newVersion.setParentVersionId(null);
        newVersion.setActivities(sourceVersion.getActivities());
        newVersion.setRoutes(sourceVersion.getRoutes());
        newVersion.setConflicts(sourceVersion.getConflicts());
        newVersion.setStats(sourceVersion.getStats());
        newVersion.setFeedback("分支自行程 " + tripId + " v" + sourceVersion.getVersionNum());
        newVersion.setSolverMeta("{}");
        newVersion.setStatus("completed");

        versionRepository.insert(newVersion);

        // 复制活动项
        copyActivities(versionId, newVersion.getId());

        newTrip.setCurrentVersionId(newVersion.getId());
        tripRepository.updateById(newTrip);

        log.info("分支新建行程: originalTripId={}, newTripId={}", tripId, newTrip.getId());

        return newTrip;
    }

    /**
     * 获取版本历史
     */
    public List<TripVersion> getVersionHistory(String tripId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int offset = (safePage - 1) * safeSize;
        return versionRepository.findByTripId(tripId, offset, safeSize);
    }

    /**
     * 获取版本总数
     */
    public long countVersions(String tripId) {
        return versionRepository.countByTripId(tripId);
    }

    /**
     * 获取指定版本详情
     */
    public TripVersion getVersion(String tripId, String versionId) {
        TripVersion version = versionRepository.findById(versionId);
        if (version == null || !version.getTripId().equals(tripId)) {
            throw BizException.notFound("版本", versionId);
        }
        return version;
    }

    /**
     * 按版本号获取指定版本
     */
    public TripVersion getVersionByNum(String tripId, int versionNum) {
        TripVersion version = versionRepository.findByTripIdAndVersionNum(tripId, versionNum);
        if (version == null) {
            throw BizException.notFound("版本", "v" + versionNum);
        }
        return version;
    }

    /**
     * 获取最新版本
     */
    public TripVersion getLatestVersion(String tripId) {
        return versionRepository.findLatestByTripId(tripId);
    }

    /**
     * 更新版本数据 (规划完成后调用)
     */
    public void updateVersionResult(String versionId, String activities, String routes, String conflicts, String stats, String solverMeta, String status) {
        TripVersion version = versionRepository.selectById(versionId);
        if (version == null) {
            log.warn("更新版本结果失败: 版本不存在 versionId={}", versionId);
            return;
        }

        version.setActivities(activities);
        version.setRoutes(routes);
        version.setConflicts(conflicts);
        version.setStats(stats);
        version.setSolverMeta(solverMeta);
        version.setStatus(status);

        versionRepository.updateById(version);
    }

    private int getNextVersionNum(String tripId) {
        TripVersion latest = versionRepository.findLatestByTripId(tripId);
        return latest != null ? latest.getVersionNum() + 1 : 1;
    }

    private void copyActivities(String sourceVersionId, String targetVersionId) {
        List<Activity> activities = activityRepository.findByVersionId(sourceVersionId);
        if (activities != null && !activities.isEmpty()) {
            for (Activity act : activities) {
                act.setId(UUID.randomUUID().toString().replace("-", ""));
                act.setVersionId(targetVersionId);
            }
            activityRepository.insertBatch(activities);
        }
    }

    public TripVersionResponse toResponse(TripVersion version) {
        List<Map<String, Object>> activities = jsonUtils.fromJson(version.getActivities(), List.class);
        
        if (activities != null) {
            String seed = version.getTripId();
            for (Map<String, Object> act : activities) {
                String poiName = (String) act.getOrDefault("poiName", act.getOrDefault("poi_name", act.get("name")));
                String actType = (String) act.getOrDefault("activityType", act.getOrDefault("activity_type", act.get("type")));
                Object existing = act.get("slogan");
                String slogan = existing == null ? null : existing.toString();
                // 缺失或为泛化文案（如「游览西湖」「雷峰塔」）时，按景点生成个性化签名替换
                // rest 休息节点无个性化签名需求（poiName 为「中场休息」等标签，生成词库文案无意义）
                if (!"rest".equals(actType) && sloganService.isGeneric(slogan, poiName)) {
                    String generated = sloganService.generateSlogan(poiName, actType, seed);
                    if (generated != null) {
                        act.put("slogan", generated);
                    }
                }
            }
        }

        return TripVersionResponse.builder()
                .id(version.getId())
                .tripId(version.getTripId())
                .versionNum(version.getVersionNum())
                .parentVersionId(version.getParentVersionId())
                .activities((List<Object>) (List<?>) activities)
                .routes(jsonUtils.fromJson(version.getRoutes(), List.class))
                .conflicts(jsonUtils.fromJson(version.getConflicts(), List.class))
                .stats(jsonUtils.fromJson(version.getStats(), Map.class))
                .feedback(version.getFeedback())
                .solverMeta(jsonUtils.fromJson(version.getSolverMeta(), Map.class))
                .status(version.getStatus())
                .createdAt(version.getCreatedAt())
                .build();
    }
}