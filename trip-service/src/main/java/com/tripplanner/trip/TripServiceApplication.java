package com.tripplanner.trip;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Trip Service 启动类
 */
@SpringBootApplication(scanBasePackages = "com.tripplanner")
@EnableCaching
@EnableAsync
@EnableFeignClients(basePackages = "com.tripplanner.trip.client")
@MapperScan("com.tripplanner.trip.repository")
public class TripServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripServiceApplication.class, args);
    }
}