package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.dto.DoctorForm;
import com.yuzhua.dto.DoctorQuery;
import com.yuzhua.entity.Doctor;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.DoctorService;
import com.yuzhua.vo.DoctorVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-医生")
@RestController
@RequestMapping("/admin/doctor")
@RequiredArgsConstructor
@RequireRole(AdminRole.ADMIN)
public class DoctorController {

    private final DoctorService doctorService;

    @Operation(summary = "医生分页（含停诊的）")
    @GetMapping("/page")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    public Result<PageResult<DoctorVo>> page(DoctorQuery query) {
        return Result.ok(doctorService.pageVo(query, false));
    }

    @Operation(summary = "医生详情（编辑表单用，含手机号）")
    @GetMapping("/{id}")
    public Result<Doctor> detail(@PathVariable Long id) {
        return Result.ok(doctorService.require(id));
    }

    @Operation(summary = "新增医生")
    @PostMapping
    public Result<Doctor> create(@RequestBody DoctorForm req) {
        return Result.ok("已新增", doctorService.create(req));
    }

    @Operation(summary = "修改医生（评分/接诊量/在线状态由系统维护，不在请求表单里）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DoctorForm req) {
        doctorService.update(id, req);
        return Result.ok("已保存", null);
    }

    @Operation(summary = "停诊/恢复出诊。status: 0 停诊 1 正常")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam EnableStatus status) {
        doctorService.changeStatus(id, status);
        return Result.ok("已更新", null);
    }

    @Operation(summary = "删除医生")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        doctorService.remove(id);
        return Result.ok("已删除", null);
    }

    @Operation(summary = "重算该医生的评分与接诊量（评价数据被直接改过后可手工触发）")
    @PutMapping("/{id}/refresh-stats")
    public Result<Void> refreshStats(@PathVariable Long id) {
        doctorService.require(id);
        doctorService.refreshStats(id);
        return Result.ok("已重算", null);
    }
}
