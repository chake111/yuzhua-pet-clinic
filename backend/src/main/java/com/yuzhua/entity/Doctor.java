package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("doctor")
public class Doctor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String avatar;
    private Integer gender;

    private String phone;

    private String title;
    private Long departmentId;
    private String specialty;
    private String introduction;
    private Integer workYears;
    private BigDecimal consultationFee;

    private BigDecimal rating;
    private Integer reviewCount;
    private Integer appointmentCount;

    private Integer onlineStatus;

    private Integer status;
    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
