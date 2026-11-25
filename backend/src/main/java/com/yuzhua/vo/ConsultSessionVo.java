package com.yuzhua.vo;

import com.yuzhua.domain.enums.ConsultModerationStatus;
import com.yuzhua.domain.enums.ConsultSessionStatus;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

public record ConsultSessionVo(
        @Nullable Long id,
        @Nullable Long appointmentId,
        @Nullable Long userId,
        @Nullable Long doctorId,
        @Nullable Long petId,
        @Nullable String symptom,
        @Nullable String images,
        @Nullable String summary,
        @Nullable ConsultSessionStatus status,
        @Nullable ConsultModerationStatus moderateStatus,
        @Nullable String moderateReason,
        @Nullable Long moderateBy,
        @Nullable LocalDateTime moderateTime,
        @Nullable String lastMessage,
        @Nullable LocalDateTime lastMessageTime,
        @Nullable Integer userUnread,
        @Nullable Integer doctorUnread,
        @Nullable LocalDateTime startTime,
        @Nullable LocalDateTime endTime,
        @Nullable LocalDateTime createTime,
        @Nullable String petName,
        @Nullable String doctorName,
        @Nullable String doctorAvatar,
        @Nullable String userNickname,
        @Nullable String userPhone,
        @Nullable Integer myUnread
) {
}
