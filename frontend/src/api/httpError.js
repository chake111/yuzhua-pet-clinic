/**
 * HTTP 失败的决策函数：决定提示错误、强制重登或静默忽略。
 *
 * 业务 401 无视 silent 必须返回 reauth 清空登录态并跳转，防止失效会话继续后台轮询；
 * 非 401 且 silent=true 返回 silent，其余按业务错误或网络/超时返回 notify。
 */
export function httpFailureAction({ business, code, msg, timeout = false, silent = false }) {
  if (business && code === 401) {
    return { type: 'reauth' }
  }
  if (silent) {
    return { type: 'silent' }
  }
  if (business) {
    return { type: 'notify', message: msg || '操作未成功' }
  }
  return { type: 'notify', message: timeout ? '请求超时，请稍后重试' : '连接失败，请稍后重试' }
}
