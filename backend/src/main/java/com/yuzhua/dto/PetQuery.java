package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class PetQuery extends PageQuery {

    private @Nullable String keyword;
}
