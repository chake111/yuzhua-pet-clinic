package com.yuzhua.vo;

import com.yuzhua.entity.DoctorSchedule;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScheduleVo extends DoctorSchedule {

    private @Nullable String doctorName;
    private @Nullable String doctorTitle;
    private @Nullable String doctorAvatar;
    private @Nullable Long departmentId;
    private @Nullable String departmentName;
    private @Nullable BigDecimal consultationFee;

    private @Nullable Boolean doctorAvailable;

    private @Nullable Long refNum;
}
