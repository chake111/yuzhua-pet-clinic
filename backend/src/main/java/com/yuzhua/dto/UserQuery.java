package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    private @Nullable String keyword;

    private @Nullable Integer status;
}
