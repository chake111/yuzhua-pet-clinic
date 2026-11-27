package com.yuzhua.dto;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record ConsultCloseForm(
        @Size(max = 500, message = "问诊小结不超过 500 字")
        @Nullable String summary
) {
}
