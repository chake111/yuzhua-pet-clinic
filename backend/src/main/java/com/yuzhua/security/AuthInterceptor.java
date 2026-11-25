package com.yuzhua.security;

import tools.jackson.databind.ObjectMapper;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.props.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鉴权拦截器。校验 JWT、区分两套登录体系，并回查账号状态。
 *
 * 回查 status 是为了补 JWT 无状态的老洞：管理员禁用某账号后，
 * 其手上未过期的 token 应立即失效。代价是每请求两次索引查询，本项目量级下可接受。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;
    private final AppProperties props;
    private final AccountGuard accountGuard;
    private final ObjectMapper objectMapper;
    private final Map<Method, Optional<PublicEndpoint>> publicCache = new ConcurrentHashMap<>();

    /**
     * 仅从请求头解析 JWT（拒绝 query 参数避免泄露至访问日志），先校验 @PublicEndpoint，
     * 再按路径隔离两套登录体系、回查账号状态与令牌版本，并在后台强制执行 @RequireRole Fail-Closed。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (handler instanceof HandlerMethod hm && findPublicEndpoint(hm) != null) {
            return true;
        }

        // 原因: getRequestURI() 含 contextPath（/api），剥离前缀后才能按 /admin/ /client/ 判定
        String path = request.getRequestURI().substring(request.getContextPath().length());

        boolean needAdmin = path.startsWith("/admin/");
        boolean needProtected = needAdmin || path.startsWith("/client/") || path.startsWith("/common/");

        // 原因: REST 仅认请求头 token，避免 ?token= 落入访问日志与浏览器历史
        String token = jwtUtils.strip(request.getHeader(props.getJwt().getHeader()));
        if (token == null || token.isBlank()) {
            if (needProtected) {
                reject(response, 401, "请先登录");
                return false;
            }
            return true;
        }

        LoginUser user = jwtUtils.parse(token);
        if (user == null) {
            reject(response, 401, "登录已过期，请重新登录");
            return false;
        }

        String scopeDeny = checkTokenScope(path, needAdmin, user);
        if (scopeDeny != null) {
            reject(response, 403, scopeDeny);
            return false;
        }

        if (!accountGuard.active(user)) {
            reject(response, 401, "账号状态或权限已变化，请重新登录");
            return false;
        }

        String deny = checkRole(handler, user, needAdmin);
        if (deny != null) {
            reject(response, 403, deny);
            return false;
        }

        UserContext.set(user);
        return true;
    }

    private @Nullable String checkTokenScope(String path, boolean needAdmin, LoginUser user) {
        if (needAdmin && user.getTyp() != TokenType.ADMIN) {
            return "无权访问后台";
        }
        if (path.startsWith("/client/") && user.getTyp() != TokenType.CLIENT) {
            return "请使用用户端账号访问";
        }
        return null;
    }

    private @Nullable PublicEndpoint findPublicEndpoint(HandlerMethod hm) {
        return publicCache.computeIfAbsent(hm.getMethod(), method -> {
            PublicEndpoint ann = hm.getMethodAnnotation(PublicEndpoint.class);
            if (ann == null) {
                ann = hm.getBeanType().getAnnotation(PublicEndpoint.class);
            }
            return Optional.ofNullable(ann);
        }).orElse(null);
    }

    private @Nullable String checkRole(Object handler, LoginUser user, boolean needAdmin) {
        if (!(handler instanceof HandlerMethod hm)) {
            // 原因: 非 HandlerMethod（如后台未知路径交由 ResourceHandler 触发业务 404）不拦截为 403
            return null;
        }
        RequireRole ann = hm.getMethodAnnotation(RequireRole.class);
        if (ann == null) {
            ann = hm.getBeanType().getAnnotation(RequireRole.class);
        }
        if (ann == null) {
            return needAdmin ? "当前账号没有该操作权限" : null;
        }
        return user.matchesAny(ann.value()) ? null : "当前账号没有该操作权限";
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception ex) {
        // 原因: 容器线程池会复用请求线程，preHandle 放行后无论正常返回或异常均须清理 ThreadLocal 身份
        UserContext.clear();
    }

    private void reject(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = code == 403 ? Result.forbidden(msg) : Result.unauthorized(msg);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
