package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class ReplyForm {

    @NotBlank(message = "请填写回复内容")
    private @Nullable String reply;
}
