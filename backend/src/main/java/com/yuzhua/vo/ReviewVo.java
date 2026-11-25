package com.yuzhua.vo;

import com.yuzhua.domain.enums.EnableStatus;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ReviewVo(
        @Nullable Long id,
        @Nullable Long appointmentId,
        @Nullable Long doctorId,
        @Nullable Long departmentId,
        @Nullable Integer rating,
        @Nullable Integer attitudeScore,
        @Nullable Integer effectScore,
        @Nullable String content,
        @Nullable String images,
        @Nullable Integer isAnonymous,
        @Nullable String reply,
        @Nullable LocalDateTime replyTime,
        @Nullable EnableStatus status,
        @Nullable LocalDateTime createTime,
        @Nullable String userNickname,
        @Nullable String userAvatar,
        @Nullable String doctorName,
        @Nullable String doctorTitle,
        @Nullable String orderNo,
        @Nullable LocalDate appointmentDate,
        @Nullable String petName,
        @Nullable String petSpecies
) {
}
