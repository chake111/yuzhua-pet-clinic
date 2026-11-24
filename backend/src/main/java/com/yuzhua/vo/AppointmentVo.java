package com.yuzhua.vo;

import com.yuzhua.entity.Appointment;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentVo extends Appointment {

    private String petName;
    private String petSpecies;
    private String petBreed;
    private String petAvatar;

    private String doctorName;
    private String doctorTitle;
    private String doctorAvatar;
    private String departmentName;

    private LocalDateTime payDeadline;

    private Long sessionId;

    private Long recordId;

    private String userNickname;
    private String userPhone;
}
