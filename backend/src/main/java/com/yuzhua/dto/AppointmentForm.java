package com.yuzhua.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppointmentForm {

    @NotNull(message = "请选择就诊宠物")
    private Long petId;

    @NotNull(message = "请选择医生")
    private Long doctorId;

    @NotNull(message = "请选择就诊时段")
    private Long scheduleId;

    @NotNull(message = "请描述症状")
    @Size(max = 500, message = "症状描述不超过 500 字")
    private String symptom;

    private String images;
}
