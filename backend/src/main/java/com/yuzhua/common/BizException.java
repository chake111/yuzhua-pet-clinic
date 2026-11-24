package com.yuzhua.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String msg) {
        super(msg);
        this.code = 400;
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public static BizException notFound(String what) {
        return new BizException(404, what + "不存在");
    }

    public static BizException forbidden(String msg) {
        return new BizException(403, msg);
    }
}
