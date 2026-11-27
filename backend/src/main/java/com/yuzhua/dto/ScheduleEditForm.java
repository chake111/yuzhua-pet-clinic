package com.yuzhua.dto;

import com.yuzhua.domain.enums.SchedulePeriod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduleEditForm(
        @NotNull(message = "请选择医生")
        Long doctorId,
        @NotNull(message = "请选择排班日期")
        LocalDate workDate,
        @NotNull(message = "请选择时段")
        SchedulePeriod period,
        @NotNull(message = "请选择开始时间")
        LocalTime startTime,
        @NotNull(message = "请选择结束时间")
        LocalTime endTime,
        @NotNull(message = "请填写号源数")
        @Min(value = 1, message = "号源数至少为 1")
        Integer totalNum
) {
}
