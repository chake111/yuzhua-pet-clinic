package com.yuzhua.vo;

import com.yuzhua.entity.Pet;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PetVo extends Pet {

    private String userNickname;
    private String userPhone;
}
