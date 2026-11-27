package com.yuzhua.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConsultMessageQuery extends PageQuery {
    @Min(value = 1, message = "beforeId 必须为正数")
    private @Nullable Long beforeId;
}
