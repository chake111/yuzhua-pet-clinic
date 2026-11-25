package com.yuzhua.dto;

import com.yuzhua.domain.enums.ConsultModerationStatus;
import com.yuzhua.domain.enums.ConsultSessionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConsultQuery extends PageQuery {

    private @Nullable ConsultSessionStatus status;

    private @Nullable ConsultModerationStatus moderateStatus;
}
