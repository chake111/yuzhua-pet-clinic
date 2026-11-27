package com.yuzhua.vo;

import com.yuzhua.domain.enums.EnableStatus;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DoctorVo(
        @Nullable Long id,
        @Nullable String name,
        @Nullable String avatar,
        @Nullable Integer gender,
        @Nullable String title,
        @Nullable Long departmentId,
        @Nullable String specialty,
        @Nullable String introduction,
        @Nullable Integer workYears,
        @Nullable BigDecimal consultationFee,
        @Nullable BigDecimal rating,
        @Nullable Integer reviewCount,
        @Nullable Integer appointmentCount,
        @Nullable Integer onlineStatus,
        @Nullable EnableStatus status,
        @Nullable Integer sort,
        @Nullable LocalDateTime createTime,
        @Nullable String departmentName,
        @Nullable String departmentIcon
) {
}
