package com.tripplanner.trip.controller;

import com.tripplanner.common.security.JwtAuthenticationToken;
import com.tripplanner.common.annotation.RequirePermission;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.response.Meta;
import com.tripplanner.trip.dto.request.CreateTripRequest;
import com.tripplanner.trip.dto.request.UpdateTripRequest;
import com.tripplanner.trip.dto.response.TripListResponse;
import com.tripplanner.trip.dto.response.TripResponse;
import com.tripplanner.trip.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 行程控制器
 */
@Tag(name = "行程管理", description = "行程 CRUD、规划触发、公开访问")
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @Operation(summary = "创建行程并触发规划")
    @PostMapping
    @RequirePermission("trip:write")
    public ResponseEntity<ApiResponse<TripResponse>> createTrip(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @Valid @RequestBody CreateTripRequest request
    ) {
        TripResponse trip = tripService.createTrip(auth.getUserId(), request);
        return ResponseEntity.status(201).body(ApiResponse.success(trip));
    }

    @Operation(summary = "分页查询我的行程")
    @GetMapping
    @RequirePermission("trip:read")
    public ResponseEntity<ApiResponse<TripListResponse>> listTrips(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status
    ) {
        TripListResponse result = tripService.listTrips(auth.getUserId(), page, size, keyword, status);
        return ResponseEntity.ok(ApiResponse.success(result, Meta.builder()
                .page(page)
                .size(size)
                .total(result.getTotal())
                .build()));
    }

    @Operation(summary = "获取行程详情 (含最新版本)")
    @GetMapping("/{id}")
    @RequirePermission(value = "trip:read", resourceId = "#id")
    public ResponseEntity<ApiResponse<TripResponse>> getTrip(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @Parameter(description = "行程 ID") @PathVariable String id
    ) {
        TripResponse trip = tripService.getTrip(auth.getUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(trip));
    }

    @Operation(summary = "更新行程基本信息")
    @PutMapping("/{id}")
    @RequirePermission(value = "trip:write", resourceId = "#id")
    public ResponseEntity<ApiResponse<TripResponse>> updateTrip(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @Valid @RequestBody UpdateTripRequest request
    ) {
        TripResponse trip = tripService.updateTrip(auth.getUserId(), id, request);
        return ResponseEntity.ok(ApiResponse.success(trip));
    }

    @Operation(summary = "删除行程 (软删除)")
    @DeleteMapping("/{id}")
    @RequirePermission(value = "trip:delete", resourceId = "#id")
    public ResponseEntity<ApiResponse<Void>> deleteTrip(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id
    ) {
        tripService.deleteTrip(auth.getUserId(), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "触发/重新规划", description = "variant=true 时为换版规划：主题不变、排除基准版本已用 POI，生成内容不同的新路线")
    @PostMapping("/{id}/plan")
    @RequirePermission(value = "trip:write", resourceId = "#id")
    public ResponseEntity<ApiResponse<Map<String, String>>> triggerPlanning(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @RequestParam(required = false) String baseVersionId,
            @RequestParam(required = false) Boolean variant
    ) {
        tripService.triggerPlanning(auth.getUserId(), id, baseVersionId, Boolean.TRUE.equals(variant));
        return ResponseEntity.accepted().body(ApiResponse.success(Map.of(
                "message", "规划任务已提交，请通过 WebSocket 等待结果"
        )));
    }

    @Operation(summary = "保存Agent规划结果")
    @PostMapping("/{id}/activities")
    @RequirePermission(value = "trip:write", resourceId = "#id")
    public ResponseEntity<ApiResponse<Map<String, String>>> saveActivities(
            @AuthenticationPrincipal JwtAuthenticationToken auth,
            @PathVariable String id,
            @RequestBody Map<String, Object> body
    ) {
        tripService.saveActivities(auth.getUserId(), id, body);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "保存成功")));
    }

    @Operation(summary = "查询公开行程列表 (无需登录)")
    @GetMapping("/public")
    public ResponseEntity<ApiResponse<TripListResponse>> listPublicTrips(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        TripListResponse result = tripService.listPublicTrips(page, size);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @Operation(summary = "通过分享链接访问行程 (无需登录)")
    @GetMapping("/shared/{token}")
    public ResponseEntity<ApiResponse<TripResponse>> getByShareToken(
            @Parameter(description = "分享 Token") @PathVariable String token
    ) {
        TripResponse trip = tripService.getByShareToken(token);
        return ResponseEntity.ok(ApiResponse.success(trip));
    }
}
