package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

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
    private Long id;

    private Long appointmentId;
    private Long userId;
    private Long doctorId;
    private Long petId;

    private String symptom;
    private String images;

    private String summary;

    private Integer status;

    private Integer moderateStatus;
    private String moderateReason;
    private Long moderateBy;
    private LocalDateTime moderateTime;

    private String lastMessage;
    private LocalDateTime lastMessageTime;
    private Integer userUnread;
    private Integer doctorUnread;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
