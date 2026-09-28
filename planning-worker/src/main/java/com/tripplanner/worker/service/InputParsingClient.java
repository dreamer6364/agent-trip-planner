package com.tripplanner.worker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InputParsingClient {

    @Value("${plan-service.url:http://localhost:8083}")
    private String planServiceUrl;

    private final WebClient webClient;

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseInput(String rawInput, String timeStart, String timeEnd, String transportMode) {
        try {
            Map<String, String> request = Map.of(
                    "rawInput", rawInput != null ? rawInput : "",
                    "timeStart", timeStart != null ? timeStart : "",
                    "timeEnd", timeEnd != null ? timeEnd : "",
                    "transportMode", transportMode != null ? transportMode : "mixed"
            );

            Map<String, Object> response = webClient.post()
                    .uri(planServiceUrl + "/api/agent/parse")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                log.info("输入解析成功: places={}, meals={}",
                        countItems(response, "places"), countItems(response, "meals"));
                return response;
            } else {
                log.warn("输入解析返回失败: {}", response);
                return buildFallbackResponse(rawInput, timeStart, timeEnd, transportMode);
            }
        } catch (Exception e) {
            log.error("调用解析接口失败: {}", e.getMessage());
            return buildFallbackResponse(rawInput, timeStart, timeEnd, transportMode);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> planItinerary(Map<String, Object> request) {
        try {
            Map<String, Object> response = webClient.post()
                    .uri(planServiceUrl + "/api/agent/plan-itinerary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(120))
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                log.info("LLM行程规划成功: activities={}", countItems(response, "activities"));
                return response;
            } else {
                log.warn("LLM行程规划返回失败: {}", response);
                return Map.of("success", false, "activities", (Object) List.of());
            }
        } catch (Exception e) {
            log.error("调用行程规划接口失败: {}", e.getMessage());
            return Map.of("success", false, "activities", (Object) List.of());
        }
    }

    private int countItems(Map<String, Object> response, String key) {
        Object val = response.get(key);
        if (val instanceof List<?> l) return l.size();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFallbackResponse(String rawInput, String timeStart, String timeEnd, String transportMode) {
        return Map.of(
                "success", false,
                "places", (Object) List.of(),
                "meals", (Object) List.of(
                        Map.of("type", "lunch", "durationMin", 90),
                        Map.of("type", "dinner", "durationMin", 90)
                ),
                "rawInput", rawInput != null ? rawInput : "",
                "timeStart", timeStart != null ? timeStart : "",
                "timeEnd", timeEnd != null ? timeEnd : "",
                "transportMode", transportMode != null ? transportMode : "mixed",
                "city", inferCityFromInput(rawInput)
        );
    }

    private String inferCityFromInput(String rawInput) {
        if (rawInput == null) return "";
        String city = com.tripplanner.common.util.CityOwnershipUtils.extractCity(rawInput);
        return city == null ? "" : city;
    }
}
