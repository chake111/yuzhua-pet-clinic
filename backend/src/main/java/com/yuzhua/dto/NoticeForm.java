package com.yuzhua.dto;

import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record NoticeForm(
        @NotBlank(message = "请填写公告标题")
        @Size(max = 120, message = "公告标题不超过 120 字")
        String title,
        @Nullable String cover,
        @Nullable String summary,
        @Nullable String content,
        @Nullable NoticeType type,
        @Nullable Integer topFlag,
        @Nullable NoticeStatus status
) {
}
