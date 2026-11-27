package com.yuzhua.security;

import io.jsonwebtoken.io.AbstractSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.util.Map;

/**
 * 通过 `META-INF/services/io.jsonwebtoken.io.Serializer` 注册的 JJWT 序列化 SPI，
 * 在 Spring Boot 4 环境下以 Jackson 3 替代 `jjwt-jackson`，避免引入 Jackson 2 传递依赖。
 * <p>
 * 复用无状态、线程安全的默认 `JsonMapper`（`MAPPER`）并发写出 JWT 头与载荷；
 * 若序列化失败，`AbstractSerializer` 会将底层异常包装为 `SerializationException`，确保签发 fail-closed 中止。
 */
public class Jackson3JwtSerializer extends AbstractSerializer<Map<String, ?>> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected void doSerialize(Map<String, ?> value, OutputStream out) {
        MAPPER.writeValue(out, value);
    }
}
