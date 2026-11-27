package com.yuzhua.dto;

import com.yuzhua.domain.enums.AppointmentStatus;
import com.yuzhua.domain.enums.PayStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentQuery extends PageQuery {

    private @Nullable Long doctorId;
    private @Nullable Long userId;
    private @Nullable AppointmentStatus status;
    private @Nullable PayStatus payStatus;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private @Nullable LocalDate start;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private @Nullable LocalDate end;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private @Nullable LocalDate day;
}
