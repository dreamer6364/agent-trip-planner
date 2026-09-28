package com.tripplanner.plan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * LLM Client 配置
 * 支持 OpenAI 兼容接口
 */
@Configuration
public class LlmConfig {

    @Value("${llm.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:glm-4-flash}")
    private String model;

    @Value("${llm.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${llm.max-tokens:2000}")
    private int maxTokens;

    @Value("${llm.temperature:0.1}")
    private double temperature;

    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }

    @Bean
    public WebClient llmWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}