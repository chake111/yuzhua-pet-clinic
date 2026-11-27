package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhua.domain.enums.ConsultMsgType;
import com.yuzhua.domain.enums.ConsultSenderRole;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("consult_message")
public class ConsultMessage {

    public static final int HIDE_NO = 0;
    public static final int HIDE_YES = 1;

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable Long sessionId;

    private @Nullable Long senderId;
    private @Nullable ConsultSenderRole senderRole;
    private @Nullable String senderName;
    private @Nullable String senderAvatar;
    private @Nullable ConsultMsgType msgType;
    private @Nullable String content;
    private @Nullable Integer isRead;

    private @Nullable Integer hidden;
    private @Nullable String hiddenReason;
    private @Nullable Long hiddenBy;
    private @Nullable LocalDateTime hiddenTime;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;
}
