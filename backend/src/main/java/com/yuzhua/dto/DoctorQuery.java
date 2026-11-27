package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class DoctorQuery extends PageQuery {

    private @Nullable Long departmentId;

    private @Nullable String keyword;

    private @Nullable String sort;

    private @Nullable EnableStatus status;
}
