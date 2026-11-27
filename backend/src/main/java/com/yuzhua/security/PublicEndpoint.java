package com.yuzhua.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 显式声明匿名可访问端点（全站默认 fail-closed，未标注受保护路径须经 {@link AuthInterceptor} 校验 JWT）。
 * 运行期由 {@link AuthInterceptor} 按方法优先于类读取；写操作匿名入口须显式声明 {@code allowMutation = true}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {

    boolean allowMutation() default false;
}
