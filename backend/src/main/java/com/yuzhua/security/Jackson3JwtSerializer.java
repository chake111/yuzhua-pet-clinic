package com.yuzhua.security;

import io.jsonwebtoken.io.AbstractSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.util.Map;

/**
 * 基于 Jackson 3 的 JJWT 序列化器 SPI 实现，供 ServiceLoader 自动发现。
 */
public class Jackson3JwtSerializer extends AbstractSerializer<Map<String, ?>> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected void doSerialize(Map<String, ?> value, OutputStream out) {
        MAPPER.writeValue(out, value);
    }
}
