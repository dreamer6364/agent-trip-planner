package com.tripplanner.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * JSON 工具类
 * 统一使用 Spring 管理的 ObjectMapper
 */
@Slf4j
@Component
public final class JsonUtils {

    private final ObjectMapper objectMapper;

    public JsonUtils(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对象转 JSON 字符串
     */
    public String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("JSON 序列化失败: {}", e.getMessage(), e);
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /**
     * JSON 字符串转对象
     */
    public <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (IOException e) {
            log.error("JSON 反序列化失败: {}", e.getMessage(), e);
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }

    /**
     * JSON 字符串转复杂类型（如 List<Map<String, Object>>）
     */
    public <T> T fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (IOException e) {
            log.error("JSON 反序列化失败: {}", e.getMessage(), e);
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }

    /**
     * 判断是否为合法 JSON
     */
    public boolean isValidJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            objectMapper.readTree(json);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}