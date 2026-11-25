package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum EnableStatus implements CodedEnum<Integer> {
    DISABLED(0),
    ENABLED(1);

    @EnumValue
    private final Integer code;

    EnableStatus(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static EnableStatus fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(EnableStatus.class, code);
    }
}
