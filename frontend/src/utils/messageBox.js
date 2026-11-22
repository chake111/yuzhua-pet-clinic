import { ElMessageBox as RawElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

/**
 * 命令式 ElMessageBox 统一收口封装：
 * 1. 按需模式下集中绑定 message-box 样式，同时将其 JS+CSS 隔离出首屏入口包；
 * 2. 在调用方未显式传入按钮文案时补充中文默认文案，避免脱离 <el-config-provider>
 *    上下文后回退到英文 OK / Cancel。
 */
const DEFAULT_CONFIRM_OPTS = { confirmButtonText: '确定', cancelButtonText: '取消' }
const DEFAULT_ALERT_OPTS = { confirmButtonText: '确定' }

function mergeDefaults(defaults, options) {
  const merged = { ...defaults, ...(options || {}) }
  if (!merged.confirmButtonText && defaults.confirmButtonText) {
    merged.confirmButtonText = defaults.confirmButtonText
  }
  if (!merged.cancelButtonText && defaults.cancelButtonText) {
    merged.cancelButtonText = defaults.cancelButtonText
  }
  return merged
}

function wrapBoxMethod(fn, defaults) {
  return (message, title, options, appContext) => {
    // 兼容 2 参形式：(message, options[, appContext])
    if (title !== null && typeof title === 'object') {
      const merged = mergeDefaults(defaults, title)
      return options === undefined
        ? fn(message, merged)
        : fn(message, '', merged, options)
    }
    const merged = mergeDefaults(defaults, options)
    return fn(message, title ?? '', merged, appContext)
  }
}

const wrappedMethods = {
  confirm: wrapBoxMethod(RawElMessageBox.confirm, DEFAULT_CONFIRM_OPTS),
  prompt: wrapBoxMethod(RawElMessageBox.prompt, DEFAULT_CONFIRM_OPTS),
  alert: wrapBoxMethod(RawElMessageBox.alert, DEFAULT_ALERT_OPTS)
}

export const ElMessageBox = new Proxy(RawElMessageBox, {
  get(target, prop, receiver) {
    if (Object.prototype.hasOwnProperty.call(wrappedMethods, prop)) {
      return wrappedMethods[prop]
    }
    return Reflect.get(target, prop, receiver)
  }
})
