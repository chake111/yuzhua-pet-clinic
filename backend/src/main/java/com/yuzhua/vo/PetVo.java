package com.yuzhua.vo;

import com.yuzhua.entity.Pet;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;

@Data
@EqualsAndHashCode(callSuper = true)
public class PetVo extends Pet {

    private @Nullable String userNickname;
    private @Nullable String userPhone;
}
