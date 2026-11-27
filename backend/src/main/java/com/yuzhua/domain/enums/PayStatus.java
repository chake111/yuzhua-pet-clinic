package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum PayStatus implements CodedEnum<Integer> {
    UNPAID(0),
    PAID(1),
    REFUNDED(2);

    @EnumValue
    private final Integer code;

    PayStatus(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static PayStatus fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(PayStatus.class, code);
    }
}
