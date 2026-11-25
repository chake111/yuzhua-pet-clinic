package com.yuzhua.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.dto.AuthDto;
import com.yuzhua.entity.AdminUser;
import com.yuzhua.entity.Department;
import com.yuzhua.entity.Doctor;
import com.yuzhua.mapper.AdminUserMapper;
import com.yuzhua.mapper.DepartmentMapper;
import com.yuzhua.mapper.DoctorMapper;
import com.yuzhua.security.JwtUtils;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.LoginVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminUserService extends ServiceImpl<AdminUserMapper, AdminUser> {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final DoctorMapper doctorMapper;
    private final DepartmentMapper departmentMapper;

    public LoginVo login(AuthDto.Login req) {
        if (req.username() == null || req.password() == null) {
            throw new BizException("账号或密码错误");
        }
        AdminUser a = baseMapper.selectForLogin(req.username());
        if (a == null || !passwordEncoder.matches(req.password(), a.getPassword())) {
            throw new BizException("账号或密码错误");
        }
        if (a.getStatus() != EnableStatus.ENABLED) {
            throw new BizException("账号已被停用");
        }
        AdminUser touch = new AdminUser();
        touch.setId(a.getId());
        touch.setLastLoginTime(LocalDateTime.now());
        updateById(touch);
        return issueToken(a);
    }

    public LoginVo current() {
        AdminUser a = getById(UserContext.requireId());
        if (a == null) {
            throw new BizException(401, "账号不存在");
        }
        return toLoginVo(null, a, doctorOf(a));
    }

    /** 同用户端：密码与令牌版本同一条 UPDATE 提交，使此前签发的后台令牌立即失效 */
    @Transactional
    public void changePassword(AuthDto.ChangePassword req) {
        AdminUser a = baseMapper.selectWithPassword(UserContext.requireId());
        if (a == null || !passwordEncoder.matches(req.oldPassword(), a.getPassword())) {
            throw new BizException("原密码不正确");
        }
        update(Wrappers.<AdminUser>lambdaUpdate()
                .set(AdminUser::getPassword, passwordEncoder.encode(req.newPassword()))
                .setSql("token_version = token_version + 1")
                .eq(AdminUser::getId, a.getId()));
    }

    private LoginVo issueToken(AdminUser a) {
        Doctor d = doctorOf(a);
        LoginUser lu = new LoginUser();
        lu.setId(a.getId());
        lu.setUsername(a.getUsername());
        lu.setName(a.getRealName());
        lu.setAvatar(avatarOf(a, d));
        lu.setTyp(TokenType.ADMIN);
        lu.setRole(a.getRole() == null ? null : UserRole.fromCode(a.getRole().code()));
        lu.setDoctorId(a.getDoctorId());
        lu.setTokenVersion(a.getTokenVersion());

        return toLoginVo(jwtUtils.create(lu), a, d);
    }

    private LoginVo toLoginVo(@Nullable String token, AdminUser a, @Nullable Doctor d) {
        String doctorName = d == null ? null : d.getName();
        String departmentName = null;
        if (d != null) {
            Department dep = departmentMapper.selectById(d.getDepartmentId());
            departmentName = dep == null ? null : dep.getName();
        }
        return new LoginVo(
                token,
                a.getId(),
                a.getUsername(),
                a.getRealName(),
                avatarOf(a, d),
                a.getPhone(),
                a.getEmail(),
                TokenType.ADMIN,
                a.getRole() == null ? null : UserRole.fromCode(a.getRole().code()),
                a.getDoctorId(),
                doctorName,
                departmentName
        );
    }

    private @Nullable Doctor doctorOf(AdminUser a) {
        return a.getDoctorId() == null ? null : doctorMapper.selectById(a.getDoctorId());
    }

    private static @Nullable String avatarOf(AdminUser a, @Nullable Doctor d) {
        if (a.getAvatar() != null && !a.getAvatar().isBlank()) {
            return a.getAvatar();
        }
        return d == null ? a.getAvatar() : d.getAvatar();
    }
}
