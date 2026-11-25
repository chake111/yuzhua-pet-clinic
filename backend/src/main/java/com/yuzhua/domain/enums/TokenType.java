package com.yuzhua.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

public enum TokenType implements CodedEnum<String> {
    CLIENT("CLIENT"),
    ADMIN("ADMIN");

    @EnumValue
    private final String code;

    TokenType(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static TokenType fromCode(@Nullable String code) {
        return CodedEnum.fromCode(TokenType.class, code);
    }
}
