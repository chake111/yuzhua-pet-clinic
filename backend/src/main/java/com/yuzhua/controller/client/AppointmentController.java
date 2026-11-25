package com.yuzhua.controller.client;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AppointmentStatus;
import com.yuzhua.dto.AppointmentForm;
import com.yuzhua.dto.CancelForm;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.entity.MedicalRecord;
import com.yuzhua.service.AppointmentService;
import com.yuzhua.service.MedicalRecordService;
import com.yuzhua.vo.AppointmentVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户端-预约问诊")
@RestController
@RequestMapping("/client/appointment")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final MedicalRecordService recordService;

    @Operation(summary = "下单预约（占用号源，30 分钟内需支付）",
            description = "可选请求头 Idempotency-Key（8–64 字符，仅限字母数字与 . _ : -）；"
                    + "同一用户携带同一个键重放时返回原单，不重复占号")
    @PostMapping
    public Result<AppointmentVo> create(@Valid @RequestBody AppointmentForm req,
                                        @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idemKey) {
        return Result.ok("预约成功，请在有效期内支付", appointmentService.create(req, idemKey));
    }

    @Operation(summary = "模拟支付")
    @PostMapping("/{id}/pay")
    public Result<AppointmentVo> pay(@PathVariable Long id) {
        return Result.ok("支付成功", appointmentService.pay(id));
    }

    @Operation(summary = "取消预约（号源退回池子，已支付则标记退款）")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) @Nullable CancelForm req) {
        appointmentService.cancelByUser(id, req == null ? null : req.getReason());
        return Result.ok("已取消，号源已退回", null);
    }

    @Operation(summary = "我的预约分页")
    @GetMapping("/page")
    public Result<PageResult<AppointmentVo>> page(@RequestParam(required = false) @Nullable AppointmentStatus status,
                                                  PageQuery query) {
        return Result.ok(appointmentService.pageMine(status, query));
    }

    @Operation(summary = "预约详情")
    @GetMapping("/{id}")
    public Result<AppointmentVo> detail(@PathVariable Long id) {
        return Result.ok(appointmentService.detail(id));
    }

    @Operation(summary = "这次预约的病历（医生未写则为空）")
    @GetMapping("/{id}/record")
    public Result<MedicalRecord> record(@PathVariable Long id) {
        return Result.ok(recordService.ofAppointmentForOwner(id));
    }
}
