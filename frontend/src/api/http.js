import { toast } from '@/utils/toast'
import { clearToken, getToken } from '@/utils/token'
import { httpFailureAction } from './httpError'

/**
 * 全站唯一的 HTTP 请求封装（基于原生 fetch，零第三方运行时依赖）。
 *
 * 应用业务响应约定 HTTP 状态恒为 200，业务码在 body.code（200/400/401/403/404/405/500），
 * 所以业务成败只看 body.code，不看 res.status。HTTP / 代理 / 网络层错误（502、连接失败、
 * 超时）不适用这套约定，会落到下面的错误分支统一提示。
 * 用户端与后台共用这个实例：带哪个 token 由请求路径前缀决定。
 */
const BASE_URL = '/api'
const DEFAULT_TIMEOUT_MS = 20000

function buildUrl(url, params) {
  const path = `${BASE_URL}${String(url || '')}`
  if (!params || typeof params !== 'object') return path
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item !== undefined && item !== null) search.append(key, String(item))
      }
    } else {
      search.append(key, String(value))
    }
  }
  const qs = search.toString()
  if (!qs) return path
  return `${path}${path.includes('?') ? '&' : '?'}${qs}`
}

async function request(method, url, data, cfg = {}) {
  // 少数接口（如后台里传图片到 /common/upload）需要显式指定身份，走 cfg.kind
  const kind = cfg.kind || (String(url).startsWith('/admin') ? 'admin' : 'client')
  const token = getToken(kind)
  const headers = { ...(cfg.headers || {}) }
  if (token) headers.Authorization = 'Bearer ' + token

  let body
  if (data instanceof FormData) {
    // FormData 交给浏览器自己带 boundary，写死 JSON 头会让上传失败
    delete headers['Content-Type']
    delete headers['content-type']
    body = data
  } else if (data !== undefined && data !== null && method !== 'GET') {
    if (!headers['Content-Type'] && !headers['content-type']) {
      headers['Content-Type'] = 'application/json'
    }
    body = typeof data === 'string' ? data : JSON.stringify(data)
  }

  const controller = new AbortController()
  const timeoutMs = typeof cfg.timeout === 'number' ? cfg.timeout : DEFAULT_TIMEOUT_MS
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  let res
  let payload
  try {
    res = await fetch(buildUrl(url, cfg.params), {
      method,
      headers,
      body,
      credentials: 'same-origin',
      signal: controller.signal
    })
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }
    const text = await res.text()
    payload = text ? JSON.parse(text) : null
  } catch (err) {
    clearTimeout(timer)
    const isTimeout = timedOut || Boolean(err?.message?.includes('timeout'))
    const action = httpFailureAction({
      business: false,
      timeout: isTimeout,
      silent: cfg.silent
    })
    if (action.type === 'notify') {
      toast.error(action.message)
    }
    throw isTimeout ? new Error(`timeout of ${timeoutMs}ms exceeded`) : err
  }
  clearTimeout(timer)

  if (!payload || typeof payload.code === 'undefined') return payload
  if (payload.code === 200) return payload

  const action = httpFailureAction({
    business: true,
    code: payload.code,
    msg: payload.msg,
    silent: cfg.silent
  })
  if (action.type === 'reauth') {
    clearToken(kind)
    const target = kind === 'admin' ? '/admin/login' : '/login'
    if (location.pathname !== target) {
      // 整页跳转而非路由跳转：顺手把内存里的登录态一起清掉，比逐个 reset store 可靠
      location.replace(`${target}?redirect=${encodeURIComponent(location.pathname + location.search)}`)
    }
  } else if (action.type === 'notify') {
    toast.error(action.message)
  }
  throw Object.assign(new Error(payload.msg || '业务失败'), { code: payload.code })
}

const http = {
  get: (url, cfg) => request('GET', url, undefined, cfg),
  post: (url, data, cfg) => request('POST', url, data, cfg),
  put: (url, data, cfg) => request('PUT', url, data, cfg),
  delete: (url, cfg) => request('DELETE', url, undefined, cfg)
}

/** 只取 Result.data；需要 msg 的场景直接用 http */
const unwrap = (p) => p.then((r) => r?.data)

export const get = (url, params, cfg) => unwrap(http.get(url, { ...cfg, params: params ?? cfg?.params }))
export const post = (url, data, cfg) => unwrap(http.post(url, data, cfg))
export const put = (url, data, cfg) => unwrap(http.put(url, data, cfg))
export const del = (url, cfg) => unwrap(http.delete(url, cfg))
/** 公共上传接口只认 file/files 两个参数名；kind 决定带哪一侧的 token */
export const upload = (file, kind) => {
  const fd = new FormData()
  fd.append('file', file)
  return unwrap(http.post('/common/upload', fd, { kind }))
}

export default http
