package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class DoctorQuery extends PageQuery {

    private @Nullable Long departmentId;

    private @Nullable String keyword;

    private @Nullable String sort;

    private @Nullable Integer status;
}
