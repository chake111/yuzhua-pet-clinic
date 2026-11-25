package com.yuzhua.controller.admin;

import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.StatsService;
import com.yuzhua.vo.DashboardVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-数据统计")
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "看板汇总：数字卡片 + ECharts 图表数据")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @GetMapping("/dashboard")
    public Result<DashboardVo> dashboard(@RequestParam(required = false) @Nullable Integer days) {
        return Result.ok(statsService.dashboard(days));
    }
}
