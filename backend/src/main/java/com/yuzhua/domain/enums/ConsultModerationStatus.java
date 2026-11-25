package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ConsultModerationStatus implements CodedEnum<Integer> {
    NORMAL(0),
    INVALID(1),
    VIOLATION(2);

    @EnumValue
    private final Integer code;

    ConsultModerationStatus(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static ConsultModerationStatus fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(ConsultModerationStatus.class, code);
    }
}
