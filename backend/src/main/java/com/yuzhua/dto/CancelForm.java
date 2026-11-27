package com.yuzhua.dto;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record CancelForm(
        @Size(max = 200, message = "取消原因不超过 200 字")
        @Nullable String reason
) {
}
