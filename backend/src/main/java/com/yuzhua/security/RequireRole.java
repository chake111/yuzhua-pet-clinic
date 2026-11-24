package com.yuzhua.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限定能调用该接口（或整个 Controller）的后台角色。
 *
 * 后台只有两种账号：ADMIN 管理员、DOCTOR 医生，两者都通过 /admin/** 的登录体系校验。
 * 不加这个注解，医生账号就能删科室、改别人的排班、建后台账号。
 * 方法上的声明优先于类上的声明。
 *
 * 注意 fail-open：类与方法都没有声明时，该接口不做角色校验（仅受 /admin/** 的登录体系约束）。
 * 新接口务必显式声明；方法级覆盖较多的控制器（如 AdminConsultController）建议挂类级兜底，
 * 漏标时按兜底角色收敛而不是放行。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    String[] value();
}
