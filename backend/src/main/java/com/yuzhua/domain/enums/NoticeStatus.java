package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum NoticeStatus implements CodedEnum<Integer> {
    DRAFT(0),
    PUBLISHED(1),
    OFFLINE(2);

    @EnumValue
    private final Integer code;

    NoticeStatus(Integer code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public Integer code() {
        return code;
    }

    @JsonCreator
    public static NoticeStatus fromCode(@Nullable Integer code) {
        return CodedEnum.fromCode(NoticeStatus.class, code);
    }
}
