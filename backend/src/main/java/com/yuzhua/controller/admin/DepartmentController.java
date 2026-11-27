package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.dto.DepartmentForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Department;
import com.yuzhua.security.LoginUser;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台-科室")
@RestController
@RequestMapping("/admin/department")
@RequiredArgsConstructor
@RequireRole(LoginUser.ROLE_ADMIN)
public class DepartmentController {

    private final DepartmentService departmentService;

    @Operation(summary = "科室分页")
    @GetMapping("/page")
    @RequireRole({LoginUser.ROLE_ADMIN, LoginUser.ROLE_DOCTOR})
    public Result<PageResult<Department>> page(PageQuery query,
                                               @RequestParam(required = false) String keyword) {
        return Result.ok(departmentService.page(query, keyword));
    }

    @Operation(summary = "全部科室（下拉选项用）")
    @GetMapping("/list")
    @RequireRole({LoginUser.ROLE_ADMIN, LoginUser.ROLE_DOCTOR})
    public Result<List<Department>> list() {
        return Result.ok(departmentService.listEnabled());
    }

    @Operation(summary = "新增科室")
    @PostMapping
    public Result<Department> create(@RequestBody DepartmentForm req) {
        return Result.ok("已新增", departmentService.create(req));
    }

    @Operation(summary = "修改科室")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody DepartmentForm req) {
        departmentService.update(id, req);
        return Result.ok("已保存", null);
    }

    @Operation(summary = "启用/停用科室。status: 0 停用 1 启用")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        departmentService.changeStatus(id, status);
        return Result.ok("已更新", null);
    }

    @Operation(summary = "删除科室（有医生挂靠时会被拒绝）")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        departmentService.remove(id);
        return Result.ok("已删除", null);
    }
}
