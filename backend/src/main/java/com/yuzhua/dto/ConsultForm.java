package com.yuzhua.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class ConsultForm {

    @NotNull(message = "请选择问诊宠物")
    private @Nullable Long petId;

    @NotNull(message = "请选择医生")
    private @Nullable Long doctorId;

    @NotBlank(message = "请描述症状")
    @Size(max = 500, message = "症状描述不超过 500 字")
    private @Nullable String symptom;

    private @Nullable String images;
}
