import { toast } from '@/utils/toast'
import { clearToken, getToken } from '@/utils/token'
import { httpFailureAction } from './httpError'

const BASE_URL = '/api'
const DEFAULT_TIMEOUT_MS = 20000

function buildUrl(url, params) {
  const path = `${BASE_URL}${String(url || '')}`
  if (!params || typeof params !== 'object') return path
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    const items = Array.isArray(value) ? value : [value]
    for (const item of items) {
      if (item !== undefined && item !== null) search.append(key, String(item))
    }
  }
  const qs = search.toString()
  return qs ? `${path}${path.includes('?') ? '&' : '?'}${qs}` : path
}

function resolveKind(url, cfg) {
  // 原因: /common/upload 跨端共用，需支持由 cfg.kind 显式指定携带用户端或后台 token
  return cfg.kind || (String(url).startsWith('/admin') ? 'admin' : 'client')
}

function buildRequestInit(method, data, cfg, kind, signal) {
  const token = getToken(kind)
  const headers = { ...(cfg.headers || {}) }
  if (token) headers.Authorization = 'Bearer ' + token

  let body
  if (data instanceof FormData) {
    // 原因: FormData 需由浏览器自动生成 multipart boundary，保留 JSON Content-Type 会导致上传失败
    delete headers['Content-Type']
    delete headers['content-type']
    body = data
  } else if (data !== undefined && data !== null && method !== 'GET') {
    if (!headers['Content-Type'] && !headers['content-type']) {
      headers['Content-Type'] = 'application/json'
    }
    body = typeof data === 'string' ? data : JSON.stringify(data)
  }

  return { method, headers, body, credentials: 'same-origin', signal }
}

function handleTransportError(err, timedOut, timeoutMs, silent) {
  const isTimeout = timedOut || Boolean(err?.message?.includes('timeout'))
  const action = httpFailureAction({ business: false, timeout: isTimeout, silent })
  if (action.type === 'notify') toast.error(action.message)
  throw isTimeout ? new Error(`timeout of ${timeoutMs}ms exceeded`) : err
}

async function fetchWithTimeout(fullUrl, makeInit, timeoutMs, silent) {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  try {
    const res = await fetch(fullUrl, makeInit(controller.signal))
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const text = await res.text()
    return text ? JSON.parse(text) : null
  } catch (err) {
    return handleTransportError(err, timedOut, timeoutMs, silent)
  } finally {
    clearTimeout(timer)
  }
}

function unwrapBusinessPayload(payload, kind, silent) {
  if (!payload || typeof payload.code === 'undefined' || payload.code === 200) return payload
  const action = httpFailureAction({ business: true, code: payload.code, msg: payload.msg, silent })
  if (action.type === 'reauth') {
    clearToken(kind)
    const target = kind === 'admin' ? '/admin/login' : '/login'
    if (location.pathname !== target) {
      // 原因: 整页跳转而非路由跳转，可同步清空内存中的登录态
      location.replace(`${target}?redirect=${encodeURIComponent(location.pathname + location.search)}`)
    }
  } else if (action.type === 'notify') {
    toast.error(action.message)
  }
  throw Object.assign(new Error(payload.msg || '业务失败'), { code: payload.code })
}

async function request(method, url, data, cfg = {}) {
  const kind = resolveKind(url, cfg)
  const timeoutMs = typeof cfg.timeout === 'number' ? cfg.timeout : DEFAULT_TIMEOUT_MS
  const fullUrl = buildUrl(url, cfg.params)
  const payload = await fetchWithTimeout(
    fullUrl,
    (signal) => buildRequestInit(method, data, cfg, kind, signal),
    timeoutMs,
    cfg.silent
  )
  return unwrapBusinessPayload(payload, kind, cfg.silent)
}

const http = {
  get: (url, cfg) => request('GET', url, undefined, cfg),
  post: (url, data, cfg) => request('POST', url, data, cfg),
  put: (url, data, cfg) => request('PUT', url, data, cfg),
  delete: (url, cfg) => request('DELETE', url, undefined, cfg)
}

const unwrap = (p) => p.then((r) => r?.data)

/**
 * 发起 GET 请求并解包 Result.data。
 *
 * 按路径前缀（或 cfg.kind）选择用户/后台 token，默认 20s 超时中止；
 * 业务 401 时无视 cfg.silent 清空 token 并整页跳转登录，其余失败按 cfg.silent 提示后抛错。
 */
export const get = (url, params, cfg) => unwrap(http.get(url, { ...cfg, params: params ?? cfg?.params }))

/**
 * 发起 POST 请求并解包 Result.data。
 *
 * 自动序列化非 FormData 请求体为 JSON；遵循统一超时中止、401 强制重登与业务码异常抛出契约。
 */
export const post = (url, data, cfg) => unwrap(http.post(url, data, cfg))

/**
 * 发起 PUT 请求并解包 Result.data。
 *
 * 自动序列化请求体为 JSON；遵循统一超时中止、401 强制重登与业务码异常抛出契约。
 */
export const put = (url, data, cfg) => unwrap(http.put(url, data, cfg))

/**
 * 发起 DELETE 请求并解包 Result.data。
 *
 * 遵循统一超时中止、401 强制重登与业务码异常抛出契约。
 */
export const del = (url, cfg) => unwrap(http.delete(url, cfg))

/**
 * 上传单个文件到 /common/upload 并解包 Result.data。
 *
 * 公共上传接口只认 file 字段；必须由 kind 决定携带用户端或后台 token，超时与 401 契约同 request。
 */
export const upload = (file, kind) => {
  const fd = new FormData()
  fd.append('file', file)
  return unwrap(http.post('/common/upload', fd, { kind }))
}

/**
 * 全站唯一原生 fetch 请求包装（返回完整 Result 信封，供需读取 msg 的场景使用）。
 *
 * 业务响应 HTTP 恒为 200 且成败仅看 body.code（非 200 抛错并在 401 时强制清 token 重登）；
 * 网络/HTTP 非 2xx/超时统一经 AbortController 与 try/finally 定时器清理后按 httpFailureAction 提示。
 */
export default http
