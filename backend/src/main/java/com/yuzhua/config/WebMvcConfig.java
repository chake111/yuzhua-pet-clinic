package com.yuzhua.config;

import tools.jackson.databind.ObjectMapper;
import com.yuzhua.domain.enums.CodedEnum;
import com.yuzhua.props.AppProperties;
import com.yuzhua.security.AccountGuard;
import com.yuzhua.security.AuthInterceptor;
import com.yuzhua.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private static final long CORS_MAX_AGE_SECONDS = 3600L;
    private static final long IMMUTABLE_CACHE_DAYS = 365L;

    private final JwtUtils jwtUtils;
    private final AppProperties props;
    private final AccountGuard accountGuard;
    private final ObjectMapper objectMapper;

    // 原因: Lombok 构造器注入不传递字段上的 @Value，须保留字段注入
    @Value("${server.servlet.context-path:}")
    private @Nullable String contextPath;

    @Bean
    public AuthInterceptor authInterceptor() {
        return new AuthInterceptor(jwtUtils, props, accountGuard, objectMapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor())
                .addPathPatterns("/admin/**", "/client/**", "/common/**");
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToCodedEnumConverter());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 原因: 跨域直连调试时暴露追踪号给浏览器脚本，同源 nginx 代理不受此限制
                .exposedHeaders("Authorization", "X-Trace-Id")
                .allowCredentials(true)
                .maxAge(CORS_MAX_AGE_SECONDS);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadRoot = Paths.get(props.getUpload().getDir()).toAbsolutePath().normalize();
        String pattern = resourcePattern();
        // 原因: 按路径特异度先后注册缓存策略，/seed/** 可覆盖更新故短缓存，UUID 上传图永久不可变
        registry.addResourceHandler(pattern + "/seed/**")
                .addResourceLocations(toDirectoryLocation(uploadRoot.resolve("seed")))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
        registry.addResourceHandler(pattern + "/**")
                .addResourceLocations(toDirectoryLocation(uploadRoot))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(IMMUTABLE_CACHE_DAYS)).cachePublic().immutable());
    }

    private String resourcePattern() {
        String prefix = props.getUpload().getUrlPrefix();
        String ctx = contextPath == null ? "" : stripTrailingSlashes(contextPath);
        String stripped = (!ctx.isEmpty() && (prefix.equals(ctx) || prefix.startsWith(ctx + "/")))
                ? prefix.substring(ctx.length())
                : prefix;
        stripped = stripTrailingSlashes(stripped);
        if (!stripped.isEmpty() && !stripped.startsWith("/")) {
            return "/" + stripped;
        }
        return stripped;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String toDirectoryLocation(Path dir) {
        String uri = dir.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    static final class StringToCodedEnumConverter implements ConditionalGenericConverter {

        @Override
        public Set<ConvertiblePair> getConvertibleTypes() {
            return Set.of(new ConvertiblePair(String.class, CodedEnum.class));
        }

        @Override
        public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
            Class<?> targetClass = targetType.getType();
            return CodedEnum.class.isAssignableFrom(targetClass) && targetClass.isEnum();
        }

        @Override
        public @Nullable Object convert(@Nullable Object source,
                                        TypeDescriptor sourceType,
                                        TypeDescriptor targetType) {
            if (source == null) {
                return null;
            }
            String trimmed = source.toString().trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            Object[] constants = targetType.getType().getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    if (constant instanceof CodedEnum<?> coded && String.valueOf(coded.code()).equals(trimmed)) {
                        return constant;
                    }
                }
            }
            throw new IllegalArgumentException(
                    "Invalid " + targetType.getType().getSimpleName() + " code: " + source);
        }
    }
}
