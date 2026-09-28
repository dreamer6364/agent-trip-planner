package com.tripplanner.trip.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.Retryer;
import feign.codec.Encoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Feign 客户端配置
 * LLM 行程规划调用耗时较长，需显式加大读超时；
 * 强制 JSON body 按 UTF-8 编码，避免中文 rawInput 变 ?
 */
@Configuration
public class FeignConfig {

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(
                10, TimeUnit.SECONDS,   // connectTimeout
                300, TimeUnit.SECONDS,  // readTimeout: LLM 多日规划可能超过 60s
                true
        );
    }

    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Encoder utf8JsonEncoder(ObjectMapper objectMapper) {
        return new Encoder() {
            @Override
            public void encode(Object object, Type bodyType, feign.RequestTemplate template)
                    throws feign.codec.EncodeException {
                try {
                    byte[] bytes = objectMapper.writeValueAsBytes(object);
                    template.body(bytes, StandardCharsets.UTF_8);
                    template.header("Content-Type",
                            MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                } catch (Exception e) {
                    throw new feign.codec.EncodeException(e.getMessage(), e);
                }
            }
        };
    }
}
