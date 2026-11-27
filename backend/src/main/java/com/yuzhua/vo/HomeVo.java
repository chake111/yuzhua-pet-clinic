package com.yuzhua.vo;

import com.yuzhua.entity.Department;
import com.yuzhua.entity.Notice;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Data
public class HomeVo {

    private @Nullable List<Department> departments;
    private @Nullable List<DoctorVo> doctors;
    private @Nullable List<Notice> notices;
}
