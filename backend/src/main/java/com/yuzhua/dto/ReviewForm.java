package com.yuzhua.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record ReviewForm(
        @NotNull(message = "缺少预约单")
        Long appointmentId,
        @NotNull(message = "请打分")
        @Min(value = 1, message = "评分为 1-5 星")
        @Max(value = 5, message = "评分为 1-5 星")
        Integer rating,
        @Min(value = 1, message = "评分为 1-5 星")
        @Max(value = 5, message = "评分为 1-5 星")
        @Nullable Integer attitudeScore,
        @Min(value = 1, message = "评分为 1-5 星")
        @Max(value = 5, message = "评分为 1-5 星")
        @Nullable Integer effectScore,
        @Size(max = 500, message = "评价内容不超过 500 字")
        @Nullable String content,
        @Nullable String images,
        @Nullable Integer isAnonymous
) {
}
