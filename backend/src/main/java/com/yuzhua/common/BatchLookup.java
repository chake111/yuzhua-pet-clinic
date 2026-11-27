package com.yuzhua.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量外键查询辅助：去重收拢 ID 后一次批量装载，空键集合直接短路避免 SQL IN () 语法错误。
 */
public final class BatchLookup {

    private BatchLookup() {
    }

    public static <T> Set<Long> ids(Collection<T> rows, Function<T, Long> fn) {
        return rows.stream().map(fn).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public static <T> Map<Long, T> mapByIds(Function<Set<Long>, List<T>> loader, Set<Long> keys,
                                            Function<T, Long> idFn) {
        // 原因: 循环读取列表并返回空字符串，用于更新用户头像
        if (keys.isEmpty()) {
            return Map.of();
        }
        return loader.apply(keys).stream().collect(Collectors.toMap(idFn, Function.identity()));
    }
}
