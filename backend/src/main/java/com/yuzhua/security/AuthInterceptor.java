package com.yuzhua.security;

import tools.jackson.databind.ObjectMapper;
import com.yuzhua.common.Result;
import com.yuzhua.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

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

    /**
     * 仅从请求头解析 JWT（拒绝 query 参数避免泄露至访问日志），按路径隔离两套登录体系，
     * 经 AccountGuard 回查账号状态及令牌版本，并在放行时校验 @RequireRole 并写入 UserContext。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 原因: getRequestURI() 含 contextPath（/api），剥离前缀后才能按 /admin/ /client/ 判定
        String path = request.getRequestURI().substring(request.getContextPath().length());

        boolean needAdmin = path.startsWith("/admin/");
        boolean needClient = path.startsWith("/client/");
        // 原因: /common/** 双端共用，只校验已登录而不限登录体系
        boolean needAny = path.startsWith("/common/");

        // 原因: REST 仅认请求头 token，避免 ?token= 落入访问日志与浏览器历史
        String token = jwtUtils.strip(request.getHeader(props.getJwt().getHeader()));
        if (token == null || token.isBlank()) {
            if (needAdmin || needClient || needAny) {
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

        if (needAdmin && !LoginUser.TYP_ADMIN.equals(user.getTyp())) {
            reject(response, 403, "无权访问后台");
            return false;
        }
        if (needClient && !LoginUser.TYP_CLIENT.equals(user.getTyp())) {
            reject(response, 403, "请使用用户端账号访问");
            return false;
        }

        if (!accountGuard.active(user)) {
            reject(response, 401, "账号状态或权限已变化，请重新登录");
            return false;
        }

        String deny = checkRole(handler, user);
        if (deny != null) {
            reject(response, 403, deny);
            return false;
        }

        UserContext.set(user);
        return true;
    }

    private String checkRole(Object handler, LoginUser user) {
        if (!(handler instanceof HandlerMethod hm)) {
            return null;
        }
        RequireRole ann = hm.getMethodAnnotation(RequireRole.class);
        if (ann == null) {
            ann = hm.getBeanType().getAnnotation(RequireRole.class);
        }
        if (ann == null) {
            return null;
        }
        for (String role : ann.value()) {
            if (role.equals(user.getRole())) {
                return null;
            }
        }
        return "当前账号没有该操作权限";
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 原因: Tomcat 线程池复用请求线程，必须清理 ThreadLocal 防止身份串用
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
