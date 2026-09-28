package com.tripplanner.plan;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Plan Service 启动类
 */
@SpringBootApplication(scanBasePackages = "com.tripplanner")
@EnableCaching
@EnableAsync
@MapperScan("com.tripplanner.plan.repository")
public class PlanServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanServiceApplication.class, args);
    }
}