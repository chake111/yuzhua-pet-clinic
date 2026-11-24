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

@Data
@TableName("pet")
public class Pet {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable Long userId;
    private @Nullable String name;

    private @Nullable String species;
    private @Nullable String breed;

    private @Nullable Integer gender;
    private @Nullable LocalDate birthday;
    private @Nullable Integer ageMonth;
    private @Nullable BigDecimal weight;
    private @Nullable String color;

    private @Nullable Integer isNeutered;
    private @Nullable Integer vaccineStatus;
    private @Nullable Integer dewormFlag;

    private @Nullable String avatar;
    private @Nullable String allergy;
    private @Nullable String medicalHistory;
    private @Nullable String remark;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
