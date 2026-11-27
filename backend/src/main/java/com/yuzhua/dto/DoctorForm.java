package com.yuzhua.dto;

import com.yuzhua.domain.enums.EnableStatus;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

@Data
public class DoctorForm {

    private @Nullable String name;
    private @Nullable String avatar;
    private @Nullable Integer gender;
    private @Nullable String phone;
    private @Nullable String title;
    private @Nullable Long departmentId;
    private @Nullable String specialty;
    private @Nullable String introduction;
    private @Nullable Integer workYears;
    private @Nullable BigDecimal consultationFee;

    private @Nullable EnableStatus status;
    private @Nullable Integer sort;
}
