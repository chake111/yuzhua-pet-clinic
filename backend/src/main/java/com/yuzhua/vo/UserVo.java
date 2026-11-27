package com.yuzhua.vo;

import com.yuzhua.domain.enums.EnableStatus;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UserVo(
        @Nullable Long id,
        @Nullable String username,
        @Nullable String nickname,
        @Nullable String avatar,
        @Nullable String phone,
        @Nullable String email,
        @Nullable Integer gender,
        @Nullable LocalDate birthday,
        @Nullable String city,
        @Nullable String address,
        @Nullable EnableStatus status,
        @Nullable String remark,
        @Nullable LocalDateTime lastLoginTime,
        @Nullable LocalDateTime createTime
) {
}
