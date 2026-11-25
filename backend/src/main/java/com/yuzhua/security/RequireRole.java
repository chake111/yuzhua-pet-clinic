package com.yuzhua.security;

import com.yuzhua.domain.enums.AdminRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限定能调用该接口（或整个 Controller）的后台角色。
 *
 * 后台只有两种账号：ADMIN 管理员、DOCTOR 医生，两者都通过 /admin/** 的登录体系校验。
 * 方法上的声明优先于类上的声明；/admin/** 端点的 HandlerMethod 未标本注解且未标 @PublicEndpoint 时一律 403 拒绝。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    AdminRole[] value();
}
