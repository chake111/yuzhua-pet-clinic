package com.yuzhua.vo;

import com.yuzhua.domain.enums.TokenType;
import com.yuzhua.domain.enums.UserRole;
import org.jspecify.annotations.Nullable;

public record LoginVo(
        @Nullable String token,
        @Nullable Long id,
        @Nullable String username,
        @Nullable String name,
        @Nullable String avatar,
        @Nullable String phone,
        @Nullable String email,
        @Nullable TokenType typ,
        @Nullable UserRole role,
        @Nullable Long doctorId,
        @Nullable String doctorName,
        @Nullable String departmentName
) {
}
