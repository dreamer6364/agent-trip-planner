package com.tripplanner.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Auth Service 启动类
 */
@SpringBootApplication(scanBasePackages = "com.tripplanner", exclude = {
    RedisRepositoriesAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
@EnableCaching
@EnableAsync
@MapperScan(value = "com.tripplanner.auth.repository", factoryBean = org.mybatis.spring.mapper.MapperFactoryBean.class)
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}