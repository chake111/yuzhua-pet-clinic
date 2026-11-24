package com.yuzhua.controller.client;

import com.yuzhua.common.PageResult;
import com.yuzhua.common.Result;
import com.yuzhua.dto.PageQuery;
import com.yuzhua.service.MedicalRecordService;
import com.yuzhua.vo.MedicalRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户端-我的病历")
@RestController
@RequestMapping("/client/record")
@RequiredArgsConstructor
public class RecordController {

    private final MedicalRecordService recordService;

    @Operation(summary = "我的病历分页")
    @GetMapping("/page")
    public Result<PageResult<MedicalRecordVo>> page(PageQuery query) {
        return Result.ok(recordService.pageMine(query));
    }

    @Operation(summary = "病历详情（含宠物与医生名字）")
    @GetMapping("/{id}")
    public Result<MedicalRecordVo> detail(@PathVariable Long id) {
        return Result.ok(recordService.detail(id));
    }
}
