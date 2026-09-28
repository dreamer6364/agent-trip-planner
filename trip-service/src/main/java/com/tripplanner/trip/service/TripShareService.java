package com.tripplanner.trip.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.dto.request.ShareTripRequest;
import com.tripplanner.trip.dto.response.ShareResponse;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 行程分享服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TripShareService {

    private final TripRepository tripRepository;
    private final JsonUtils jsonUtils;

    /**
     * 生成分享链接
     */
    public ShareResponse createShare(String userId, String tripId, ShareTripRequest request) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权分享该行程");
        }

        // 生成分享 token
        String shareToken = generateShareToken();
        LocalDateTime expiresAt = request.getExpireDays() != null 
                ? LocalDateTime.now().plusDays(request.getExpireDays()) 
                : null;

        trip.setIsPublic(true);
        trip.setShareToken(shareToken);
        trip.setViewCount(0);

        // 存储分享配置 (JSON 扩展字段) — 允许 null 值，勿用 Map.of
        Map<String, Object> shareConfig = new java.util.LinkedHashMap<>();
        shareConfig.put("password", request.getPassword());
        shareConfig.put("allowExport", request.getAllowExport());
        shareConfig.put("shareTitle", request.getShareTitle());
        shareConfig.put("shareDescription", request.getShareDescription());
        shareConfig.put("expiresAt", expiresAt);
        shareConfig.put("createdAt", LocalDateTime.now());
        trip.setPreferences(mergeShareConfig(trip.getPreferences(), shareConfig));

        tripRepository.updateById(trip);

        String baseUrl = "https://tripplanner.example.com"; // 实际应从配置读取
        String shareUrl = baseUrl + "/shared/" + shareToken;

        log.info("生成分享链接: tripId={}, shareToken={}", tripId, shareToken);

        return ShareResponse.builder()
                .shareToken(shareToken)
                .shareUrl(shareUrl)
                .isPublic(true)
                .expiresAt(expiresAt != null ? expiresAt.toString() : null)
                .viewCount(0)
                .build();
    }

    /**
     * 撤销分享
     */
    public void revokeShare(String userId, String tripId) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权操作该行程");
        }

        trip.setIsPublic(false);
        trip.setShareToken(null);
        tripRepository.updateById(trip);

        log.info("撤销分享: tripId={}", tripId);
    }

    /**
     * 获取分享信息
     */
    public ShareResponse getShareInfo(String userId, String tripId) {
        Trip trip = tripRepository.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程", tripId);
        }

        if (!trip.getUserId().equals(userId)) {
            throw BizException.forbidden("无权查看该行程");
        }

        if (!Boolean.TRUE.equals(trip.getIsPublic()) || trip.getShareToken() == null) {
            throw BizException.notFound("该行程未分享");
        }

        String baseUrl = "https://tripplanner.example.com";
        return ShareResponse.builder()
                .shareToken(trip.getShareToken())
                .shareUrl(baseUrl + "/shared/" + trip.getShareToken())
                .isPublic(true)
                .expiresAt(getExpiresAt(trip))
                .viewCount(trip.getViewCount())
                .build();
    }

    /**
     * 验证分享访问 (公开访问时调用)
     */
    public Trip validateShareAccess(String token, String password) {
        Trip trip = tripRepository.findByShareToken(token);
        if (trip == null || !Boolean.TRUE.equals(trip.getIsPublic())) {
            throw BizException.notFound("分享链接无效或已过期");
        }

        // 检查过期时间
        String expiresAt = getExpiresAt(trip);
        if (expiresAt != null && LocalDateTime.parse(expiresAt).isBefore(LocalDateTime.now())) {
            throw BizException.notFound("分享链接已过期");
        }

        // 检查密码
        String storedPassword = getSharePassword(trip);
        if (storedPassword != null && !storedPassword.equals(password)) {
            throw BizException.forbidden("分享链接需要密码");
        }

        return trip;
    }

    private String generateShareToken() {
        // URL-safe base64, 32 字符
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes())
                .substring(0, 32);
    }

    private String getExpiresAt(Trip trip) {
        try {
            Map<String, Object> prefs = jsonUtils.fromJson(trip.getPreferences(), Map.class);
            Map<String, Object> shareConfig = (Map<String, Object>) prefs.get("shareConfig");
            if (shareConfig != null) {
                return (String) shareConfig.get("expiresAt");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getSharePassword(Trip trip) {
        try {
            Map<String, Object> prefs = jsonUtils.fromJson(trip.getPreferences(), Map.class);
            Map<String, Object> shareConfig = (Map<String, Object>) prefs.get("shareConfig");
            if (shareConfig != null) {
                return (String) shareConfig.get("password");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String mergeShareConfig(String existingPrefs, Map<String, Object> shareConfig) {
        Map<String, Object> prefs = jsonUtils.fromJson(existingPrefs, Map.class);
        if (prefs == null) {
            prefs = new java.util.LinkedHashMap<>();
        }
        prefs.put("shareConfig", shareConfig);
        return jsonUtils.toJson(prefs);
    }
}