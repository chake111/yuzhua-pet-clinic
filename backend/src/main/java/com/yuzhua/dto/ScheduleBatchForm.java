package com.yuzhua.dto;

import com.yuzhua.domain.enums.SchedulePeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ScheduleBatchForm(
        @NotNull(message = "请选择医生")
        Long doctorId,
        @NotNull(message = "请选择开始日期")
        LocalDate startDate,
        @NotNull(message = "请选择结束日期")
        LocalDate endDate,
        @NotEmpty(message = "请选择每周几排班")
        List<Integer> weekDays,
        @Valid
        @NotEmpty(message = "请至少配置一个时段")
        List<PeriodTemplate> periods,
        @Nullable Integer overrideTotalNum
) {

    public ScheduleBatchForm {
        weekDays = weekDays == null ? List.of() : List.copyOf(weekDays);
        periods = periods == null ? List.of() : List.copyOf(periods);
    }

    public record PeriodTemplate(
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
}
