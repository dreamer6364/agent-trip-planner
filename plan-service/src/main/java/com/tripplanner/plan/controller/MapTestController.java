package com.tripplanner.plan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.plan.dto.response.RouteResponse;
import com.tripplanner.plan.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 地理编码/路线测试控制器
 * 仅用于开发调试
 */
@Tag(name = "地图 API 测试", description = "地理编码、路线规划测试端点")
@RestController
@RequestMapping("/api/plan/map")
@RequiredArgsConstructor
public class MapTestController {

    private final RouteService routeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "测试路线规划")
    @PostMapping("/route")
    public ResponseEntity<ApiResponse<RouteResponse>> testRoute(
            @RequestBody Map<String, Object> body
    ) {
        double originLat = ((Number) body.get("originLat")).doubleValue();
        double originLng = ((Number) body.get("originLng")).doubleValue();
        double destLat = ((Number) body.get("destLat")).doubleValue();
        double destLng = ((Number) body.get("destLng")).doubleValue();
        String mode = (String) body.getOrDefault("mode", "drive");
        String city = (String) body.get("city");
        RouteResponse response = routeService.route(originLat, originLng, destLat, destLng, mode, city);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "测试距离矩阵")
    @PostMapping("/distance-matrix")
    public ResponseEntity<ApiResponse<RouteService.DistanceMatrixResponse>> testDistanceMatrix(
            @RequestBody Map<String, Object> body
    ) {
        List<RouteService.Point> points = objectMapper.convertValue(
                body.get("points"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, RouteService.Point.class)
        );
        String mode = (String) body.getOrDefault("mode", "drive");
        String city = (String) body.get("city");
        RouteService.DistanceMatrixResponse response = routeService.distanceMatrix(points, mode, city);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}