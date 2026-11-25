package com.yuzhua.controller.client;

import com.yuzhua.common.Result;
import com.yuzhua.dto.ReviewForm;
import com.yuzhua.service.DoctorReviewService;
import com.yuzhua.vo.ReviewVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户端-医生评价")
@RestController
@RequestMapping("/client/review")
@RequiredArgsConstructor
public class ReviewController {

    private final DoctorReviewService reviewService;

    @Operation(summary = "提交评价（仅已完成的预约，一单一评）")
    @PostMapping
    public Result<ReviewVo> submit(@Valid @RequestBody ReviewForm req) {
        return Result.ok("感谢您的评价", reviewService.submit(req));
    }
}
