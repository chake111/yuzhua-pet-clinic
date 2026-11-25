package com.yuzhua.controller.admin;

import com.yuzhua.common.BizException;
import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.domain.enums.AdminRole;
import com.yuzhua.dto.MedicalRecordForm;
import com.yuzhua.dto.RecordQuery;
import com.yuzhua.security.RequireRole;
import com.yuzhua.service.MedicalRecordService;
import com.yuzhua.vo.MedicalRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台-病历处方")
@RestController
@RequestMapping("/admin/record")
@RequiredArgsConstructor
public class AdminRecordController {

    private final MedicalRecordService recordService;

    @Operation(summary = "病历分页（医生账号自动限定为本人）")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @GetMapping("/page")
    public Result<PageResult<MedicalRecordVo>> page(RecordQuery query) {
        return Result.ok(recordService.pageForStaff(query));
    }

    @Operation(summary = "某张预约单上的病历，未写则为空")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @GetMapping("/appointment/{appointmentId}")
    public Result<MedicalRecordVo> ofAppointment(@PathVariable Long appointmentId) {
        return Result.ok(recordService.ofAppointmentForStaff(appointmentId));
    }

    @Operation(summary = "病历详情")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @GetMapping("/{id}")
    public Result<MedicalRecordVo> detail(@PathVariable Long id) {
        return Result.ok(recordService.detail(id));
    }

    @Operation(summary = "书写 / 修订病历（诊断结论必填，仅医生账号，R4）")
    @RequireRole(AdminRole.DOCTOR)
    @PostMapping("/appointment/{appointmentId}")
    public Result<MedicalRecordVo> save(@PathVariable Long appointmentId, @Valid @RequestBody MedicalRecordForm req) {
        return Result.ok("病历已保存", recordService.saveOrUpdateForAppointment(appointmentId, req));
    }

    @Operation(summary = "删除病历（已关闭：后台不得删除诊疗记录）")
    @RequireRole({AdminRole.ADMIN, AdminRole.DOCTOR})
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        throw BizException.forbidden("病历是诊疗记录，后台不提供删除；需要更正由医生在原单上修订");
    }
}
