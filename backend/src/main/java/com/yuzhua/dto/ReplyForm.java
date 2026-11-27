package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyForm(
        @NotBlank(message = "请填写回复内容")
        @Size(max = 500, message = "回复不超过 500 字")
        String reply
) {
}
