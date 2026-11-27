package com.yuzhua.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class NoticeForm {

    private @Nullable String title;
    private @Nullable String cover;
    private @Nullable String summary;
    private @Nullable String content;

    private @Nullable Integer type;

    private @Nullable Integer topFlag;

    private @Nullable Integer status;
}
