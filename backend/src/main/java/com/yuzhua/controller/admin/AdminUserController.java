package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.dto.UserQuery;
import com.yuzhua.entity.SysUser;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.SysUserService;
import com.yuzhua.vo.UserDetailVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-用户")
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
@RequireRole(LoginUser.ROLE_ADMIN)
public class AdminUserController {

    private final SysUserService sysUserService;

    @Operation(summary = "用户分页（昵称/手机号/账号、状态）")
    @GetMapping("/page")
    public Result<PageResult<SysUser>> page(UserQuery query) {
        return Result.ok(sysUserService.pageForAdmin(query));
    }

    @Operation(summary = "用户详情：账号 + 宠物档案 + 最近预约")
    @GetMapping("/{id}")
    public Result<UserDetailVo> detail(@PathVariable Long id) {
        return Result.ok(sysUserService.detailForAdmin(id));
    }

    @Operation(summary = "禁用/恢复账号。status: 0 禁用 1 正常")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        sysUserService.changeStatus(id, status);
        return Result.ok("已更新", null);
    }
}
