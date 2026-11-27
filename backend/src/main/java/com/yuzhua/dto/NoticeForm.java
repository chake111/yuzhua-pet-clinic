package com.yuzhua.dto;

import com.yuzhua.domain.enums.NoticeStatus;
import com.yuzhua.domain.enums.NoticeType;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class NoticeForm {

    private @Nullable String title;
    private @Nullable String cover;
    private @Nullable String summary;
    private @Nullable String content;

    private @Nullable NoticeType type;

    private @Nullable Integer topFlag;

    private @Nullable NoticeStatus status;
}
