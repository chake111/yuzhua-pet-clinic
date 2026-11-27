// 原因: 首屏关键路径复用原生 DOM 与 .el-message 样式，避免静态捆绑 Element Plus 运行时并支持断网即时提示
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
