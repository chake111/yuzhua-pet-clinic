package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhua.domain.enums.EnableStatus;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("department")
public class Department {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable String name;
    private @Nullable String code;
    private @Nullable String icon;
    private @Nullable String description;
    private @Nullable Integer sort;
    private @Nullable EnableStatus status;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
