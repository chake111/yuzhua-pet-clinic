package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum ConsultSenderRole implements CodedEnum<String> {
    USER("USER"),
    DOCTOR("DOCTOR"),
    SYSTEM("SYSTEM");

    @EnumValue
    private final String code;

    ConsultSenderRole(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static ConsultSenderRole fromCode(@Nullable String code) {
        return CodedEnum.fromCode(ConsultSenderRole.class, code);
    }
}
