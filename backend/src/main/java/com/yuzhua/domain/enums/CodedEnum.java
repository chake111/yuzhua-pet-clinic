package com.yuzhua.domain.enums;

import org.jspecify.annotations.Nullable;

public interface CodedEnum<C> {

    C code();

    static <E extends Enum<E> & CodedEnum<C>, C> E fromCode(Class<E> type, @Nullable C code) {
        if (code == null) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " code: null");
        }
        E[] constants = type.getEnumConstants();
        if (constants != null) {
            for (E constant : constants) {
                if (code.equals(constant.code())) {
                    return constant;
                }
            }
        }
        throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " code: " + code);
    }
}
