package com.tripplanner.trip.controller;

import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.trip.dto.request.GetAlternativesRequest;
import com.tripplanner.trip.dto.request.ReplaceActivityRequest;
import com.tripplanner.trip.dto.response.AlternativesResponse;
import com.tripplanner.trip.service.AlternativeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 景点备选项控制器
 * 提供景点推荐和替换功能
 */
@Slf4j
@Tag(name = "景点备选项", description = "获取景点推荐、替换景点")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class AlternativeController {

    private final AlternativeService alternativeService;

    /**
     * 获取景点备选项
     */
    @Operation(summary = "获取景点备选项", description = "根据当前景点推荐相似的备选景点")
    @PostMapping("/{tripId}/alternatives")
    public ResponseEntity<ApiResponse<AlternativesResponse>> getAlternatives(
            @PathVariable String tripId,
            @RequestBody GetAlternativesRequest request
    ) {
        log.info("收到获取备选项请求: tripId={}, activityName={}", tripId, request.getActivityName());
        request.setTripId(tripId);
        AlternativesResponse response = alternativeService.getAlternatives(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 替换景点
     */
    @Operation(summary = "替换景点", description = "用备选项替换原景点，并自动更新距离和时间")
    @PostMapping("/{tripId}/replace")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replaceActivity(
            @PathVariable String tripId,
            @RequestBody ReplaceActivityRequest request
    ) {
        log.info("收到替换景点请求: tripId={}, activityId={}, activityName={}, seq={}, alternativeName={}", 
                tripId, request.getActivityId(), request.getActivityName(), request.getSeq(), request.getAlternativeName());
        request.setTripId(tripId);
        Map<String, Object> result = alternativeService.replaceActivity(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
