package com.tripplanner.auth.service;

import com.tripplanner.auth.dto.request.UpdatePreferenceRequest;
import com.tripplanner.auth.dto.response.UserPreferenceResponse;
import com.tripplanner.auth.entity.UserPreference;
import com.tripplanner.auth.repository.UserPreferenceRepository;
import com.tripplanner.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserPreferenceService {

    private final UserPreferenceRepository preferenceRepository;
    private final JsonUtils jsonUtils;

    /**
     * 初始化默认偏好(注册时调用)
     */
    public void initDefaultPreference(String userId) {
        UserPreference preference = new UserPreference();
        preference.setUserId(userId);
        preference.setVisitDurationDefaults(jsonUtils.toJson(Map.of(
                "scenic", 120, "museum", 180, "park", 90, "temple", 60
        )));
        preference.setMealDurations(jsonUtils.toJson(Map.of(
                "breakfast", 60, "lunch", 90, "dinner", 120
        )));
        preference.setActiveWindow(jsonUtils.toJson(Map.of(
                "start", "08:00", "end", "23:00"
        )));
        preference.setBlockedPeriods(jsonUtils.toJson(List.of(
                Map.of("start", "13:00", "end", "14:00")
        )));
        preference.setIntensity("standard");
        preference.setDietaryTags(jsonUtils.toJson(Collections.emptyList()));
        preference.setAccessibility(false);
        preference.setPreferredTransportModes(jsonUtils.toJson(List.of("transit", "walk")));

        preferenceRepository.insert(preference);
    }

    /**
     * 获取用户偏好
     */
    public UserPreferenceResponse getPreference(String userId) {
        UserPreference pref = preferenceRepository.selectById(userId);
        if (pref == null) {
            // 如果不存在，初始化默认
            initDefaultPreference(userId);
            pref = preferenceRepository.selectById(userId);
        }
        return toResponse(pref);
    }

    /**
     * 全量更新偏好
     */
    public UserPreferenceResponse updatePreference(String userId, UpdatePreferenceRequest request) {
        UserPreference pref = preferenceRepository.selectById(userId);
        if (pref == null) {
            pref = new UserPreference();
            pref.setUserId(userId);
        }
        updateEntityFromRequest(pref, request);
        if (pref.getUserId() != null && preferenceRepository.selectById(pref.getUserId()) != null) {
            preferenceRepository.updateById(pref);
        } else {
            preferenceRepository.insert(pref);
        }
        return toResponse(pref);
    }

    /**
     * 部分更新偏好
     */
    public UserPreferenceResponse patchPreference(String userId, UpdatePreferenceRequest request) {
        UserPreference pref = preferenceRepository.selectById(userId);
        if (pref == null) {
            initDefaultPreference(userId);
            pref = preferenceRepository.selectById(userId);
        }
        updateEntityFromRequest(pref, request);
        preferenceRepository.updateById(pref);
        return toResponse(pref);
    }

    private void updateEntityFromRequest(UserPreference pref, UpdatePreferenceRequest request) {
        if (request.getVisitDurationDefaults() != null) {
            pref.setVisitDurationDefaults(jsonUtils.toJson(request.getVisitDurationDefaults()));
        }
        if (request.getMealDurations() != null) {
            pref.setMealDurations(jsonUtils.toJson(request.getMealDurations()));
        }
        if (request.getActiveWindow() != null) {
            pref.setActiveWindow(jsonUtils.toJson(Map.of(
                    "start", request.getActiveWindow().getStart(),
                    "end", request.getActiveWindow().getEnd()
            )));
        }
        if (request.getBlockedPeriods() != null) {
            pref.setBlockedPeriods(jsonUtils.toJson(request.getBlockedPeriods().stream()
                    .map(p -> Map.of("start", p.getStart(), "end", p.getEnd()))
                    .toList()));
        }
        if (request.getIntensity() != null) {
            pref.setIntensity(request.getIntensity());
        }
        if (request.getDietaryTags() != null) {
            pref.setDietaryTags(jsonUtils.toJson(request.getDietaryTags()));
        }
        if (request.getAccessibility() != null) {
            pref.setAccessibility(request.getAccessibility());
        }
        if (request.getHomeLocation() != null) {
            pref.setHomeLocation(request.getHomeLocation());
        }
        if (request.getWorkLocation() != null) {
            pref.setWorkLocation(request.getWorkLocation());
        }
        if (request.getPreferredTransportModes() != null) {
            pref.setPreferredTransportModes(jsonUtils.toJson(request.getPreferredTransportModes()));
        }
    }

    private UserPreferenceResponse toResponse(UserPreference pref) {
        return UserPreferenceResponse.builder()
                .userId(pref.getUserId())
                .visitDurationDefaults(jsonUtils.fromJson(pref.getVisitDurationDefaults(), Map.class))
                .mealDurations(jsonUtils.fromJson(pref.getMealDurations(), Map.class))
                .activeWindow(jsonUtils.fromJson(pref.getActiveWindow(), UserPreferenceResponse.ActiveWindowResponse.class))
                .blockedPeriods(jsonUtils.fromJson(pref.getBlockedPeriods(), List.class))
                .intensity(pref.getIntensity())
                .dietaryTags(jsonUtils.fromJson(pref.getDietaryTags(), List.class))
                .accessibility(pref.getAccessibility())
                .homeLocation(pref.getHomeLocation())
                .workLocation(pref.getWorkLocation())
                .preferredTransportModes(jsonUtils.fromJson(pref.getPreferredTransportModes(), List.class))
                .updatedAt(pref.getUpdatedAt())
                .build();
    }
}