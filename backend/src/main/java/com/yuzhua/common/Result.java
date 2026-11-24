package com.yuzhua.common;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * 统一响应信封：HTTP 状态码与业务状态解耦，code=200 表示成功，401/403 分别表示未认证与无权限。
 */
@Getter
public class Result<T> {

    private final Integer code;
    private final String msg;
    private final @Nullable T data;

    private Result(Integer code, String msg, @Nullable T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(@Nullable T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> ok(String msg, @Nullable T data) {
        return new Result<>(200, msg, data);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public static <T> Result<T> unauthorized(String msg) {
        return new Result<>(401, msg, null);
    }

    public static <T> Result<T> forbidden(String msg) {
        return new Result<>(403, msg, null);
    }
}
