package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum CancelSource implements CodedEnum<String> {
    USER("USER"),
    DOCTOR("DOCTOR"),
    ADMIN("ADMIN"),
    SYSTEM("SYSTEM");

    @EnumValue
    private final String code;

    CancelSource(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static CancelSource fromCode(@Nullable String code) {
        return CodedEnum.fromCode(CancelSource.class, code);
    }
}
