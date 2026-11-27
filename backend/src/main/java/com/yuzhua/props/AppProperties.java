package com.yuzhua.props;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "yuzhua")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Upload upload = new Upload();
    @Valid
    private Scheduler scheduler = new Scheduler();
    @Valid
    private Observability observability = new Observability();

    private int payTimeoutMinutes = 30;

    @Data
    public static class Jwt {
        private @Nullable String secret;
        private long expireSeconds = 604800;
        private String header = "Authorization";
        private String prefix = "Bearer ";
    }

    @Data
    public static class Upload {
        private String dir = "./uploads";
        private String urlPrefix = "/api/uploads";
        private long maxSizeBytes = 5 * 1024 * 1024;
    }

    @Data
    public static class Scheduler {
        @Min(value = 1, message = "定时任务租约有效期必须大于 0 秒")
        private long leaseSeconds = 300;
    }

    @Data
    public static class Observability {
        @Min(value = 0, message = "慢 SQL 阈值不能为负数")
        private long slowSqlThresholdMs = 200;
    }
}
