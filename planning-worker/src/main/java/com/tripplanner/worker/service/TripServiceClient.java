package com.tripplanner.worker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * TripService 内部 API 客户端
 * 供增量重规划加载基础版本（活动/路线/行程信息）
 *
 * @author TripForge Team
 * @since 1.15.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripServiceClient {

    @Value("${trip-service.url:http://localhost:8082}")
    private String tripServiceUrl;

    private final WebClient webClient;

    /**
     * 获取版本详情
     *
     * @param tripId    行程 ID
     * @param versionId 版本 ID
     * @return 版本详情（含 activities/routes/stats/rawInput/parsedInput/timeStart/timeEnd）；失败返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getVersionDetail(String tripId, String versionId) {
        if (tripId == null || versionId == null) {
            return null;
        }
        try {
            Map<String, Object> response = webClient.get()
                    .uri(tripServiceUrl + "/api/internal/trips/{tripId}/versions/{versionId}", tripId, versionId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (response == null || response.get("activities") == null) {
                log.warn("版本详情为空: tripId={}, versionId={}", tripId, versionId);
                return null;
            }
            return response;
        } catch (Exception e) {
            log.error("获取版本详情失败: tripId={}, versionId={}, error={}", tripId, versionId, e.getMessage());
            return null;
        }
    }
}
