package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.dto.AppointmentQuery;
import com.yuzhua.dto.CancelForm;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.AppointmentService;
import com.yuzhua.vo.AppointmentVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-预约订单")
@RestController
@RequestMapping("/admin/appointment")
@RequiredArgsConstructor
public class AdminAppointmentController {

    private final AppointmentService appointmentService;

    @Operation(summary = "订单分页（医生账号自动限定为本人）")
    @GetMapping("/page")
    public Result<PageResult<AppointmentVo>> page(AppointmentQuery query) {
        return Result.ok(appointmentService.pageForStaff(query));
    }

    @Operation(summary = "订单详情（含患者联系方式）")
    @GetMapping("/{id}")
    public Result<AppointmentVo> detail(@PathVariable Long id) {
        return Result.ok(appointmentService.detail(id));
    }

    @Operation(summary = "接诊：待接诊 + 已支付才能开始（仅医生账号，R4）")
    @RequireRole(LoginUser.ROLE_DOCTOR)
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        appointmentService.accept(id);
        return Result.ok("已开始接诊", null);
    }

    @Operation(summary = "结束问诊（仅医生账号，R4）")
    @RequireRole(LoginUser.ROLE_DOCTOR)
    @PostMapping("/{id}/finish")
    public Result<Void> finish(@PathVariable Long id) {
        appointmentService.finish(id);
        return Result.ok("问诊已结束", null);
    }

    @Operation(summary = "代患者取消（号源退回）")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) @Nullable CancelForm req) {
        appointmentService.cancelByStaff(id, req == null ? null : req.getReason());
        return Result.ok("已取消，号源已退回", null);
    }
}
