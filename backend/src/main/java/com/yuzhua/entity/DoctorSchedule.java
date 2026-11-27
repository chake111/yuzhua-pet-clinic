package com.yuzhua.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.SchedulePeriod;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("doctor_schedule")
public class DoctorSchedule {

    @TableId(type = IdType.AUTO)
    private @Nullable Long id;

    private @Nullable Long doctorId;
    private @Nullable LocalDate workDate;

    private @Nullable SchedulePeriod period;
    private @Nullable LocalTime startTime;
    private @Nullable LocalTime endTime;
    private @Nullable Integer totalNum;
    private @Nullable Integer remainNum;

    private @Nullable Integer lastQueueNo;

    private @Nullable EnableStatus status;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable LocalDateTime updateTime;
}
