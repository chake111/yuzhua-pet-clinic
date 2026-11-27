package com.yuzhua.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class ReviewForm {

    @NotNull(message = "缺少预约单")
    private @Nullable Long appointmentId;

    @NotNull(message = "请打分")
    @Min(value = 1, message = "评分为 1-5 星")
    @Max(value = 5, message = "评分为 1-5 星")
    private @Nullable Integer rating;

    @Min(value = 1, message = "评分为 1-5 星")
    @Max(value = 5, message = "评分为 1-5 星")
    private @Nullable Integer attitudeScore;

    @Min(value = 1, message = "评分为 1-5 星")
    @Max(value = 5, message = "评分为 1-5 星")
    private @Nullable Integer effectScore;

    @Size(max = 500, message = "评价内容不超过 500 字")
    private @Nullable String content;

    private @Nullable String images;

    private @Nullable Integer isAnonymous;
}
