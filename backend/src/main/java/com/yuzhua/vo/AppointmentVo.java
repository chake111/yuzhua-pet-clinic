package com.yuzhua.vo;

import com.yuzhua.entity.Appointment;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentVo extends Appointment {

    private @Nullable String petName;
    private @Nullable String petSpecies;
    private @Nullable String petBreed;
    private @Nullable String petAvatar;

    private @Nullable String doctorName;
    private @Nullable String doctorTitle;
    private @Nullable String doctorAvatar;
    private @Nullable String departmentName;

    private @Nullable LocalDateTime payDeadline;

    private @Nullable Long sessionId;

    private @Nullable Long recordId;

    private @Nullable String userNickname;
    private @Nullable String userPhone;
}
