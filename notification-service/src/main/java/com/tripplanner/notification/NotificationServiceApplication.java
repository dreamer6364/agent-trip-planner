package com.tripplanner.notification;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Notification Service 启动类
 */
@SpringBootApplication(scanBasePackages = "com.tripplanner")
@EnableCaching
@EnableAsync
@MapperScan("com.tripplanner.notification.repository")
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}