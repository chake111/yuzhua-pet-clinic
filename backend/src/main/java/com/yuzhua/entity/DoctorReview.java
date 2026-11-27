package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@TableName("doctor_review")
public class DoctorReview {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable Long appointmentId;
    private @Nullable Long userId;
    private @Nullable Long doctorId;
    private @Nullable Long departmentId;

    private @Nullable Integer rating;
    private @Nullable Integer attitudeScore;
    private @Nullable Integer effectScore;

    private @Nullable String content;
    private @Nullable String images;
    private @Nullable Integer isAnonymous;
    private @Nullable String reply;
    private @Nullable LocalDateTime replyTime;
    private @Nullable Integer status;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private @Nullable Integer deleted;
}
