package com.yuzhua.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    private final BrandProperties brandProperties;

    @Bean
    public OpenAPI yuzhuaOpenAPI() {
        return new OpenAPI().info(new Info()
                .title(brandProperties.getFullName() + " API")
                .description("单家宠物医院自用：预约挂号 + 在线图文问诊 + 后台接诊工作台")
                .version("1.0.0")
                .contact(new Contact().name("yuzhua")));
    }

    @Bean
    public GroupedOpenApi clientApi() {
        return GroupedOpenApi.builder().group("1-用户端").pathsToMatch("/client/**", "/common/**").build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("2-管理后台").pathsToMatch("/admin/**").build();
    }
}
