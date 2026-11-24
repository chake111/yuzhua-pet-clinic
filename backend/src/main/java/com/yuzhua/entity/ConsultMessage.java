package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("consult_message")
public class ConsultMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_DOCTOR = "DOCTOR";
    public static final String ROLE_SYSTEM = "SYSTEM";

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_SUGGEST = "SUGGEST";

    public static final int HIDE_NO = 0;
    public static final int HIDE_YES = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long senderId;
    private String senderRole;
    private String senderName;
    private String senderAvatar;
    private String msgType;
    private String content;
    private Integer isRead;

    private Integer hidden;
    private String hiddenReason;
    private Long hiddenBy;
    private LocalDateTime hiddenTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
