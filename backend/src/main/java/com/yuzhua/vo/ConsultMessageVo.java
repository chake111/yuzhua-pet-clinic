package com.yuzhua.vo;

import com.yuzhua.domain.enums.ConsultMsgType;
import com.yuzhua.domain.enums.ConsultSenderRole;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

public record ConsultMessageVo(
        @Nullable Long id,
        @Nullable Long sessionId,
        @Nullable Long senderId,
        @Nullable ConsultSenderRole senderRole,
        @Nullable String senderName,
        @Nullable String senderAvatar,
        @Nullable ConsultMsgType msgType,
        @Nullable String content,
        @Nullable Integer isRead,
        @Nullable Integer hidden,
        @Nullable String hiddenReason,
        @Nullable Long hiddenBy,
        @Nullable LocalDateTime hiddenTime,
        @Nullable LocalDateTime createTime
) {
}
