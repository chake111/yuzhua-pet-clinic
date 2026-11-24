package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("consult_session")
public class ConsultSession {

    public static final int ST_PENDING = 0;
    public static final int ST_ACTIVE = 1;
    public static final int ST_CLOSED = 2;

    public static final int MV_NORMAL = 0;
    public static final int MV_INVALID = 1;
    public static final int MV_VIOLATION = 2;

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable Long appointmentId;
    private @Nullable Long userId;
    private @Nullable Long doctorId;
    private @Nullable Long petId;

    private @Nullable String symptom;
    private @Nullable String images;

    private @Nullable String summary;

    private @Nullable Integer status;

    private @Nullable Integer moderateStatus;
    private @Nullable String moderateReason;
    private @Nullable Long moderateBy;
    private @Nullable LocalDateTime moderateTime;

    private @Nullable String lastMessage;
    private @Nullable LocalDateTime lastMessageTime;
    private @Nullable Integer userUnread;
    private @Nullable Integer doctorUnread;
    private @Nullable LocalDateTime startTime;
    private @Nullable LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;
}
