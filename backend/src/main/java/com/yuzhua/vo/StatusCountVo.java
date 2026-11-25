package com.yuzhua.vo;

import com.yuzhua.domain.enums.AppointmentStatus;

public record StatusCountVo(AppointmentStatus status, long cnt) {
}
