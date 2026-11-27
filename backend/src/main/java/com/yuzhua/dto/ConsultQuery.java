package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConsultQuery extends PageQuery {

    private @Nullable Integer status;

    private @Nullable Integer moderateStatus;
}
