package com.tripplanner.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Planning Worker 启动类
 */
@SpringBootApplication(
    scanBasePackages = "com.tripplanner",
    exclude = {KafkaAutoConfiguration.class}
)
@EnableCaching
@EnableAsync
@MapperScan("com.tripplanner.worker.repository")
public class PlanningWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanningWorkerApplication.class, args);
    }
}