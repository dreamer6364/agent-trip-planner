package com.tripplanner.trip.client;

import com.tripplanner.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Auth Service Feign Client
 * 调用 auth-service 的跨服务公开接口（公开行程卡片作者显示名查询）
 */
@FeignClient(
        name = "auth-service",
        url = "${auth.service.url:http://localhost:8081}",
        path = "/api/users",
        configuration = UserClientFeignConfig.class
)
public interface AuthUserClient {

    /**
     * 批量获取用户显示名（id -> 昵称），仅返回昵称非空且状态有效的用户
     */
    @GetMapping("/names")
    ApiResponse<Map<String, String>> getUserNames(@RequestParam("ids") List<String> ids);
}
