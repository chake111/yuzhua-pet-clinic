const DEMO_PRESETS = {
  client: {
    user: 'zhangsan'
  },
  admin: {
    doctor: 'doctor01',
    admin: 'admin'
  }
}

// 原因: 仅用于登录页表单预填而不触发自动登录；口令由 VITE_DEMO_PASSWORD 注入，缺失时降级为空串
export function resolveDemoPreset(side, demoQuery, demoPassword = import.meta.env?.VITE_DEMO_PASSWORD) {
  if (typeof side !== 'string' || !Object.prototype.hasOwnProperty.call(DEMO_PRESETS, side)) {
    return null
  }
  if (typeof demoQuery !== 'string') {
    return null
  }
  const normalized = demoQuery.trim().toLowerCase()
  if (!normalized) {
    return null
  }
  const sidePresets = DEMO_PRESETS[side]
  if (!Object.prototype.hasOwnProperty.call(sidePresets, normalized)) {
    return null
  }
  const password = typeof demoPassword === 'string' && demoPassword.length > 0 ? demoPassword : ''
  return {
    username: sidePresets[normalized],
    password
  }
}
