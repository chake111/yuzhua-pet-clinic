package com.yuzhua.vo;

import org.jspecify.annotations.Nullable;

public record PetDisplayVo(
        @Nullable Long id,
        @Nullable String name,
        @Nullable String species,
        @Nullable String breed,
        @Nullable String avatar
) {
}
