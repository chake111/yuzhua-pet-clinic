package com.yuzhua.vo;

import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class PetDisplayVo {

    private @Nullable Long id;
    private @Nullable String name;
    private @Nullable String species;
    private @Nullable String breed;
    private @Nullable String avatar;
}
