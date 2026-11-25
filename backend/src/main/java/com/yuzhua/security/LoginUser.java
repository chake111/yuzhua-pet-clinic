package com.yuzhua.security;

import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 登录态。由 JwtUtils 从 token 解出，放进 UserContext 供全链路取用。
 * typ 区分两套登录体系，防止用户端 token 直接访问后台接口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private @Nullable Long id;
    private @Nullable String username;
    private @Nullable String name;
    private @Nullable String avatar;
    private @Nullable TokenType typ;
    private @Nullable UserRole role;
    private @Nullable Long doctorId;
    private @Nullable Integer tokenVersion;
    private @Nullable Instant expiresAt;

    /** 判定当前登录态是否为后台管理员角色（须同时满足 typ=ADMIN 与 role=ADMIN）。 */
    public boolean isAdmin() {
        return typ == TokenType.ADMIN && role == UserRole.ADMIN;
    }

    /** 判定当前登录态是否为后台接诊医生角色（须同时满足 typ=ADMIN 与 role=DOCTOR）。 */
    public boolean isDoctor() {
        return typ == TokenType.ADMIN && role == UserRole.DOCTOR;
    }

    /** 判定当前登录态是否属于用户端登录体系（typ=CLIENT）。 */
    public boolean isClient() {
        return typ == TokenType.CLIENT;
    }

    /** 校验当前后台登录态是否命中给定后台角色集合。 */
    public boolean matchesAny(AdminRole... roles) {
        if (typ != TokenType.ADMIN || role == null) {
            return false;
        }
        for (AdminRole allowed : roles) {
            if (allowed.code().equals(role.code())) {
                return true;
            }
        }
        return false;
    }
}
