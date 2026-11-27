package com.yuzhua.security;

import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.entity.AdminUser;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.AdminUserMapper;
import com.yuzhua.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌撤销规则：改密递增 token_version 后，旧令牌必须在 {@link AccountGuard} 这一处被拦下。
 *
 * <p>REST 拦截器、WebSocket 握手、入站帧、出站帧四条路径都调用同一个方法，
 * 所以这里覆盖到的语义就是四条路径共同的行为，不需要各自再测一遍。
 */
class AccountGuardTest {

    @SuppressWarnings("unchecked")
    private static <T> T mapper(Class<T> type, Function<String, Object> result) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> result.apply(method.getName()));
    }

    private static AccountGuard guard(SysUser user, AdminUser admin) {
        return new AccountGuard(
                mapper(SysUserMapper.class, name -> user),
                mapper(AdminUserMapper.class, name -> admin));
    }

    private static LoginUser client(Integer tokenVersion) {
        LoginUser u = new LoginUser();
        u.setId(7L);
        u.setTyp(TokenType.CLIENT);
        u.setRole(UserRole.USER);
        u.setTokenVersion(tokenVersion);
        u.setExpiresAt(Instant.now().plusSeconds(3600));
        return u;
    }

    private static SysUser dbUser(EnableStatus status, Integer tokenVersion) {
        SysUser u = new SysUser();
        u.setId(7L);
        u.setStatus(status);
        u.setTokenVersion(tokenVersion);
        return u;
    }

    private static LoginUser doctor(Integer tokenVersion, Long doctorId) {
        LoginUser u = new LoginUser();
        u.setId(3L);
        u.setTyp(TokenType.ADMIN);
        u.setRole(UserRole.DOCTOR);
        u.setDoctorId(doctorId);
        u.setTokenVersion(tokenVersion);
        u.setExpiresAt(Instant.now().plusSeconds(3600));
        return u;
    }

    private static AdminUser dbDoctor(EnableStatus status, Integer tokenVersion, Long doctorId) {
        AdminUser a = new AdminUser();
        a.setId(3L);
        a.setStatus(status);
        a.setTokenVersion(tokenVersion);
        a.setRole(AdminRole.DOCTOR);
        a.setDoctorId(doctorId);
        return a;
    }

    @Test
    void acceptTokenWhoseVersionMatches() {
        assertTrue(guard(dbUser(EnableStatus.ENABLED, 1), null).active(client(1)));
    }

    @Test
    void rejectTokenAfterPasswordChangeBumpedVersion() {
        // 改密把库中版本推到 2，令牌里仍是签发时的 1
        assertFalse(guard(dbUser(EnableStatus.ENABLED, 2), null).active(client(1)));
    }

    @Test
    void rejectTokenWithoutVersionClaim() {
        // 升级前签发、没有 ver 声明的旧令牌：无法证明未被撤销，一律拒绝
        assertFalse(guard(dbUser(EnableStatus.ENABLED, 1), null).active(client(null)));
    }

    @Test
    void rejectWhenDatabaseVersionIsMissing() {
        // 迁移未执行或列被清空时不能退化成"放行"
        assertFalse(guard(dbUser(EnableStatus.ENABLED, null), null).active(client(1)));
    }

    @Test
    void rejectDisabledAccount() {
        assertFalse(guard(dbUser(EnableStatus.DISABLED, 1), null).active(client(1)));
    }

    @Test
    void rejectExpiredToken() {
        LoginUser u = client(1);
        u.setExpiresAt(Instant.now().minusSeconds(1));
        assertFalse(guard(dbUser(EnableStatus.ENABLED, 1), null).active(u));
    }

    @Test
    void acceptDoctorWhoseBindingIsUnchanged() {
        assertTrue(guard(null, dbDoctor(EnableStatus.ENABLED, 4, 12L)).active(doctor(4, 12L)));
    }

    @Test
    void rejectDoctorAfterBindingChangedOrVersionBumped() {
        assertFalse(guard(null, dbDoctor(EnableStatus.ENABLED, 4, 99L)).active(doctor(4, 12L)));
        assertFalse(guard(null, dbDoctor(EnableStatus.ENABLED, 5, 12L)).active(doctor(4, 12L)));
    }
}
