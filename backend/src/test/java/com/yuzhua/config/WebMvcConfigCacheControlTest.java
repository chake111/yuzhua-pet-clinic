package com.yuzhua.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.yuzhua.security.AccountGuard;
import com.yuzhua.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传图片静态资源的分层缓存头校验（免 Docker 轻量 MockMvc）。
 *
 * <p>验证 {@link WebMvcConfig#addResourceHandlers} 按特异度先后注册的两条映射：
 * 种子图 {@code /api/uploads/seed/**} 返回 24 小时公开缓存且不带 {@code immutable}，
 * UUID 用户上传图 {@code /api/uploads/**} 返回 1 年公开强缓存并带 {@code immutable}。
 */
class WebMvcConfigCacheControlTest {

    @TempDir
    Path uploadRoot;

    @Test
    void appliesLayeredCacheControlForSeedAndUploadedAssets() throws Exception {
        byte[] seedBytes = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        byte[] uuidBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

        Path seedFile = uploadRoot.resolve("seed/sample.jpg");
        Files.createDirectories(seedFile.getParent());
        Files.write(seedFile, seedBytes);

        Path uuidFile = uploadRoot.resolve("2026/11/22/uuid.png");
        Files.createDirectories(uuidFile.getParent());
        Files.write(uuidFile, uuidBytes);

        try (AnnotationConfigWebApplicationContext context = buildContext(uploadRoot, "/api/uploads")) {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            MvcResult seedResult = mockMvc.perform(get("/api/uploads/seed/sample.jpg").contextPath("/api"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(seedBytes))
                    .andReturn();
            String seedCache = seedResult.getResponse().getHeader("Cache-Control");
            assertThat(seedCache)
                    .isNotNull()
                    .contains("max-age=86400")
                    .contains("public")
                    .doesNotContain("immutable");

            MvcResult uuidResult = mockMvc.perform(get("/api/uploads/2026/11/22/uuid.png").contextPath("/api"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(uuidBytes))
                    .andReturn();
            String uuidCache = uuidResult.getResponse().getHeader("Cache-Control");
            assertThat(uuidCache)
                    .isNotNull()
                    .contains("max-age=31536000")
                    .contains("public")
                    .contains("immutable");
        }
    }

    @Test
    void normalizesTrailingSlashInUrlPrefix() throws Exception {
        byte[] seedBytes = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        Path seedFile = uploadRoot.resolve("seed/sample.jpg");
        Files.createDirectories(seedFile.getParent());
        Files.write(seedFile, seedBytes);

        try (AnnotationConfigWebApplicationContext context = buildContext(uploadRoot, "/api/uploads/")) {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

            MvcResult seedResult = mockMvc.perform(get("/api/uploads/seed/sample.jpg").contextPath("/api"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(seedResult.getResponse().getHeader("Cache-Control"))
                    .contains("max-age=86400")
                    .contains("public")
                    .doesNotContain("immutable");
        }
    }

    private static AnnotationConfigWebApplicationContext buildContext(Path uploadDir, String urlPrefix) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of(
                        "server.servlet.context-path", "/api",
                        "test.upload.dir", uploadDir.toString(),
                        "test.upload.url-prefix", urlPrefix)));
        context.register(TestMvcConfig.class, WebMvcConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    static class TestMvcConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        AppProperties appProperties(org.springframework.core.env.Environment env) {
            AppProperties props = new AppProperties();
            props.getUpload().setDir(env.getRequiredProperty("test.upload.dir"));
            props.getUpload().setUrlPrefix(env.getRequiredProperty("test.upload.url-prefix"));
            return props;
        }

        @Bean
        JwtUtils jwtUtils() {
            return mock(JwtUtils.class);
        }

        @Bean
        AccountGuard accountGuard() {
            return mock(AccountGuard.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
