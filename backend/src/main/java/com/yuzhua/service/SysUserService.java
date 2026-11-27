package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.dto.AuthDto;
import com.yuzhua.dto.UserQuery;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.JwtUtils;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.LoginVo;
import com.yuzhua.vo.UserDetailVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final PetService petService;
    private final AppointmentService appointmentService;

    /** 校验账号与手机号唯一后创建用户端账号（初始令牌版本为 1）并签发登录令牌。 */
    @Transactional
    public LoginVo register(AuthDto.Register req) {
        if (exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.getUsername()))) {
            throw new BizException("该账号已被注册");
        }
        if (exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, req.getPhone()))) {
            throw new BizException("该手机号已注册");
        }
        SysUser u = new SysUser();
        u.setUsername(req.getUsername());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setNickname(req.getNickname());
        u.setPhone(req.getPhone());
        u.setEmail(req.getEmail());
        u.setGender(req.getGender() == null ? 0 : req.getGender());
        u.setStatus(1);
        u.setTokenVersion(1);
        save(u);
        return issueToken(u);
    }

    public LoginVo login(AuthDto.Login req) {
        SysUser u = baseMapper.selectForLogin(req.getUsername());
        if (u == null || !passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            // 原因: 不区分账号不存在与密码错误，避免登录接口被用于枚举注册账号
            throw new BizException("账号或密码错误");
        }
        if (!Integer.valueOf(1).equals(u.getStatus())) {
            throw new BizException("账号已被停用，请联系医院客服");
        }
        SysUser touch = new SysUser();
        touch.setId(u.getId());
        touch.setLastLoginTime(LocalDateTime.now());
        updateById(touch);
        return issueToken(u);
    }

    public SysUser current() {
        SysUser u = getById(UserContext.requireId());
        if (u == null) {
            throw new BizException(401, "账号不存在");
        }
        return u;
    }

    public void updateProfile(AuthDto.UpdateProfile req) {
        String phone = emptyToNull(req.getPhone());
        if (phone != null && exists(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, phone).ne(SysUser::getId, UserContext.requireId()))) {
            throw new BizException("该手机号已注册");
        }
        update(Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getNickname, req.getNickname())
                .set(SysUser::getAvatar, req.getAvatar())
                .set(SysUser::getPhone, emptyToNull(req.getPhone()))
                .set(SysUser::getEmail, emptyToNull(req.getEmail()))
                .set(SysUser::getGender, req.getGender())
                .set(SysUser::getCity, emptyToNull(req.getCity()))
                .set(SysUser::getAddress, emptyToNull(req.getAddress()))
                .eq(SysUser::getId, UserContext.requireId()));
    }

    /**
     * 改密即撤销该账号此前签发的所有令牌：密码与令牌版本在同一条 UPDATE 里提交，
     * 版本递增后旧令牌在 AccountGuard 处版本不匹配，REST、握手和 WebSocket 收发一并失效。
     */
    @Transactional
    public void changePassword(AuthDto.ChangePassword req) {
        SysUser u = baseMapper.selectWithPassword(UserContext.requireId());
        if (u == null || !passwordEncoder.matches(req.getOldPassword(), u.getPassword())) {
            throw new BizException("原密码不正确");
        }
        update(Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, passwordEncoder.encode(req.getNewPassword()))
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getId, u.getId()));
    }

    public void changeStatus(Long userId, Integer status) {
        if (getById(userId) == null) {
            throw BizException.notFound("用户");
        }
        update(Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getStatus, status)
                .eq(SysUser::getId, userId));
    }

    public PageResult<SysUser> pageForAdmin(UserQuery q) {
        LambdaQueryWrapper<SysUser> qw = Wrappers.<SysUser>lambdaQuery()
                .and(q.getKeyword() != null && !q.getKeyword().isBlank(), w -> w
                        .like(SysUser::getNickname, q.getKeyword())
                        .or().like(SysUser::getPhone, q.getKeyword())
                        .or().like(SysUser::getUsername, q.getKeyword()))
                .eq(q.getStatus() != null, SysUser::getStatus, q.getStatus())
                .orderByDesc(SysUser::getCreateTime);
        return PageResult.of(page(new Page<>(q.getPageNum(), q.getPageSize()), qw));
    }

    public UserDetailVo detailForAdmin(Long userId) {
        SysUser u = getById(userId);
        if (u == null) {
            throw BizException.notFound("用户");
        }
        UserDetailVo vo = new UserDetailVo();
        vo.setUser(u);
        vo.setPets(petService.listByUser(userId));
        vo.setAppointments(appointmentService.listByUserForAdmin(userId, 20));
        return vo;
    }

    private LoginVo issueToken(SysUser u) {
        LoginUser lu = new LoginUser();
        lu.setId(u.getId());
        lu.setUsername(u.getUsername());
        lu.setName(u.getNickname());
        lu.setAvatar(u.getAvatar());
        lu.setTyp(LoginUser.TYP_CLIENT);
        lu.setRole(LoginUser.ROLE_USER);
        lu.setTokenVersion(u.getTokenVersion());

        LoginVo vo = new LoginVo();
        vo.setToken(jwtUtils.create(lu));
        fill(vo, u);
        return vo;
    }

    private void fill(LoginVo vo, SysUser u) {
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setName(u.getNickname());
        vo.setAvatar(u.getAvatar());
        vo.setPhone(u.getPhone());
        vo.setEmail(u.getEmail());
        vo.setTyp(LoginUser.TYP_CLIENT);
        vo.setRole(LoginUser.ROLE_USER);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
