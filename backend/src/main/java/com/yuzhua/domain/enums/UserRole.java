package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum UserRole implements CodedEnum<String> {
    USER("USER"),
    ADMIN("ADMIN"),
    DOCTOR("DOCTOR");

    @EnumValue
    private final String code;

    UserRole(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static UserRole fromCode(@Nullable String code) {
        return CodedEnum.fromCode(UserRole.class, code);
    }
}
