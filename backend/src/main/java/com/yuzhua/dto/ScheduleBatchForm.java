package com.yuzhua.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
public class ScheduleBatchForm {

    @NotNull(message = "请选择医生")
    private @Nullable Long doctorId;

    @NotNull(message = "请选择开始日期")
    private @Nullable LocalDate startDate;

    @NotNull(message = "请选择结束日期")
    private @Nullable LocalDate endDate;

    @NotEmpty(message = "请选择每周几排班")
    private @Nullable List<Integer> weekDays;

    @Valid
    @NotEmpty(message = "请至少配置一个时段")
    private @Nullable List<PeriodTemplate> periods;

    @Data
    public static class PeriodTemplate {
        @NotNull(message = "请选择时段")
        private @Nullable Integer period;

        @NotNull(message = "请选择开始时间")
        private @Nullable LocalTime startTime;

        @NotNull(message = "请选择结束时间")
        private @Nullable LocalTime endTime;

        @NotNull(message = "请填写号源数")
        @Min(value = 1, message = "号源数至少为 1")
        private @Nullable Integer totalNum;
    }

    private @Nullable Integer overrideTotalNum;
}
