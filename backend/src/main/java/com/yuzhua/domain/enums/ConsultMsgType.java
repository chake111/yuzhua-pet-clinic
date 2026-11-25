package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ConsultMsgType implements CodedEnum<String> {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    SUGGEST("SUGGEST"),
    SYSTEM("SYSTEM");

    @EnumValue
    private final String code;

    ConsultMsgType(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static ConsultMsgType fromCode(@Nullable String code) {
        return CodedEnum.fromCode(ConsultMsgType.class, code);
    }
}
