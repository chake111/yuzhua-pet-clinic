package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record DepartmentForm(
        @NotBlank(message = "请填写科室名称")
        @Size(max = 50, message = "科室名称不超过 50 字")
        String name,
        @Size(max = 50, message = "科室编码不超过 50 字")
        @Nullable String code,
        @Nullable String icon,
        @Nullable String description,
        @Nullable Integer sort,
        @Nullable EnableStatus status
) {
}
