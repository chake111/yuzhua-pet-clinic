package com.yuzhua.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yuzhua.entity.Doctor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({"phone"})
public class DoctorVo extends Doctor {

    private String departmentName;
    private String departmentIcon;
}
