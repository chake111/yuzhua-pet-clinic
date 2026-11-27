package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ConsultSessionStatus implements CodedEnum<Integer> {
    PENDING(0),
    ACTIVE(1),
    CLOSED(2);

    @EnumValue
    private final Integer code;

    ConsultSessionStatus(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static ConsultSessionStatus fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(ConsultSessionStatus.class, code);
    }
}
