package com.yuzhua.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DoctorForm {

    private String name;
    private String avatar;
    private Integer gender;
    private String phone;
    private String title;
    private Long departmentId;
    private String specialty;
    private String introduction;
    private Integer workYears;
    private BigDecimal consultationFee;

    private Integer status;
    private Integer sort;
}
