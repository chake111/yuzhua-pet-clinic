/**
 * 演示入口查询参数（?demo=user|doctor|admin）白名单解析器。
 *
 * 仅用于登录页表单预填，不触发自动登录，也不在源码中硬编码演示口令；
 * 演示口令由构建期环境变量 VITE_DEMO_PASSWORD 注入，未配置时安全降级为空串。
 */
const DEMO_PRESETS = {
  client: {
    user: 'zhangsan'
  },
  admin: {
    doctor: 'doctor01',
    admin: 'admin'
  }
}

/**
 * 解析某登录侧对应的演示预填账号与口令。
 *
 * @param {'client' | 'admin'} side 登录侧
 * @param {unknown} demoQuery 路由 query 中的 demo 参数
 * @param {string | undefined} [demoPassword] 演示口令，默认取构建期环境变量
 * @returns {{ username: string, password: string } | null}
 */
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
