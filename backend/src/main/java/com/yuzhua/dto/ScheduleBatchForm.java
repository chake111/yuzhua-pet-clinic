package com.yuzhua.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
public class ScheduleBatchForm {

    @NotNull(message = "请选择医生")
    private Long doctorId;

    @NotNull(message = "请选择开始日期")
    private LocalDate startDate;

    @NotNull(message = "请选择结束日期")
    private LocalDate endDate;

    @NotEmpty(message = "请选择每周几排班")
    private List<Integer> weekDays;

    @Valid
    @NotEmpty(message = "请至少配置一个时段")
    private List<PeriodTemplate> periods;

    @Data
    public static class PeriodTemplate {
        @NotNull(message = "请选择时段")
        private Integer period;

        @NotNull(message = "请选择开始时间")
        private LocalTime startTime;

        @NotNull(message = "请选择结束时间")
        private LocalTime endTime;

        @NotNull(message = "请填写号源数")
        @Min(value = 1, message = "号源数至少为 1")
        private Integer totalNum;
    }

    private Integer overrideTotalNum;
}
