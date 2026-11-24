package com.yuzhua.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class CancelForm {

    @Size(max = 200, message = "取消原因不超过 200 字")
    private @Nullable String reason;
}
