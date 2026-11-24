package com.yuzhua.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PetQuery extends PageQuery {

    private String keyword;
}
