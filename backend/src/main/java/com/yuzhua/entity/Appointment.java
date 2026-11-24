package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

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
    private Long id;

    private String orderNo;

    @TableField(select = false)
    private String idemKey;

    private Long userId;
    private Long petId;
    private Long doctorId;
    private Long departmentId;
    private Long scheduleId;

    private Integer consultType;
    private LocalDate appointmentDate;
    private Integer period;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer queueNo;

    private String symptom;
    private String images;

    private BigDecimal amount;
    private Integer payStatus;
    private LocalDateTime payTime;
    private String payType;
    private Integer status;

    private LocalDateTime acceptTime;
    private LocalDateTime finishTime;
    private String cancelReason;
    private LocalDateTime cancelTime;
    private String cancelBy;
    private Integer isReviewed;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
