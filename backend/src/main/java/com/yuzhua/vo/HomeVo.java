package com.yuzhua.vo;

import java.util.List;

public record HomeVo(
        List<DepartmentVo> departments,
        List<DoctorVo> doctors,
        List<NoticeVo> notices
) {

    public HomeVo {
        departments = departments == null ? List.of() : List.copyOf(departments);
        doctors = doctors == null ? List.of() : List.copyOf(doctors);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }
}
