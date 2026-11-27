package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    private @Nullable String keyword;

    private @Nullable EnableStatus status;
}
