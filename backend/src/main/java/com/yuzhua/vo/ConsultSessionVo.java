package com.yuzhua.vo;

import com.yuzhua.entity.ConsultSession;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConsultSessionVo extends ConsultSession {

    private @Nullable String petName;
    private @Nullable String doctorName;
    private @Nullable String doctorAvatar;
    private @Nullable String userNickname;
    private @Nullable String userPhone;

    private @Nullable Integer myUnread;
}
