package com.yuzhua.vo;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PetVo(
        @Nullable Long id,
        @Nullable Long userId,
        @Nullable String name,
        @Nullable String species,
        @Nullable String breed,
        @Nullable Integer gender,
        @Nullable LocalDate birthday,
        @Nullable Integer ageMonth,
        @Nullable BigDecimal weight,
        @Nullable String color,
        @Nullable Integer isNeutered,
        @Nullable Integer vaccineStatus,
        @Nullable Integer dewormFlag,
        @Nullable String avatar,
        @Nullable String allergy,
        @Nullable String medicalHistory,
        @Nullable String remark,
        @Nullable LocalDateTime createTime,
        @Nullable String userNickname,
        @Nullable String userPhone
) {
}
