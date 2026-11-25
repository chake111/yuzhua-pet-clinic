package com.yuzhua.vo;

import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.SchedulePeriod;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record ScheduleVo(
        @Nullable Long id,
        @Nullable Long doctorId,
        @Nullable LocalDate workDate,
        @Nullable SchedulePeriod period,
        @Nullable LocalTime startTime,
        @Nullable LocalTime endTime,
        @Nullable Integer totalNum,
        @Nullable Integer remainNum,
        @Nullable Integer lastQueueNo,
        @Nullable EnableStatus status,
        @Nullable LocalDateTime createTime,
        @Nullable String doctorName,
        @Nullable String doctorTitle,
        @Nullable String doctorAvatar,
        @Nullable Long departmentId,
        @Nullable String departmentName,
        @Nullable BigDecimal consultationFee,
        @Nullable Boolean doctorAvailable,
        @Nullable Long refNum
) {
}
