package com.yuzhua.security;

import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.props.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthInterceptor 反射矩阵与 Fail-Closed 门禁测试：
 * 覆盖全部真实控制器端点与 4 种身份（匿名 / 客户端 / 管理员 / 医生）组合，
 * 以及后台未标注 @RequireRole 的 Fail-Closed 拦截和非 HandlerMethod 404 放行。
 */
class AuthInterceptorTest {

    private static final String TEST_SECRET = "auth-interceptor-matrix-test-secret-key!!";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void cleanContext() {
        UserContext.clear();
    }

    @Test
    void allControllerEndpointsEnforceFailClosedAndRoleMatrix() throws Exception {
        Fixture f = newFixture();
        List<Class<?>> controllers = scanControllers();
        assertThat(controllers).hasSizeGreaterThanOrEqualTo(20);

        int testedCombinations = 0;
        for (Class<?> controllerType : controllers) {
            Object bean = instantiateWithMocks(controllerType);
            String classPrefix = extractPrefix(controllerType.getAnnotation(RequestMapping.class));
            for (Method method : controllerType.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = joinPath(classPrefix, firstPath(mapping));
                HandlerMethod hm = new HandlerMethod(bean, method);
                PublicEndpoint pub = findAnnotation(hm, PublicEndpoint.class);
                RequireRole role = findAnnotation(hm, RequireRole.class);

                for (Identity identity : Identity.values()) {
                    testedCombinations++;
                    assertMatrixCase(f, hm, path, pub, role, identity);
                }
            }
        }
        assertThat(testedCombinations).isGreaterThan(200);
    }

    @Test
    void unannotatedAdminHandlerIsDeniedFailClosedForEveryIdentity() throws Exception {
        Fixture f = newFixture();
        UnannotatedAdminStub stub = new UnannotatedAdminStub();
        Method method = UnannotatedAdminStub.class.getDeclaredMethod("ping");
        HandlerMethod hm = new HandlerMethod(stub, method);

        assertRejected(f, hm, "/admin/stub/ping", Identity.ANONYMOUS, 401);
        assertRejected(f, hm, "/admin/stub/ping", Identity.CLIENT, 403);
        assertRejected(f, hm, "/admin/stub/ping", Identity.ADMIN, 403);
        assertRejected(f, hm, "/admin/stub/ping", Identity.DOCTOR, 403);
    }

    @Test
    void nonHandlerMethodAdminPathPassesThroughFor404AndPreflightOptionsPasses() throws Exception {
        Fixture f = newFixture();
        Object resourceHandler = new Object();

        assertRejected(f, resourceHandler, "/admin/nonexistent", Identity.ANONYMOUS, 401);
        assertRejected(f, resourceHandler, "/admin/nonexistent", Identity.CLIENT, 403);
        assertAllowed(f, resourceHandler, "/admin/nonexistent", Identity.ADMIN);

        MockHttpServletRequest optionsReq = new MockHttpServletRequest("OPTIONS", "/api/admin/user/page");
        optionsReq.setContextPath("/api");
        MockHttpServletResponse optionsResp = new MockHttpServletResponse();
        assertThat(f.interceptor.preHandle(optionsReq, optionsResp, resourceHandler)).isTrue();
    }

    private void assertMatrixCase(Fixture f, HandlerMethod hm, String path,
                                  PublicEndpoint pub, RequireRole role, Identity identity) throws Exception {
        if (pub != null) {
            assertAllowed(f, hm, path, identity);
            return;
        }
        if (identity == Identity.ANONYMOUS) {
            assertRejected(f, hm, path, identity, 401);
            return;
        }
        if (path.startsWith("/admin/") && identity.typ != TokenType.ADMIN) {
            assertRejected(f, hm, path, identity, 403);
            return;
        }
        if (path.startsWith("/client/") && identity.typ != TokenType.CLIENT) {
            assertRejected(f, hm, path, identity, 403);
            return;
        }
        if (path.startsWith("/admin/") && role == null) {
            assertRejected(f, hm, path, identity, 403);
            return;
        }
        if (role != null && !identity.matches(role.value())) {
            assertRejected(f, hm, path, identity, 403);
            return;
        }
        assertAllowed(f, hm, path, identity);
    }

    private void assertAllowed(Fixture f, Object handler, String path, Identity identity) throws Exception {
        MockHttpServletRequest req = requestFor(f, path, identity);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean passed = f.interceptor.preHandle(req, resp, handler);
        try {
            assertThat(passed).as("expected allow for %s %s", identity, path).isTrue();
            if (identity != Identity.ANONYMOUS && findPublic(handler) == null) {
                assertThat(UserContext.get()).isNotNull();
                assertThat(UserContext.get().getId()).isEqualTo(identity.userId);
            }
        } finally {
            f.interceptor.afterCompletion(req, resp, handler, null);
            assertThat(UserContext.get()).isNull();
        }
    }

    private void assertRejected(Fixture f, Object handler, String path,
                                Identity identity, int expectedCode) throws Exception {
        MockHttpServletRequest req = requestFor(f, path, identity);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean passed = f.interceptor.preHandle(req, resp, handler);
        try {
            assertThat(passed).as("expected reject(%s) for %s %s", expectedCode, identity, path).isFalse();
            assertThat(resp.getStatus()).isEqualTo(200);
            JsonNode body = MAPPER.readTree(resp.getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("code").asInt()).as("code for %s %s", identity, path).isEqualTo(expectedCode);
        } finally {
            f.interceptor.afterCompletion(req, resp, handler, null);
        }
    }

    private MockHttpServletRequest requestFor(Fixture f, String path, Identity identity) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api" + path);
        req.setContextPath("/api");
        if (identity != Identity.ANONYMOUS) {
            req.addHeader(f.props.getJwt().getHeader(), f.props.getJwt().getPrefix() + identity.token(f.jwtUtils));
        }
        return req;
    }

    private static PublicEndpoint findPublic(Object handler) {
        if (handler instanceof HandlerMethod hm) {
            return findAnnotation(hm, PublicEndpoint.class);
        }
        return null;
    }

    private static <A extends java.lang.annotation.Annotation> A findAnnotation(HandlerMethod hm, Class<A> type) {
        A ann = hm.getMethodAnnotation(type);
        return ann != null ? ann : hm.getBeanType().getAnnotation(type);
    }

    private static Fixture newFixture() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(TEST_SECRET);
        JwtUtils jwtUtils = new JwtUtils(props);
        AccountGuard guard = mock(AccountGuard.class);
        when(guard.active(any())).thenReturn(true);
        AuthInterceptor interceptor = new AuthInterceptor(jwtUtils, props, guard, MAPPER);
        return new Fixture(props, jwtUtils, interceptor);
    }

    private static List<Class<?>> scanControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> defs = scanner.findCandidateComponents("com.yuzhua.controller");
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition def : defs) {
            result.add(Class.forName(def.getBeanClassName()));
        }
        return result;
    }

    private static Object instantiateWithMocks(Class<?> controllerType) throws Exception {
        Constructor<?> ctor = controllerType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(type -> mock(type))
                .toArray();
        return ctor.newInstance(args);
    }

    private static String extractPrefix(RequestMapping mapping) {
        return mapping == null ? "" : firstPath(mapping);
    }

    private static String firstPath(RequestMapping mapping) {
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return paths.length > 0 ? paths[0] : "";
    }

    private static String joinPath(String prefix, String suffix) {
        String normalized = (prefix + "/" + suffix).replaceAll("/+", "/");
        return normalized.replace("{id}", "1");
    }

    private record Fixture(AppProperties props, JwtUtils jwtUtils, AuthInterceptor interceptor) {
    }

    private enum Identity {
        ANONYMOUS(0L, null, null, null),
        CLIENT(101L, TokenType.CLIENT, UserRole.USER, null),
        ADMIN(1L, TokenType.ADMIN, UserRole.ADMIN, null),
        DOCTOR(2L, TokenType.ADMIN, UserRole.DOCTOR, 12L);

        private final long userId;
        private final TokenType typ;
        private final UserRole role;
        private final Long doctorId;

        Identity(long userId, TokenType typ, UserRole role, Long doctorId) {
            this.userId = userId;
            this.typ = typ;
            this.role = role;
            this.doctorId = doctorId;
        }

        boolean matches(AdminRole[] allowed) {
            for (AdminRole candidate : allowed) {
                if (role != null && candidate.name().equals(role.name())) {
                    return true;
                }
            }
            return false;
        }

        String token(JwtUtils jwtUtils) {
            LoginUser user = new LoginUser();
            user.setId(userId);
            user.setTyp(typ);
            user.setRole(role);
            user.setDoctorId(doctorId);
            user.setTokenVersion(1);
            user.setUsername(name());
            return jwtUtils.create(user);
        }
    }

    @RestController
    @RequestMapping("/admin/stub")
    static class UnannotatedAdminStub {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }
}
