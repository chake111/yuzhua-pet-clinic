package com.yuzhua.security;

import io.jsonwebtoken.io.AbstractDeserializer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.Reader;
import java.util.Map;

/**
 * 基于 Jackson 3 的 JJWT 反序列化器 SPI 实现，供 ServiceLoader 自动发现。
 */
public class Jackson3JwtDeserializer extends AbstractDeserializer<Map<String, ?>> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    @Override
    protected Map<String, ?> doDeserialize(Reader reader) {
        return MAPPER.readValue(reader, CLAIMS_TYPE);
    }
}
