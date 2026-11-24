package com.yuzhua.security;

import com.yuzhua.props.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动自检：密钥缺失、过短或仍是仓库里公开过的示例值时，应用必须起不来。
 *
 * <p>这一类配置错误的共同点是"能跑，但等于没设防"：默认密钥在版本库里，
 * 任何人都能签发合法令牌。所以判定标准是启动失败，而不是日志告警。
 */
class JwtUtilsSecretTest {

    private static JwtUtils utils(String secret) {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(secret);
        return new JwtUtils(props);
    }

    private static String messageOf(String secret) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> utils(secret).validateSecret());
        return e.getMessage();
    }

    @Test
    void rejectMissingSecretAndPointToEnvVar() {
        assertTrue(messageOf(null).contains("JWT_SECRET"));
        assertTrue(messageOf("   ").contains("未配置"));
    }

    @Test
    void rejectShortSecret() {
        assertTrue(messageOf("short-secret").contains("长度不足"));
        // 31 字节仍在边界外
        assertTrue(messageOf("x".repeat(31)).contains("长度不足"));
    }

    @Test
    void rejectRetiredRepositoryDefaults() {
        // 这两个值都曾写在 application.yml / AppProperties 里，已随仓库公开
        assertTrue(messageOf("pet-clinic-hs256-secret-please-change-in-production-2026").contains("示例密钥"));
        assertTrue(messageOf("pet-clinic-default-dev-secret-key-change-me").contains("示例密钥"));
    }

    @Test
    void acceptRandomSecretOfAtLeast32Bytes() {
        assertDoesNotThrow(() -> utils("x".repeat(32)).validateSecret());
        assertDoesNotThrow(() -> utils("4f8c1a2b6d9e0f3a7c5b8d1e2f4a6c9b").validateSecret());
    }
}
