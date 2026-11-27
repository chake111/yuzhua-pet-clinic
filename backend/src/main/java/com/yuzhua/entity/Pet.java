package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("pet")
public class Pet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;

    private String species;
    private String breed;

    private Integer gender;
    private LocalDate birthday;
    private Integer ageMonth;
    private BigDecimal weight;
    private String color;

    private Integer isNeutered;
    private Integer vaccineStatus;
    private Integer dewormFlag;

    private String avatar;
    private String allergy;
    private String medicalHistory;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
