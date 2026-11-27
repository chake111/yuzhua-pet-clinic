package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum SchedulePeriod implements CodedEnum<Integer> {
    MORNING(1),
    AFTERNOON(2),
    EVENING(3);

    @EnumValue
    private final Integer code;

    SchedulePeriod(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static SchedulePeriod fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(SchedulePeriod.class, code);
    }
}
