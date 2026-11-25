package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum AdminRole implements CodedEnum<String> {
    ADMIN("ADMIN"),
    DOCTOR("DOCTOR");

    @EnumValue
    private final String code;

    AdminRole(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static AdminRole fromCode(@Nullable String code) {
        return CodedEnum.fromCode(AdminRole.class, code);
    }
}
