package com.yuzhua.dto;

import lombok.Data;

@Data
public class DepartmentForm {

    private String name;
    private String code;
    private String icon;
    private String description;
    private Integer sort;

    private Integer status;
}
