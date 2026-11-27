import { ElMessageBox as RawElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'

// 原因: 按需模式下统一兜底中文按钮文案，防止脱离 ConfigProvider 后回退英文按钮
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
    // 原因: 兼容 Element Plus 两参调用形式，即第二个参数直接传入配置对象
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
