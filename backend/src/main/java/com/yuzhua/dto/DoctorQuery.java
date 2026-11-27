package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DoctorQuery extends PageQuery {

    private Long departmentId;

    private String keyword;

    private String sort;

    private Integer status;
}
