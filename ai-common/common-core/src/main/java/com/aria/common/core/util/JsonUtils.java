package com.aria.common.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具类（Jackson 封装，全局单例 ObjectMapper）。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {}

    /**
     * 对象序列化为 JSON 字符串。
     */
    public static String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为对象。
     */
    public static <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 反序列化失败: " + clazz.getSimpleName(), e);
        }
    }

    /**
     * JSON 字符串反序列化为对象（支持泛型）。
     */
    public static <T> T parseObject(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 反序列化失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 解析为 Map 失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为 List。
     */
    public static <T> List<T> parseList(String json, Class<T> elementClass) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, elementClass));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 解析为 List 失败", e);
        }
    }

    /**
     * 获取全局 ObjectMapper（只读场景）。
     */
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }
}
