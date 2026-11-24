package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RecordQuery extends PageQuery {

    private Long doctorId;
    private Long petId;
    private String keyword;
}
