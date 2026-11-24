package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("doctor_schedule")
public class DoctorSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long doctorId;
    private LocalDate workDate;

    private Integer period;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer totalNum;
    private Integer remainNum;

    private Integer lastQueueNo;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
