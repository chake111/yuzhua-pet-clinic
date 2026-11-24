package com.yuzhua.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class ScheduleForm {

    @NotNull(message = "请选择医生")
    private Long doctorId;

    @NotNull(message = "请选择排班日期")
    private LocalDate workDate;

    @NotNull(message = "请选择时段")
    private Integer period;

    @NotNull(message = "请选择开始时间")
    private LocalTime startTime;

    @NotNull(message = "请选择结束时间")
    private LocalTime endTime;

    @NotNull(message = "请填写号源数")
    @Min(value = 1, message = "号源数至少为 1")
    private Integer totalNum;

    @Min(value = 0, message = "状态只能是 0 停诊或 1 正常")
    @Max(value = 1, message = "状态只能是 0 停诊或 1 正常")
    private Integer status;
}
