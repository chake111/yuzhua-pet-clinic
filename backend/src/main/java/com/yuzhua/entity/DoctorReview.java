package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("doctor_review")
public class DoctorReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long appointmentId;
    private Long userId;
    private Long doctorId;
    private Long departmentId;

    private Integer rating;
    private Integer attitudeScore;
    private Integer effectScore;

    private String content;
    private String images;
    private Integer isAnonymous;
    private String reply;
    private LocalDateTime replyTime;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
