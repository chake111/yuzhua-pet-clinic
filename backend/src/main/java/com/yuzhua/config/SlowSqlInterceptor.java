package com.yuzhua.config;

import com.yuzhua.props.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * MyBatis 慢 SQL 拦截器：按单条 Executor 语句计时，耗时达到阈值时仅记录 statement id、命令类型、耗时与失败标志。
 * 不记录 SQL 文本、入参与异常消息，避免手机号与病历隐私落盘。
 */
@Slf4j
@Component
// 原因: 最高优先级使本拦截器位于 MybatisPlusInterceptor 内层，分别统计分页 count 与主查询耗时
@Order(Ordered.HIGHEST_PRECEDENCE)
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        // 原因: MybatisPlusInterceptor 将 4 参 query 改写为 6 参调内层 Executor，需同时拦截才能统计 SELECT
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final AppProperties appProperties;
    private final LongSupplier nanoClock;

    @Autowired
    public SlowSqlInterceptor(AppProperties appProperties) {
        this(appProperties, System::nanoTime);
    }

    SlowSqlInterceptor(AppProperties appProperties, LongSupplier nanoClock) {
        this.appProperties = appProperties;
        this.nanoClock = nanoClock;
    }

    /**
     * 无状态可重入的只读观察者（不修改 SQL 返回值或异常）；关闭阈值时直接放行，
     * 开启后在 {@code finally} 中以单调时钟计时（SQL 抛异常亦记录 {@code failed=true}），耗时达阈值及以上时记 WARN。
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long thresholdMs = appProperties.getObservability().getSlowSqlThresholdMs();
        // 原因: 阈值为 0 表示关闭慢 SQL 监控，直接放行避免额外计时开销
        if (thresholdMs <= 0) {
            return invocation.proceed();
        }

        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        long startNanos = nanoClock.getAsLong();
        boolean failed = false;
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            failed = true;
            throw t;
        } finally {
            long elapsedMs = (nanoClock.getAsLong() - startNanos) / NANOS_PER_MILLI;
            // 原因: 契约语义为达到或超过阈值即告警
            if (elapsedMs >= thresholdMs) {
                log.warn("慢 SQL statement={} type={} elapsed={}ms failed={}",
                        mappedStatement.getId(), mappedStatement.getSqlCommandType(), elapsedMs, failed);
            }
        }
    }
}