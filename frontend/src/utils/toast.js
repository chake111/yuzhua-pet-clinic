/**
 * 首屏关键路径（http.js、Login.vue、admin/Login.vue）轻量原生提示条。
 *
 * 复用 Element Plus 的 .el-message / .el-message--<type> DOM 结构与 main.css 令牌变量，
 * 避免为了错误/登录提示在首屏入口静态捆绑 element-plus 的 ElMessage 及其图标/Popper 依赖链，
 * 同时保证断网报错时无需发起异步 chunk 请求即可立即展示提示。
 */
const activeToasts = []
const BASE_TOP = 20
const GAP = 16
const DEFAULT_DURATION = 3000

function layoutToasts() {
  let top = BASE_TOP
  for (const el of activeToasts) {
    el.style.top = `${top}px`
    top += (el.offsetHeight || 42) + GAP
  }
}

export function showToast(options, fallbackType = 'info') {
  if (typeof document === 'undefined' || !document.body) return
  const normalized = typeof options === 'string'
    ? { message: options, type: fallbackType }
    : { type: fallbackType, ...(options || {}) }
  const text = String(normalized.message ?? '').trim()
  if (!text) return
  const type = ['success', 'warning', 'error', 'info'].includes(normalized.type) ? normalized.type : 'info'
  const duration = typeof normalized.duration === 'number' ? normalized.duration : DEFAULT_DURATION

  const el = document.createElement('div')
  el.className = `el-message el-message--${type} is-center`
  el.setAttribute('role', 'alert')
  el.style.zIndex = '3000'

  const content = document.createElement('p')
  content.className = 'el-message__content'
  content.textContent = text
  el.appendChild(content)

  document.body.appendChild(el)
  activeToasts.push(el)
  layoutToasts()

  const close = () => {
    const idx = activeToasts.indexOf(el)
    if (idx >= 0) {
      activeToasts.splice(idx, 1)
      layoutToasts()
    }
    el.remove()
  }

  if (duration > 0) {
    setTimeout(close, duration)
  }
  return { close }
}

export const toast = Object.assign(
  (options) => showToast(options, 'info'),
  {
    success: (message) => showToast(message, 'success'),
    warning: (message) => showToast(message, 'warning'),
    info: (message) => showToast(message, 'info'),
    error: (message) => showToast(message, 'error')
  }
)
