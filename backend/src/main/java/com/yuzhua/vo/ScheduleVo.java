package com.yuzhua.vo;

import com.yuzhua.entity.DoctorSchedule;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScheduleVo extends DoctorSchedule {

    private String doctorName;
    private String doctorTitle;
    private String doctorAvatar;
    private Long departmentId;
    private String departmentName;
    private BigDecimal consultationFee;

    private Boolean doctorAvailable;

    private Long refNum;
}
