package com.yuzhua.vo;

import com.yuzhua.entity.Department;
import com.yuzhua.entity.Notice;
import lombok.Data;

import java.util.List;

@Data
public class HomeVo {

    private List<Department> departments;
    private List<DoctorVo> doctors;
    private List<Notice> notices;
}
