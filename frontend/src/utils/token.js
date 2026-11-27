// 原因: 独立于 Pinia store 存储，避免请求层与状态层之间形成循环依赖
const KEYS = { client: 'yuzhua_client_token', admin: 'yuzhua_admin_token' }

/**
 * 读取指定端（client/admin）的持久化登录令牌，缺失时返回空串。
 *
 * 两套身份键名隔离存放，同一浏览器可同时保持用户端与后台登录态。
 */
export function getToken(kind = 'client') {
  return localStorage.getItem(KEYS[kind]) || ''
}

/**
 * 写入指定端（client/admin）的登录令牌至 localStorage。
 */
export function setToken(kind, token) {
  localStorage.setItem(KEYS[kind], token)
}

/**
 * 清除指定端（client/admin）的登录令牌，用于登出或 401 强制重登。
 */
export function clearToken(kind) {
  localStorage.removeItem(KEYS[kind])
}
