package com.yuzhua.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import com.yuzhua.dto.AuthDto;
import com.yuzhua.dto.UserQuery;
import com.yuzhua.entity.SysUser;
import com.yuzhua.mapper.SysUserMapper;
import com.yuzhua.security.JwtUtils;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.UserContext;
import com.yuzhua.vo.LoginVo;
import com.yuzhua.vo.UserDetailVo;
import com.yuzhua.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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
        if (exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.username()))) {
            throw new BizException("该账号已被注册");
        }
        if (exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, req.phone()))) {
            throw new BizException("该手机号已注册");
        }
        SysUser u = new SysUser();
        u.setUsername(req.username());
        u.setPassword(passwordEncoder.encode(req.password()));
        u.setNickname(req.nickname());
        u.setPhone(req.phone());
        u.setEmail(req.email());
        u.setGender(req.gender() == null ? 0 : req.gender());
        u.setStatus(EnableStatus.ENABLED);
        u.setTokenVersion(1);
        save(u);
        return issueToken(u);
    }

    public LoginVo login(AuthDto.Login req) {
        if (req.username() == null || req.password() == null) {
            throw new BizException("账号或密码错误");
        }
        SysUser u = baseMapper.selectForLogin(req.username());
        if (u == null || !passwordEncoder.matches(req.password(), u.getPassword())) {
            // 原因: 不区分账号不存在与密码错误，避免登录接口被用于枚举注册账号
            throw new BizException("账号或密码错误");
        }
        if (u.getStatus() != EnableStatus.ENABLED) {
            throw new BizException("账号已被停用，请联系医院客服");
        }
        SysUser touch = new SysUser();
        touch.setId(u.getId());
        touch.setLastLoginTime(LocalDateTime.now());
        updateById(touch);
        return issueToken(u);
    }

    public UserVo current() {
        SysUser u = getById(UserContext.requireId());
        if (u == null) {
            throw new BizException(401, "账号不存在");
        }
        return toVo(u);
    }

    public void updateProfile(AuthDto.UpdateProfile req) {
        String phone = emptyToNull(req.phone());
        if (phone != null && exists(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, phone).ne(SysUser::getId, UserContext.requireId()))) {
            throw new BizException("该手机号已注册");
        }
        update(Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getNickname, req.nickname())
                .set(SysUser::getAvatar, req.avatar())
                .set(SysUser::getPhone, emptyToNull(req.phone()))
                .set(SysUser::getEmail, emptyToNull(req.email()))
                .set(SysUser::getGender, req.gender())
                .set(SysUser::getCity, emptyToNull(req.city()))
                .set(SysUser::getAddress, emptyToNull(req.address()))
                .eq(SysUser::getId, UserContext.requireId()));
    }

    /**
     * 改密即撤销该账号此前签发的所有令牌：密码与令牌版本在同一条 UPDATE 里提交，
     * 版本递增后旧令牌在 AccountGuard 处版本不匹配，REST、握手和 WebSocket 收发一并失效。
     */
    @Transactional
    public void changePassword(AuthDto.ChangePassword req) {
        SysUser u = baseMapper.selectWithPassword(UserContext.requireId());
        if (u == null || !passwordEncoder.matches(req.oldPassword(), u.getPassword())) {
            throw new BizException("原密码不正确");
        }
        update(Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, passwordEncoder.encode(req.newPassword()))
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getId, u.getId()));
    }

    public void changeStatus(Long userId, EnableStatus status) {
        if (getById(userId) == null) {
            throw BizException.notFound("用户");
        }
        update(Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getStatus, status)
                .eq(SysUser::getId, userId));
    }

    public PageResult<UserVo> pageForAdmin(UserQuery q) {
        LambdaQueryWrapper<SysUser> qw = Wrappers.<SysUser>lambdaQuery()
                .and(q.getKeyword() != null && !q.getKeyword().isBlank(), w -> w
                        .like(SysUser::getNickname, q.getKeyword())
                        .or().like(SysUser::getPhone, q.getKeyword())
                        .or().like(SysUser::getUsername, q.getKeyword()))
                .eq(q.getStatus() != null, SysUser::getStatus, q.getStatus())
                .orderByDesc(SysUser::getCreateTime);
        Page<SysUser> page = page(new Page<>(q.getPageNum(), q.getPageSize()), qw);
        return PageResult.of(page, page.getRecords().stream().map(SysUserService::toVo).toList());
    }

    private static final int DETAIL_APPOINTMENT_LIMIT = 20;

    public UserDetailVo detailForAdmin(Long userId) {
        SysUser u = getById(userId);
        if (u == null) {
            throw BizException.notFound("用户");
        }
        return new UserDetailVo(
                toVo(u),
                petService.listByUser(userId),
                appointmentService.listByUserForAdmin(userId, DETAIL_APPOINTMENT_LIMIT)
        );
    }

    private LoginVo issueToken(SysUser u) {
        LoginUser lu = new LoginUser();
        lu.setId(u.getId());
        lu.setUsername(u.getUsername());
        lu.setName(u.getNickname());
        lu.setAvatar(u.getAvatar());
        lu.setTyp(TokenType.CLIENT);
        lu.setRole(UserRole.USER);
        lu.setTokenVersion(u.getTokenVersion());

        return new LoginVo(
                jwtUtils.create(lu),
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatar(),
                u.getPhone(),
                u.getEmail(),
                TokenType.CLIENT,
                UserRole.USER,
                null,
                null,
                null
        );
    }

    private static UserVo toVo(SysUser u) {
        return new UserVo(
                u.getId(),
                u.getUsername(),
                u.getNickname(),
                u.getAvatar(),
                u.getPhone(),
                u.getEmail(),
                u.getGender(),
                u.getBirthday(),
                u.getCity(),
                u.getAddress(),
                u.getStatus(),
                u.getRemark(),
                u.getLastLoginTime(),
                u.getCreateTime()
        );
    }

    private static @Nullable String emptyToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
