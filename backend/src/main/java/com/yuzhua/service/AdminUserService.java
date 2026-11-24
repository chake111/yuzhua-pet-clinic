package com.yuzhua.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
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
        AdminUser a = baseMapper.selectForLogin(req.getUsername());
        if (a == null || !passwordEncoder.matches(req.getPassword(), a.getPassword())) {
            throw new BizException("账号或密码错误");
        }
        if (!Integer.valueOf(1).equals(a.getStatus())) {
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
        LoginVo vo = new LoginVo();
        fill(vo, a, doctorOf(a));
        return vo;
    }

    /** 同用户端：密码与令牌版本同一条 UPDATE 提交，使此前签发的后台令牌立即失效 */
    @Transactional
    public void changePassword(AuthDto.ChangePassword req) {
        AdminUser a = baseMapper.selectWithPassword(UserContext.requireId());
        if (a == null || !passwordEncoder.matches(req.getOldPassword(), a.getPassword())) {
            throw new BizException("原密码不正确");
        }
        update(Wrappers.<AdminUser>lambdaUpdate()
                .set(AdminUser::getPassword, passwordEncoder.encode(req.getNewPassword()))
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
        lu.setTyp(LoginUser.TYP_ADMIN);
        lu.setRole(a.getRole());
        lu.setDoctorId(a.getDoctorId());
        lu.setTokenVersion(a.getTokenVersion());

        LoginVo vo = new LoginVo();
        vo.setToken(jwtUtils.create(lu));
        fill(vo, a, d);
        return vo;
    }

    private void fill(LoginVo vo, AdminUser a, Doctor d) {
        vo.setId(a.getId());
        vo.setUsername(a.getUsername());
        vo.setName(a.getRealName());
        vo.setAvatar(avatarOf(a, d));
        vo.setPhone(a.getPhone());
        vo.setEmail(a.getEmail());
        vo.setTyp(LoginUser.TYP_ADMIN);
        vo.setRole(a.getRole());
        vo.setDoctorId(a.getDoctorId());

        if (d != null) {
            vo.setDoctorName(d.getName());
            Department dep = departmentMapper.selectById(d.getDepartmentId());
            vo.setDepartmentName(dep == null ? null : dep.getName());
        }
    }

    private Doctor doctorOf(AdminUser a) {
        return a.getDoctorId() == null ? null : doctorMapper.selectById(a.getDoctorId());
    }

    private static String avatarOf(AdminUser a, Doctor d) {
        if (a.getAvatar() != null && !a.getAvatar().isBlank()) {
            return a.getAvatar();
        }
        return d == null ? a.getAvatar() : d.getAvatar();
    }
}
