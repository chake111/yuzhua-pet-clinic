package com.yuzhua.common;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 批量外键查询辅助：去重收拢 ID 后一次批量装载，空键集合直接短路避免 SQL IN () 语法错误。
 */
public final class BatchLookup {

    private BatchLookup() {
    }

    public static <T> Set<Long> ids(Collection<T> rows, Function<T, @Nullable Long> fn) {
        Set<Long> out = new HashSet<>();
        for (T row : rows) {
            Long id = fn.apply(row);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    public static <T> Map<Long, T> mapByIds(Function<Set<Long>, List<T>> loader, Set<Long> keys,
                                            Function<T, @Nullable Long> idFn) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<Long, T> out = new LinkedHashMap<>();
        for (T item : loader.apply(keys)) {
            Long id = idFn.apply(item);
            if (id != null && out.put(id, item) != null) {
                throw new IllegalStateException("Duplicate key " + id);
            }
        }
        return out;
    }
}
