package com.yuzhua.controller.admin;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.domain.enums.ConsultModerationStatus;
import com.yuzhua.dto.ConsultCloseForm;
import com.yuzhua.dto.ConsultQuery;
import com.yuzhua.dto.ConsultMessageQuery;
import com.yuzhua.entity.ConsultMessage;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.ConsultService;
import com.yuzhua.vo.ConsultSessionVo;
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

@Tag(name = "管理后台-图文问诊")
@RestController
@RequestMapping("/admin/consult")
@RequiredArgsConstructor
@RequireRole(AdminRole.DOCTOR)
public class AdminConsultController {

    private final ConsultService consultService;

    @Operation(summary = "我接诊的会话列表（医生）")
    @GetMapping("/sessions")
    @RequireRole(AdminRole.DOCTOR)
    public Result<PageResult<ConsultSessionVo>> sessions(ConsultQuery query) {
        return Result.ok(consultService.pageMine(query));
    }

    @Operation(summary = "问诊会话详情（医生）")
    @GetMapping("/sessions/{id}")
    @RequireRole(AdminRole.DOCTOR)
    public Result<ConsultSessionVo> detail(@PathVariable Long id) {
        return Result.ok(consultService.detailMine(id));
    }

    @Operation(summary = "全部会话分页（管理员只读 + 处置视图）")
    @GetMapping("/page")
    @RequireRole(AdminRole.ADMIN)
    public Result<PageResult<ConsultSessionVo>> page(ConsultQuery query) {
        return Result.ok(consultService.pageForAdmin(query));
    }

    @Operation(summary = "接诊：待接诊会话转为进行中")
    @PostMapping("/{id}/accept")
    @RequireRole(AdminRole.DOCTOR)
    public Result<Void> accept(@PathVariable Long id) {
        consultService.accept(id);
        return Result.ok("已接诊", null);
    }

    @Operation(summary = "结束问诊，可附一段问诊小结（线上只建议、确诊要面诊）")
    @PostMapping("/{id}/close")
    @RequireRole(AdminRole.DOCTOR)
    public Result<Void> close(@PathVariable Long id, @RequestBody(required = false) @Nullable ConsultCloseForm form) {
        consultService.close(id, form == null ? null : form.getSummary());
        return Result.ok("已结束", null);
    }

    @Operation(summary = "会话历史消息，新→旧分页（医生走参与方校验；管理员只读）")
    @GetMapping("/{id}/messages")
    @RequireRole({AdminRole.DOCTOR, AdminRole.ADMIN})
    public Result<PageResult<ConsultMessage>> messages(@PathVariable Long id, @Valid ConsultMessageQuery query) {
        return Result.ok(consultService.historyForViewer(id, query));
    }

    // 原因: 前端已读主走 WS READ 帧，保留受 @RequireRole(DOCTOR) 与会话归属校验保护的幂等 REST 入口供并发竞态回归脚本驱动
    @Operation(summary = "标记已读（医生）")
    @PostMapping("/{id}/read")
    @RequireRole(AdminRole.DOCTOR)
    public Result<Void> read(@PathVariable Long id) {
        consultService.markRead(id);
        return Result.ok("已读", null);
    }

    @Operation(summary = "有未读的会话数（医生）")
    @GetMapping("/unread")
    @RequireRole(AdminRole.DOCTOR)
    public Result<Long> unread() {
        return Result.ok(consultService.unreadTotal());
    }

    @Operation(summary = "屏蔽 / 恢复一条消息（仅管理员；被屏蔽内容对参与方不可见，审计视图仍可见）")
    @PutMapping("/message/{msgId}/hidden")
    @RequireRole(AdminRole.ADMIN)
    public Result<Void> hideMessage(@PathVariable Long msgId,
                                    @RequestParam Integer hidden,
                                    @RequestParam(required = false) @Nullable String reason) {
        consultService.changeMessageHidden(msgId, hidden, reason);
        return Result.ok(hidden == 1 ? "已屏蔽" : "已恢复", null);
    }

    @Operation(summary = "处置会话：1 无效关闭 2 违规屏蔽，0 撤销（仅管理员；非 0 会连带关闭会话）")
    @PutMapping("/{id}/moderate")
    @RequireRole(AdminRole.ADMIN)
    public Result<Void> moderate(@PathVariable Long id,
                                 @RequestParam ConsultModerationStatus moderateStatus,
                                 @RequestParam(required = false) @Nullable String reason) {
        consultService.moderate(id, moderateStatus, reason);
        return Result.ok(moderateStatus == ConsultModerationStatus.NORMAL ? "已撤销处置" : "已处置", null);
    }
}
