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
import java.time.LocalDateTime;

@Data
@TableName("doctor")
public class Doctor {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable String name;
    private @Nullable String avatar;
    private @Nullable Integer gender;

    private @Nullable String phone;

    private @Nullable String title;
    private @Nullable Long departmentId;
    private @Nullable String specialty;
    private @Nullable String introduction;
    private @Nullable Integer workYears;
    private @Nullable BigDecimal consultationFee;

    private @Nullable BigDecimal rating;
    private @Nullable Integer reviewCount;
    private @Nullable Integer appointmentCount;

    private @Nullable Integer onlineStatus;

    private @Nullable Integer status;
    private @Nullable Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
