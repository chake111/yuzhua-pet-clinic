package com.yuzhua.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * 登录态。由 JwtUtils 从 token 解出，放进 UserContext 供全链路取用。
 * typ 区分两套登录体系，防止用户端 token 直接访问后台接口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    public static final String TYP_CLIENT = "CLIENT";
    public static final String TYP_ADMIN = "ADMIN";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DOCTOR = "DOCTOR";
    public static final String ROLE_USER = "USER";

    private Long id;
    private String username;
    private String name;
    private String avatar;
    private String typ;
    private String role;
    private Long doctorId;
    private Integer tokenVersion;
    private Instant expiresAt;

    /** 判定当前登录态是否为后台管理员角色（须同时满足 typ=ADMIN 与 role=ADMIN）。 */
    public boolean isAdmin() {
        return TYP_ADMIN.equals(typ) && ROLE_ADMIN.equals(role);
    }

    /** 判定当前登录态是否为后台接诊医生角色（须同时满足 typ=ADMIN 与 role=DOCTOR）。 */
    public boolean isDoctor() {
        return TYP_ADMIN.equals(typ) && ROLE_DOCTOR.equals(role);
    }

    /** 判定当前登录态是否属于用户端登录体系（typ=CLIENT）。 */
    public boolean isClient() {
        return TYP_CLIENT.equals(typ);
    }
}
