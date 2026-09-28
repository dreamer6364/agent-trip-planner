package com.tripplanner.trip.client;

import com.tripplanner.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

/**
 * Plan Service Feign Client
 * 调用 plan-service 的 Agent 规划接口
 */
@FeignClient(name = "plan-service", url = "${plan.service.url:http://localhost:8083}", path = "/api/agent")
public interface PlanServiceClient {

    @PostMapping("/plan")
    ApiResponse<Map<String, Object>> planTrip(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> request
    );

    @PostMapping("/parse")
    ApiResponse<Map<String, Object>> parseInput(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> request
    );

    @PostMapping("/plan-itinerary")
    ApiResponse<Map<String, Object>> planItinerary(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> request
    );

    @GetMapping("/health")
    ApiResponse<Map<String, Object>> health();
}