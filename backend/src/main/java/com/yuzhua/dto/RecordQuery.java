package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class RecordQuery extends PageQuery {

    private @Nullable Long doctorId;
    private @Nullable Long petId;
    private @Nullable String keyword;
}
