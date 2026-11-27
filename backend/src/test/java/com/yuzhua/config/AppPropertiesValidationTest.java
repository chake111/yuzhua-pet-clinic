package com.yuzhua.config;

import com.yuzhua.props.AppProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置项的启动期校验。租约有效期配成 0 或负数时必须在启动阶段失败，
 * 否则租约一到当前时刻就过期，多实例互斥会静默失效；慢 SQL 阈值同理不能为负数。
 */
class AppPropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void leaseSecondsMustBePositive() {
        AppProperties props = new AppProperties();

        props.getScheduler().setLeaseSeconds(0);
        var violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("scheduler.leaseSeconds");
        assertThat(violations).extracting(v -> v.getMessage())
                .contains("定时任务租约有效期必须大于 0 秒");

        props.getScheduler().setLeaseSeconds(-1);
        assertThat(validator.validate(props)).isNotEmpty();

        props.getScheduler().setLeaseSeconds(300);
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    void slowSqlThresholdMustNotBeNegative() {
        AppProperties props = new AppProperties();

        props.getObservability().setSlowSqlThresholdMs(-1);
        var violations = validator.validate(props);
        assertThat(violations).isNotEmpty();
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("observability.slowSqlThresholdMs");
        assertThat(violations).extracting(v -> v.getMessage())
                .contains("慢 SQL 阈值不能为负数");

        // 0 表示关闭慢 SQL 日志，是合法值
        props.getObservability().setSlowSqlThresholdMs(0);
        assertThat(validator.validate(props)).isEmpty();

        props.getObservability().setSlowSqlThresholdMs(200);
        assertThat(validator.validate(props)).isEmpty();
    }
}
