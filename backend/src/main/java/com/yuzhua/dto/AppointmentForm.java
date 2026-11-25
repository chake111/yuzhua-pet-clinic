package com.yuzhua.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record AppointmentForm(
        @NotNull(message = "请选择就诊宠物")
        Long petId,
        @NotNull(message = "请选择医生")
        Long doctorId,
        @NotNull(message = "请选择就诊时段")
        Long scheduleId,
        @NotNull(message = "请描述症状")
        @Size(max = 500, message = "症状描述不超过 500 字")
        String symptom,
        @Nullable String images
) {
}
