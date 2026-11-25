package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum NoticeType implements CodedEnum<Integer> {
    NOTICE(1),
    ACTIVITY(2),
    GUIDE(3);

    @EnumValue
    private final Integer code;

    NoticeType(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static NoticeType fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(NoticeType.class, code);
    }
}
