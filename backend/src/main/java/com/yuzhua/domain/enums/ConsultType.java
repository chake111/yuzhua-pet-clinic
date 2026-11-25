package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ConsultType implements CodedEnum<Integer> {
    ONLINE(1),
    VISIT(2);

    @EnumValue
    private final Integer code;

    ConsultType(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static ConsultType fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(ConsultType.class, code);
    }
}
