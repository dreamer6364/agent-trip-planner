package com.tripplanner.trip.client;

import feign.Request;
import org.springframework.context.annotation.Bean;

/**
 * 用户信息查询 Feign 客户端专属配置
 * 公开列表展示为尽力而为（fail-open），短超时快速降级，避免 auth-service 抖动拖慢行程列表
 */
public class UserClientFeignConfig {

    @Bean
    public Request.Options userClientOptions() {
        // connectTimeout=1000ms, readTimeout=3000ms, retryOnConnectionError=false
        return new Request.Options(1000, 3000, false);
    }
}
