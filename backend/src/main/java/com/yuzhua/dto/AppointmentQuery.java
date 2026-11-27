package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentQuery extends PageQuery {

    private Long doctorId;
    private Long userId;
    private Integer status;
    private Integer payStatus;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate day;
}
