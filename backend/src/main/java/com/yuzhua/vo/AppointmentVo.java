package com.yuzhua.vo;

import com.yuzhua.domain.enums.AppointmentStatus;
import com.yuzhua.domain.enums.CancelSource;
import com.yuzhua.domain.enums.ConsultType;
import com.yuzhua.domain.enums.PayStatus;
import com.yuzhua.domain.enums.SchedulePeriod;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record AppointmentVo(
        @Nullable Long id,
        @Nullable String orderNo,
        @Nullable Long userId,
        @Nullable Long petId,
        @Nullable Long doctorId,
        @Nullable Long departmentId,
        @Nullable Long scheduleId,
        @Nullable ConsultType consultType,
        @Nullable LocalDate appointmentDate,
        @Nullable SchedulePeriod period,
        @Nullable LocalTime startTime,
        @Nullable LocalTime endTime,
        @Nullable Integer queueNo,
        @Nullable String symptom,
        @Nullable String images,
        @Nullable BigDecimal amount,
        @Nullable PayStatus payStatus,
        @Nullable LocalDateTime payTime,
        @Nullable String payType,
        @Nullable AppointmentStatus status,
        @Nullable LocalDateTime acceptTime,
        @Nullable LocalDateTime finishTime,
        @Nullable String cancelReason,
        @Nullable LocalDateTime cancelTime,
        @Nullable CancelSource cancelBy,
        @Nullable Integer isReviewed,
        @Nullable LocalDateTime createTime,
        @Nullable String petName,
        @Nullable String petSpecies,
        @Nullable String petBreed,
        @Nullable String petAvatar,
        @Nullable String doctorName,
        @Nullable String doctorTitle,
        @Nullable String doctorAvatar,
        @Nullable String departmentName,
        @Nullable LocalDateTime payDeadline,
        @Nullable Long sessionId,
        @Nullable Long recordId,
        @Nullable String userNickname,
        @Nullable String userPhone
) {
}
