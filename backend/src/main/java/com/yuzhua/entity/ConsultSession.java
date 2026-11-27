package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhua.domain.enums.ConsultModerationStatus;
import com.yuzhua.domain.enums.ConsultSessionStatus;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("consult_session")
public class ConsultSession {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable Long appointmentId;
    private @Nullable Long userId;
    private @Nullable Long doctorId;
    private @Nullable Long petId;

    private @Nullable String symptom;
    private @Nullable String images;

    private @Nullable String summary;

    private @Nullable ConsultSessionStatus status;

    private @Nullable ConsultModerationStatus moderateStatus;
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
