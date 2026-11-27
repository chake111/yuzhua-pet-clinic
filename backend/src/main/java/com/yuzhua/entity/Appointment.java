package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("appointment")
public class Appointment {

    public static final int ST_PENDING = 0;
    public static final int ST_ACCEPTED = 1;
    public static final int ST_FINISHED = 2;
    public static final int ST_CANCELLED = 3;
    public static final int ST_MISSED = 4;

    public static final int CT_ONLINE = 1;
    public static final int CT_VISIT = 2;

    public static final int PAY_NONE = 0;
    public static final int PAY_PAID = 1;
    public static final int PAY_REFUNDED = 2;

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable String orderNo;

    @TableField(select = false)
    private @Nullable String idemKey;

    private @Nullable Long userId;
    private @Nullable Long petId;
    private @Nullable Long doctorId;
    private @Nullable Long departmentId;
    private @Nullable Long scheduleId;

    private @Nullable Integer consultType;
    private @Nullable LocalDate appointmentDate;
    private @Nullable Integer period;
    private @Nullable LocalTime startTime;
    private @Nullable LocalTime endTime;
    private @Nullable Integer queueNo;

    private @Nullable String symptom;
    private @Nullable String images;

    private @Nullable BigDecimal amount;
    private @Nullable Integer payStatus;
    private @Nullable LocalDateTime payTime;
    private @Nullable String payType;
    private @Nullable Integer status;

    private @Nullable LocalDateTime acceptTime;
    private @Nullable LocalDateTime finishTime;
    private @Nullable String cancelReason;
    private @Nullable LocalDateTime cancelTime;
    private @Nullable String cancelBy;
    private @Nullable Integer isReviewed;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
