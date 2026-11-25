package com.yuzhua.vo;

import java.util.List;

public record UserDetailVo(
        UserVo user,
        List<PetVo> pets,
        List<AppointmentVo> appointments
) {

    public UserDetailVo {
        pets = pets == null ? List.of() : List.copyOf(pets);
        appointments = appointments == null ? List.of() : List.copyOf(appointments);
    }
}
