package com.yuzhua.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手鉴权。
 *
 * <p>浏览器通过 {@code ?token=} 传入 JWT；查询参数为空白时回退到 Authorization 请求头。
 * URL 中的 JWT 可能被代理访问日志记录。REST 鉴权由 {@link AuthInterceptor} 从配置的请求头取值。
 * 握手仅允许宠物主人和医生，并通过 {@link AccountGuard} 回查账号状态。
 *
 * 校验通过后把登录态放进 attributes，handler 从 session.getAttributes() 取，
 * 不在连接期间反复解析 token。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER = "loginUser";
    public static final String ATTR_SESSION_ID = "sessionId";

    private final JwtUtils jwtUtils;
    private final AccountGuard accountGuard;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Map<String, List<String>> query = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams();
        String raw = first(query.get("token"));
        if (!StringUtils.hasText(raw)) {
            raw = first(request.getHeaders().get(HttpHeaders.AUTHORIZATION));
        }
        LoginUser user = jwtUtils.parse(jwtUtils.strip(raw));
        if (user == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("WebSocket 握手被拒：token 缺失或已失效 uri={}", request.getURI().getPath());
            return false;
        }
        if (!user.isClient() && !user.isDoctor()) {
            // 原因: 管理员不参与问诊会话，握手阶段直接拒绝而非建连后再踢出
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        if (!accountGuard.active(user)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER, user);
        Long sid = asLong(first(query.get("sessionId")));
        if (sid != null) {
            attributes.put(ATTR_SESSION_ID, sid);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, @Nullable Exception exception) {
        // 原因: 101 升级由 Spring 收尾，握手拦截器无额外状态需要清理
    }

    private static @Nullable String first(@Nullable List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static @Nullable Long asLong(@Nullable String raw) {
        if (raw == null || !raw.chars().allMatch(Character::isDigit) || raw.isBlank()) {
            return null;
        }
        return Long.valueOf(raw);
    }
}
