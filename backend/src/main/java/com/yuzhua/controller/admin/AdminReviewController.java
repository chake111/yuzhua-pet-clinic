package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.dto.ReplyForm;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.DoctorReviewService;
import com.yuzhua.vo.ReviewVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-医生评价")
@RestController
@RequestMapping("/admin/review")
@RequiredArgsConstructor
public class AdminReviewController {

    private final DoctorReviewService reviewService;

    @Operation(summary = "评价分页（医生账号自动限定为本人）")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @GetMapping("/page")
    public Result<PageResult<ReviewVo>> page(@RequestParam(required = false) @Nullable Long doctorId,
                                             @RequestParam(required = false) @Nullable EnableStatus status,
                                             PageQuery query) {
        return Result.ok(reviewService.pageForStaff(doctorId, status, query));
    }

    @Operation(summary = "回复评价")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @PostMapping("/{id}/reply")
    public Result<Void> reply(@PathVariable Long id, @Valid @RequestBody ReplyForm req) {
        reviewService.reply(id, req.getReply());
        return Result.ok("已回复", null);
    }

    @Operation(summary = "显示 / 隐藏评价（仅管理员，会重算医生评分）")
    @PutMapping("/{id}/status")
    @RequireRole(AdminRole.ADMIN)
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam EnableStatus status) {
        reviewService.changeStatus(id, status);
        return Result.ok(status == EnableStatus.ENABLED ? "已显示" : "已隐藏", null);
    }
}
