package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class DepartmentForm {

    private @Nullable String name;
    private @Nullable String code;
    private @Nullable String icon;
    private @Nullable String description;
    private @Nullable Integer sort;

    private @Nullable EnableStatus status;
}
