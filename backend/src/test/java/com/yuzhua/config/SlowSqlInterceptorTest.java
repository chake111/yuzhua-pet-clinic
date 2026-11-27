package com.yuzhua.config;

import com.yuzhua.props.AppProperties;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 慢 SQL 拦截器单测：用固定纳秒序列代替真实时钟，不启动 Spring 上下文，也不 sleep。
 * 断言只覆盖日志字段和阈值语义；traceId 由运行期 logging.pattern 带出，这里不做断言。
 */
@ExtendWith(OutputCaptureExtension.class)
class SlowSqlInterceptorTest {

    private static final String STATEMENT_ID = "com.yuzhua.mapper.AppointmentMapper.updateRemain";

    private static SlowSqlInterceptor interceptor(long thresholdMs, LongSupplier clock) {
        AppProperties props = new AppProperties();
        props.getObservability().setSlowSqlThresholdMs(thresholdMs);
        return new SlowSqlInterceptor(props, clock);
    }

    /** 按调用顺序返回固定纳秒值，最后一次重复使用，避免任何 sleep */
    private static LongSupplier fixedNanos(long... values) {
        AtomicInteger cursor = new AtomicInteger();
        return () -> values[Math.min(cursor.getAndIncrement(), values.length - 1)];
    }

    private static MappedStatement mappedStatement() {
        MappedStatement mappedStatement = mock(MappedStatement.class);
        when(mappedStatement.getId()).thenReturn(STATEMENT_ID);
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
        return mappedStatement;
    }

    private static Invocation invocation(MappedStatement mappedStatement, Object parameter, Object result)
            throws Exception {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getArgs()).thenReturn(new Object[]{mappedStatement, parameter});
        when(invocation.proceed()).thenReturn(result);
        return invocation;
    }

    @Test
    void belowThresholdDoesNotLog(CapturedOutput output) throws Throwable {
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 199_000_000L));
        Invocation invocation = invocation(mappedStatement(), null, "ok");

        Object result = interceptor.intercept(invocation);

        assertThat(result).isEqualTo("ok");
        assertThat(output.getAll()).doesNotContain("慢 SQL");
    }

    @Test
    void atThresholdLogsOneWarnWithFields(CapturedOutput output) throws Throwable {
        // 正好 200ms，验证阈值语义是“达到或超过”
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 200_000_000L));
        Invocation invocation = invocation(mappedStatement(), null, "ok");

        interceptor.intercept(invocation);

        assertThat(output.getAll())
                .contains("WARN")
                .contains("慢 SQL")
                .contains("statement=" + STATEMENT_ID)
                .contains("type=UPDATE")
                .contains("elapsed=200ms")
                .contains("failed=false")
                .containsOnlyOnce("慢 SQL");
    }

    @Test
    void querySignatureIsRegisteredInPluginChain(CapturedOutput output) throws Throwable {
        // 直接走 MyBatis Plugin 链，确认 SELECT 也会被拦；运行期只打 update 通常意味着 query 签名写错
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 250_000_000L));
        MappedStatement mappedStatement = mappedStatement();
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);

        Executor target = (Executor) Proxy.newProxyInstance(
                Executor.class.getClassLoader(),
                new Class<?>[]{Executor.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())) {
                        return List.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Executor proxied = (Executor) interceptor.plugin(target);

        proxied.query(mappedStatement, null, RowBounds.DEFAULT, (ResultHandler<Object>) null);

        assertThat(output.getAll())
                .contains("statement=" + STATEMENT_ID)
                .contains("type=SELECT")
                .contains("elapsed=250ms");
    }

    @Test
    void sixArgQuerySignatureIsRegisteredInPluginChain(CapturedOutput output) throws Throwable {
        // MyBatis-Plus 会把 4 参 query 改写成 6 参调用内层 Executor；漏了这条签名就看不到 SELECT
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 250_000_000L));
        MappedStatement mappedStatement = mappedStatement();
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);

        Executor target = (Executor) Proxy.newProxyInstance(
                Executor.class.getClassLoader(),
                new Class<?>[]{Executor.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())) {
                        return List.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Executor proxied = (Executor) interceptor.plugin(target);
        BoundSql boundSql = new BoundSql(new Configuration(), "select 1", List.of(), null);

        proxied.query(mappedStatement, null, RowBounds.DEFAULT, null, new CacheKey(), boundSql);

        assertThat(output.getAll())
                .contains("statement=" + STATEMENT_ID)
                .contains("type=SELECT")
                .contains("elapsed=250ms");
    }

    @Test
    void zeroThresholdDisablesInterception(CapturedOutput output) throws Throwable {
        LongSupplier clockThatMustNotBeRead = () -> {
            throw new AssertionError("阈值 0 表示关闭，不应读取时钟");
        };
        SlowSqlInterceptor interceptor = interceptor(0, clockThatMustNotBeRead);
        Invocation invocation = invocation(mappedStatement(), null, "ok");

        Object result = interceptor.intercept(invocation);

        assertThat(result).isEqualTo("ok");
        verify(invocation).proceed();
        assertThat(output.getAll()).doesNotContain("慢 SQL");
    }

    @Test
    void slowFailureIsRethrownAndLoggedAsFailed(CapturedOutput output) throws Throwable {
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 300_000_000L));
        MappedStatement mappedStatement = mappedStatement();
        Invocation invocation = mock(Invocation.class);
        when(invocation.getArgs()).thenReturn(new Object[]{mappedStatement, null});
        IllegalStateException boom = new IllegalStateException("boom-secret");
        when(invocation.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> interceptor.intercept(invocation)).isSameAs(boom);

        assertThat(output.getAll())
                .contains("elapsed=300ms")
                .contains("failed=true")
                // 异常消息也不允许进日志
                .doesNotContain("boom-secret");
    }

    @Test
    void logDoesNotContainParameterValues(CapturedOutput output) throws Throwable {
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 250_000_000L));
        Object parameter = Map.of("phone", "13800138000", "symptom", "猫咪呕吐三天");
        Invocation invocation = invocation(mappedStatement(), parameter, "ok");

        interceptor.intercept(invocation);

        assertThat(output.getAll())
                .contains("statement=" + STATEMENT_ID)
                .doesNotContain("13800138000")
                .doesNotContain("猫咪呕吐三天");
    }

    @Test
    void mybatisPlusQueryRewriteLogsOnlyOnce(CapturedOutput output) throws Throwable {
        // 模拟实际插件顺序：SlowSql 在内层，MybatisPlusInterceptor 把 4 参 query 改写成 6 参调用。
        // 如果两层都被计时，这里会出现两条 WARN；只应有一条。
        SlowSqlInterceptor interceptor = interceptor(200, fixedNanos(0L, 250_000_000L));
        MappedStatement mappedStatement = mappedStatement();
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);

        Executor raw = (Executor) Proxy.newProxyInstance(
                Executor.class.getClassLoader(),
                new Class<?>[]{Executor.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())) {
                        return List.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Executor slowSql = (Executor) interceptor.plugin(raw);
        Executor outer = (Executor) new QueryRewritingInterceptor().plugin(slowSql);

        outer.query(mappedStatement, null, RowBounds.DEFAULT, null);

        assertThat(output.getAll()).containsOnlyOnce("慢 SQL").contains("type=SELECT");
    }

    /** 复刻 MybatisPlusInterceptor 的行为：4 参 query 改为 6 参 query 调内层 Executor */
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    private static class QueryRewritingInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object[] args = invocation.getArgs();
            Executor target = (Executor) invocation.getTarget();
            BoundSql boundSql = new BoundSql(new Configuration(), "select 1", List.of(), null);
            return target.query((MappedStatement) args[0], args[1], (RowBounds) args[2],
                    (ResultHandler<?>) args[3], new CacheKey(), boundSql);
        }
    }
}