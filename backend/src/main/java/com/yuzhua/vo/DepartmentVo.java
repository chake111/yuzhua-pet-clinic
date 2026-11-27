package com.yuzhua.vo;

import com.yuzhua.domain.enums.EnableStatus;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

public record DepartmentVo(
        @Nullable Long id,
        @Nullable String name,
        @Nullable String code,
        @Nullable String icon,
        @Nullable String description,
        @Nullable Integer sort,
        @Nullable EnableStatus status,
        @Nullable LocalDateTime createTime
) {
}
