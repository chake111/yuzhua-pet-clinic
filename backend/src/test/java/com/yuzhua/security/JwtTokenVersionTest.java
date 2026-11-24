package com.yuzhua.security;

import com.yuzhua.props.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 令牌版本要真的走完"签发 → 解析"这一条链路。
 *
 * <p>{@link AccountGuardTest} 用替身验证的是判定规则；这里验证 JwtUtils 确实把版本写进
 * claims 又读了回来。两者缺一不可：只测规则会漏掉"版本根本没进令牌"，
 * 只测往返会漏掉"版本不一致时没拒绝"。
 */
class JwtTokenVersionTest {

    private static final String SECRET = "t1a-test-secret-at-least-32-bytes-long!!";

    private static JwtUtils utils() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(SECRET);
        return new JwtUtils(props);
    }

    /** 升级前签发的令牌形态：没有任何版本声明 */
    private static String legacyToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("7")
                .claim("typ", LoginUser.TYP_CLIENT)
                .claim("role", LoginUser.ROLE_USER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void versionSurvivesSignAndParse() {
        LoginUser user = new LoginUser();
        user.setId(7L);
        user.setTyp(LoginUser.TYP_CLIENT);
        user.setRole(LoginUser.ROLE_USER);
        user.setTokenVersion(5);

        LoginUser parsed = utils().parse(utils().create(user));
        assertEquals(5, parsed.getTokenVersion());
    }

    @Test
    void legacyTokenWithoutVersionParsesAsNull() {
        // null 会被 AccountGuard 判为已撤销，正是升级后旧令牌的处置方式
        assertNull(utils().parse(legacyToken()).getTokenVersion());
    }
}
