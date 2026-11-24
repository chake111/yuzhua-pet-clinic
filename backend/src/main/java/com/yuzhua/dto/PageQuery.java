package com.yuzhua.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class PageQuery {

    private @Nullable Integer pageNum = 1;
    private @Nullable Integer pageSize = 10;

    public Integer getPageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public Integer getPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }
}
