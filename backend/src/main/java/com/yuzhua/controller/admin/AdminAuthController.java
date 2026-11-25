package com.yuzhua.controller.admin;

import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.dto.AuthDto;
import com.yuzhua.security.PublicEndpoint;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.AdminUserService;
import com.yuzhua.vo.LoginVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-认证")
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminUserService adminUserService;

    /** 后台账号登录公开写入口：校验凭证后签发后台 JWT，受网关限流约束。 */
    @Operation(summary = "后台登录")
    @PublicEndpoint(allowMutation = true)
    @PostMapping("/login")
    public Result<LoginVo> login(@Valid @RequestBody AuthDto.Login req) {
        return Result.ok(adminUserService.login(req));
    }

    @Operation(summary = "当前登录账号")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @GetMapping("/profile")
    public Result<LoginVo> profile() {
        return Result.ok(adminUserService.current());
    }

    @Operation(summary = "修改密码")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody AuthDto.ChangePassword req) {
        adminUserService.changePassword(req);
        return Result.ok("密码已修改，请重新登录", null);
    }
}
