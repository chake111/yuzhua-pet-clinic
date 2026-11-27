package com.yuzhua.security;

import com.yuzhua.props.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * JWT 签发与解析。HMAC-SHA（jjwt 按密钥长度自动选 HS256/384/512），
 * claims 携带签发时的身份；AccountGuard 再核对账号状态、后台角色和医生绑定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private static final String CLAIM_TYP = "typ";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_AVATAR = "avatar";
    private static final String CLAIM_DOCTOR_ID = "doctorId";
    private static final String CLAIM_VER = "ver";

    // 原因: 历史示例密钥已公开，配置命中时在启动自检阶段直接失败阻断启动
    private static final Set<String> RETIRED_DEFAULTS = Set.of(
            "pet-clinic-hs256-secret-please-change-in-production-2026",
            "pet-clinic-default-dev-secret-key-change-me");

    private final AppProperties props;

    @PostConstruct
    void validateSecret() {
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "yuzhua.jwt.secret 未配置：请生成随机密钥并通过环境变量 JWT_SECRET 注入（不少于 32 字节）");
        }
        if (RETIRED_DEFAULTS.contains(secret.trim())) {
            throw new IllegalStateException(
                    "yuzhua.jwt.secret 仍是仓库内置的示例密钥：该密钥已公开，必须更换后再启动");
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < 32) {
            throw new IllegalStateException(
                    "yuzhua.jwt.secret 长度不足：当前 " + length + " 字节，至少需要 32 字节");
        }
    }

    private SecretKey key() {
        String secret = props.getJwt().getSecret();
        if (secret == null) {
            throw new IllegalStateException("yuzhua.jwt.secret 未配置");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("yuzhua.jwt.secret 长度必须不小于 32 字节");
        }
        return Keys.hmacShaKeyFor(raw);
    }

    /** 按账号身份与令牌版本签发 HMAC-SHA JWT；过期时间取自 yuzhua.jwt.expire-seconds。 */
    public String create(LoginUser u) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim(CLAIM_TYP, u.getTyp())
                .claim(CLAIM_ROLE, u.getRole())
                .claim(CLAIM_NAME, u.getName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(props.getJwt().getExpireSeconds())))
                .signWith(key());
        if (u.getAvatar() != null) {
            builder.claim(CLAIM_AVATAR, u.getAvatar());
        }
        if (u.getDoctorId() != null) {
            builder.claim(CLAIM_DOCTOR_ID, u.getDoctorId());
        }
        // 原因: 缺版本的令牌无法证明未被撤销，AccountGuard 一律按失效拒绝
        if (u.getTokenVersion() != null) {
            builder.claim(CLAIM_VER, u.getTokenVersion());
        }
        return builder.compact();
    }

    /**
     * 解析并校验 HMAC-SHA JWT 签名与过期时间：空串、缺 {@code exp} 声明或验签/过期异常统一返回 {@code null}，
     * 由调用方决定 401 或放行；本方法仅提取 {@code ver} 声明，不校验账号停用或令牌撤销（由 {@link AccountGuard} 核验）。
     */
    public @Nullable LoginUser parse(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims c = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
            if (c.getExpiration() == null) {
                return null;
            }
            LoginUser u = new LoginUser();
            u.setExpiresAt(c.getExpiration().toInstant());
            u.setId(Long.valueOf(c.getSubject()));
            u.setTyp(c.get(CLAIM_TYP, String.class));
            u.setRole(c.get(CLAIM_ROLE, String.class));
            u.setName(c.get(CLAIM_NAME, String.class));
            u.setAvatar(c.get(CLAIM_AVATAR, String.class));
            Number did = c.get(CLAIM_DOCTOR_ID, Number.class);
            u.setDoctorId(did == null ? null : did.longValue());
            Number ver = c.get(CLAIM_VER, Number.class);
            u.setTokenVersion(ver == null ? null : ver.intValue());
            return u;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 剥离请求头中的令牌前缀（取值自 {@code yuzhua.jwt.prefix}）并返回去首尾空白的裸 token：
     * 入参为 {@code null} 或全空白时返回 {@code null}；未匹配该前缀时不报错，原样返回 {@code header.trim()}，
     * 本方法不做签名或过期校验。
     */
    public @Nullable String strip(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String prefix = props.getJwt().getPrefix();
        return header.startsWith(prefix) ? header.substring(prefix.length()).trim() : header.trim();
    }
}
