package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import com.yuzhua.domain.enums.SchedulePeriod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class ScheduleForm {

    @NotNull(message = "请选择医生")
    private @Nullable Long doctorId;

    @NotNull(message = "请选择排班日期")
    private @Nullable LocalDate workDate;

    @NotNull(message = "请选择时段")
    private @Nullable SchedulePeriod period;

    @NotNull(message = "请选择开始时间")
    private @Nullable LocalTime startTime;

    @NotNull(message = "请选择结束时间")
    private @Nullable LocalTime endTime;

    @NotNull(message = "请填写号源数")
    @Min(value = 1, message = "号源数至少为 1")
    private @Nullable Integer totalNum;

    private @Nullable EnableStatus status;
}
