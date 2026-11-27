package com.yuzhua.security;

import com.yuzhua.common.BizException;

/**
 * 当前登录用户上下文。AuthInterceptor 在 preHandle 写入、afterCompletion 清理。
 *
 * 用 ThreadLocal 而非 request attribute，是为了让 Service 层不必层层传参也能拿到操作者。
 *
 * 契约动机: 线程复用时的身份串用风险——线程还回去再领走时 ThreadLocal 里还躺着上一个请求
 * 的身份，afterCompletion 必须 remove，否则下一个请求会拿别人的身份做鉴权和归属判断。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /** 写入当前线程登录态，调用方须在请求/帧处理结束后配合 {@link #clear()} 清理。 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 读取当前线程登录态，未登录时返回 null（不抛异常）。 */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 清理当前线程持有的 ThreadLocal 登录态，防止容器线程池复用串号。 */
    public static void clear() {
        HOLDER.remove();
    }

    /** 取当前登录人，未登录直接抛 401 */
    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw new BizException(401, "请先登录");
        }
        return u;
    }

    /** 读取当前登录账号主键，未登录时抛 401。 */
    public static Long requireId() {
        return require().getId();
    }

    /** 取当前医生的 doctorId；管理员调用则抛 403 */
    public static Long requireDoctorId() {
        LoginUser u = require();
        if (u.getDoctorId() == null) {
            throw BizException.forbidden("当前账号未绑定医生身份");
        }
        return u.getDoctorId();
    }
}
