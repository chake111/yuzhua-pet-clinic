package com.yuzhua.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测性端点集成测试：真实 Tomcat + 真实 MySQL。
 *
 * <p>健康分组、Prometheus registry 和指标标签都是 Spring 上下文装配后才生效的配置，
 * 单元测试覆盖不到；这里直接走 HTTP，确认 /api 上下文下的端点和实际输出。
 *
 * <p>Spring Boot 测试上下文默认把 management.defaults.metrics.export.enabled 设为 false，
 * 不加 @AutoConfigureMetrics 时连 PrometheusMeterRegistry 都不会创建，端点必然 404。
 */
@AutoConfigureMetrics
class ObservabilityIT extends MySqlITSupport {

    @LocalServerPort
    private int port;

    private RestTestClient rest() {
        return RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/api")
                .build();
    }

    @Test
    void readinessAndLivenessAreUpWithDatabase() {
        String readiness = rest().get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(readiness).contains("\"status\":\"UP\"");

        String liveness = rest().get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(liveness).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusExposesHttpServerRequestsWithApplicationTag() {
        // 先制造一次真实请求；空闲实例不会凭空出现 http.server.requests
        String home = rest().get().uri("/client/catalog/home")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        // 本项目的 404 也是 HTTP 200 + body.code，必须看 code 才能确认真的打到了接口
        assertThat(home).contains("\"code\":200");

        String prometheus = rest().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(prometheus)
                .contains("http_server_requests_seconds_count")
                .contains("http_server_requests_seconds_bucket")
                .contains("application=\"yuzhua-server\"")
                .contains("client/catalog/home");

        // metrics 端点是暴露配置的一部分；p95 的分位数由 Prometheus _bucket 负责，
        // /actuator/metrics 只返回 count/total/max，这里只确认端点与 meter 可用。
        String metrics = rest().get().uri("/actuator/metrics/http.server.requests?tag=uri:/client/catalog/home")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(metrics)
                .contains("\"name\":\"http.server.requests\"")
                .contains("\"statistic\":\"COUNT\"");

        String apiDocs = rest().get().uri("/v3/api-docs/1-用户端")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(apiDocs)
                .contains("\"openapi\":\"3.1.0\"")
                .contains("/client/catalog/home");
    }

    @Test
    void publicCatalogAllowsAnonymousAndUnknownAdminPathReturnsBusiness404() throws Exception {
        String home = rest().get().uri("/client/catalog/home")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(home).contains("\"code\":200");

        String adminToken = loginAdminToken(mockMvc, "itadmin");
        String notFound = rest().get().uri("/admin/nonexistent")
                .header(authHeaderName(), bearer(adminToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        assertThat(notFound).contains("\"code\":404");
    }
}