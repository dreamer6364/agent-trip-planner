package com.tripplanner.trip.client;

import com.tripplanner.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Plan Service 地图能力客户端（地理编码 / 路线规划）
 * 用于替换景点后重算邻接段真实距离与时长
 */
@FeignClient(name = "plan-service-map", url = "${plan.service.url:http://localhost:8083}")
public interface PlanMapClient {

    /**
     * 路线规划：返回 success/mode/distance(米)/duration(秒) 等
     */
    @PostMapping("/api/plan/map/route")
    ApiResponse<Map<String, Object>> route(@RequestBody Map<String, Object> body);

    /**
     * 地理编码：名称 → lat/lng
     */
    @PostMapping("/api/plan/geocode")
    ApiResponse<Map<String, Object>> geocode(@RequestBody Map<String, Object> body);
}
