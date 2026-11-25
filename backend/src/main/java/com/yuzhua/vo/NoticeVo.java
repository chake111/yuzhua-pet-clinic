package com.yuzhua.vo;

import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

public record NoticeVo(
        @Nullable Long id,
        @Nullable String title,
        @Nullable String cover,
        @Nullable String summary,
        @Nullable String content,
        @Nullable NoticeType type,
        @Nullable Integer topFlag,
        @Nullable NoticeStatus status,
        @Nullable Integer viewCount,
        @Nullable LocalDateTime publishTime,
        @Nullable Long publishBy,
        @Nullable LocalDateTime createTime
) {
}
