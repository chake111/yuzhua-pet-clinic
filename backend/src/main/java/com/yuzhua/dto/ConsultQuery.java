package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConsultQuery extends PageQuery {

    private Integer status;

    private Integer moderateStatus;
}
