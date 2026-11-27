package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum AppointmentStatus implements CodedEnum<Integer> {
    PENDING(0),
    ACCEPTED(1),
    FINISHED(2),
    CANCELLED(3),
    MISSED(4);

    @EnumValue
    private final Integer code;

    AppointmentStatus(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static AppointmentStatus fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(AppointmentStatus.class, code);
    }
}
