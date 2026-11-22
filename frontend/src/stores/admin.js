import { computed } from 'vue'
import { defineStore } from 'pinia'
import { login as adminLogin, profile as adminProfile } from '@/api/admin'
import { createSession } from '@/stores/session'

const USER_KEY = 'yuzhua_admin_user'

/**
 * 后台登录态。医生和管理员同属一套账号体系，共用这里的 token，
 * 但菜单与页面要分开：isDoctor 决定是否走接诊工作台，isAdmin 才给全量管理项。
 */
export const useAdminStore = defineStore('admin', () => {
  const { token, user, apply, refresh, logout } = createSession({
    kind: 'admin', userKey: USER_KEY, fetchProfile: adminProfile
  })

  const logged = computed(() => !!token.value)
  const role = computed(() => user.value?.role || '')
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const isDoctor = computed(() => user.value?.role === 'DOCTOR')
  const doctorId = computed(() => user.value?.doctorId)
  const name = computed(() => user.value?.name || user.value?.username || '')

  async function login(form) {
    const vo = await adminLogin(form)
    apply(vo)
    return vo
  }

  return { token, user, logged, role, isAdmin, isDoctor, doctorId, name, login, apply, refresh, logout }
})
