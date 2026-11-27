package com.yuzhua.security;

import io.jsonwebtoken.io.AbstractDeserializer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.Reader;
import java.util.Map;

/**
 * 通过 `META-INF/services/io.jsonwebtoken.io.Deserializer` 注册的 JJWT 反序列化 SPI，
 * 在 Spring Boot 4 环境下以 Jackson 3 替代 `jjwt-jackson`，避免引入 Jackson 2 传递依赖。
 * <p>
 * 复用无状态、线程安全的默认 `JsonMapper` 与 `CLAIMS_TYPE` 并发解析 JWT 头与载荷；
 * 仅接受顶层 JSON 对象（{@code Map<String, Object>}），畸形 JSON 或非对象载荷由 `AbstractDeserializer`
 * 统一转为 `DeserializationException` 向上抛出，保证验签阶段 fail-closed 拒绝非法令牌。
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
