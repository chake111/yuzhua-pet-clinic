package com.yuzhua.controller.client;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.dto.ConsultForm;
import com.yuzhua.dto.ConsultQuery;
import com.yuzhua.dto.ConsultMessageQuery;
import com.yuzhua.service.ConsultService;
import com.yuzhua.vo.ConsultMessageVo;
import com.yuzhua.vo.ConsultSessionVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户端-图文问诊")
@RestController
@RequestMapping("/client/consult")
@RequiredArgsConstructor
public class ConsultController {

    private final ConsultService consultService;

    @Operation(summary = "发起图文问诊（免费，选宠物 + 描述症状直建会话）")
    @PostMapping("/sessions")
    public Result<ConsultSessionVo> create(@RequestBody @Valid ConsultForm form) {
        return Result.ok("已发起，等待医生接诊", consultService.create(form));
    }

    @Operation(summary = "我的问诊会话（按最后一条消息时间倒序）")
    @GetMapping("/sessions")
    public Result<PageResult<ConsultSessionVo>> sessions(ConsultQuery query) {
        return Result.ok(consultService.pageMine(query));
    }

    @Operation(summary = "问诊会话详情")
    @GetMapping("/sessions/{id}")
    public Result<ConsultSessionVo> detail(@PathVariable Long id) {
        return Result.ok(consultService.detailMine(id));
    }

    @Operation(summary = "会话历史消息，新→旧分页")
    @GetMapping("/{id}/messages")
    public Result<PageResult<ConsultMessageVo>> messages(@PathVariable Long id, @Valid ConsultMessageQuery query) {
        return Result.ok(consultService.history(id, query));
    }

    @Operation(summary = "关闭会话（仅限还没被医生接诊的）")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        consultService.close(id, null);
        return Result.ok("已关闭", null);
    }

    @Operation(summary = "有未读的会话数，用于导航栏红点")
    @GetMapping("/unread")
    public Result<Long> unread() {
        return Result.ok(consultService.unreadTotal());
    }
}
