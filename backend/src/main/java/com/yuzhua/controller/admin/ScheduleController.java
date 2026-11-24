package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.dto.ScheduleBatchForm;
import com.yuzhua.dto.ScheduleEditForm;
import com.yuzhua.dto.ScheduleForm;
import com.yuzhua.entity.DoctorSchedule;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.RequireRole;
import com.yuzhua.security.UserContext;
import com.yuzhua.service.DoctorScheduleService;
import com.yuzhua.vo.ScheduleVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "管理后台-排班号源")
@RestController
@RequestMapping("/admin/schedule")
@RequiredArgsConstructor
@RequireRole(LoginUser.ROLE_ADMIN)
public class ScheduleController {

    private final DoctorScheduleService scheduleService;

    @Operation(summary = "排班分页")
    @GetMapping("/page")
    @RequireRole({LoginUser.ROLE_ADMIN, LoginUser.ROLE_DOCTOR})
    public Result<PageResult<ScheduleVo>> page(
            @RequestParam(required = false) Long doctorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Integer status,
            PageQuery query) {
        // 原因: 医生仅可查看本人排班，忽略传入 doctorId 防止越权查询他人班表
        Long scopedDoctorId = UserContext.require().isDoctor() ? UserContext.requireDoctorId() : doctorId;
        return Result.ok(scheduleService.pageAdmin(scopedDoctorId, start, end, status, query));
    }

    @Operation(summary = "单条排班")
    @PostMapping
    public Result<DoctorSchedule> create(@Valid @RequestBody ScheduleForm req) {
        return Result.ok("已排班", scheduleService.create(req));
    }

    @Operation(summary = "按星期模板批量排班")
    @PostMapping("/batch")
    public Result<DoctorScheduleService.BatchSummary> batch(@Valid @RequestBody ScheduleBatchForm req) {
        DoctorScheduleService.BatchSummary s = scheduleService.batchCreate(req);
        return Result.ok("新增 " + s.created() + " 条，跳过 " + s.skipped() + " 条已存在", s);
    }

    @Operation(summary = "修改排班（不含停诊状态，停诊/恢复请用 /stop 与 /resume）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ScheduleEditForm req) {
        scheduleService.update(id, req);
        return Result.ok("已保存", null);
    }

    @Operation(summary = "停诊：关号但保留已成交预约")
    @PutMapping("/{id}/stop")
    public Result<Void> stop(@PathVariable Long id) {
        scheduleService.stopClinic(id);
        return Result.ok("已停诊", null);
    }

    @Operation(summary = "恢复排班出诊状态，保留当前号源余量")
    @PutMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable Long id) {
        scheduleService.resumeClinic(id);
        return Result.ok("已恢复", null);
    }

    @Operation(summary = "删除排班")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        scheduleService.remove(id);
        return Result.ok("已删除", null);
    }
}
