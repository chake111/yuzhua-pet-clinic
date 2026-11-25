package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import com.yuzhua.dto.NoticeForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.Notice;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-公告科普")
@RestController
@RequestMapping("/admin/notice")
@RequiredArgsConstructor
@RequireRole(AdminRole.ADMIN)
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "公告分页。status: 0 草稿 1 已发布 2 已下架")
    @GetMapping("/page")
    public Result<PageResult<Notice>> page(PageQuery query,
                                           @RequestParam(required = false) @Nullable NoticeStatus status,
                                           @RequestParam(required = false) @Nullable NoticeType type,
                                           @RequestParam(required = false) @Nullable String keyword) {
        return Result.ok(noticeService.pageAdmin(query, status, type, keyword));
    }

    @Operation(summary = "公告详情")
    @GetMapping("/{id}")
    public Result<Notice> detail(@PathVariable Long id) {
        return Result.ok(noticeService.require(id));
    }

    @Operation(summary = "新增公告。不传 status 时按草稿保存")
    @PostMapping
    public Result<Notice> create(@RequestBody NoticeForm req) {
        return Result.ok("已保存", noticeService.create(req));
    }

    @Operation(summary = "修改公告")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody NoticeForm req) {
        noticeService.update(id, req);
        return Result.ok("已保存", null);
    }

    @Operation(summary = "发布 / 下架 / 转草稿")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam NoticeStatus status) {
        noticeService.changeStatus(id, status);
        return Result.ok("已更新", null);
    }

    @Operation(summary = "删除公告")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        noticeService.remove(id);
        return Result.ok("已删除", null);
    }
}
