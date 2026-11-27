package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("admin_user")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable String username;

    @TableField(select = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private @Nullable String password;

    private @Nullable String realName;
    private @Nullable String avatar;
    private @Nullable String phone;
    private @Nullable String email;

    private @Nullable String role;
    private @Nullable Long doctorId;
    private @Nullable Integer status;

    @JsonIgnore
    private @Nullable Integer tokenVersion;

    private @Nullable LocalDateTime lastLoginTime;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @JsonIgnore
    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
