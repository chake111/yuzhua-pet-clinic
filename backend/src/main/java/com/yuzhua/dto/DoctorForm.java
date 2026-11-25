package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

public record DoctorForm(
        @NotBlank(message = "请填写医生姓名")
        @Size(max = 50, message = "医生姓名不超过 50 字")
        String name,
        @Nullable String avatar,
        @Nullable Integer gender,
        @Nullable String phone,
        @Nullable String title,
        @NotNull(message = "请选择所属科室")
        Long departmentId,
        @Nullable String specialty,
        @Nullable String introduction,
        @Nullable Integer workYears,
        @DecimalMin(value = "0", message = "问诊费不能为负数")
        @Nullable BigDecimal consultationFee,
        @Nullable EnableStatus status,
        @Nullable Integer sort
) {
}
