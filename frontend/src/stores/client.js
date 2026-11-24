import { computed } from 'vue'
import { defineStore } from 'pinia'
import * as userApi from '@/api/user'
import { createSession } from '@/stores/session'

const USER_KEY = 'yuzhua_client_user'

export const useUserStore = defineStore('client', () => {
  const { token, user, apply, refresh, logout } = createSession({
    kind: 'client', userKey: USER_KEY, fetchProfile: userApi.profile
  })

  // 原因: LoginVo 的 name 仅是登录瞬间快照，改资料后更新的是 nickname，必须优先读取
  const logged = computed(() => !!token.value)
  const userId = computed(() => user.value?.id)
  const nickname = computed(
    () => user.value?.nickname || user.value?.name || user.value?.username || ''
  )

  async function login(form) {
    const vo = await userApi.login(form)
    apply(vo)
    return vo
  }

  async function register(form) {
    const vo = await userApi.register(form)
    apply(vo)
    return vo
  }

  return { token, user, logged, userId, nickname, login, register, apply, refresh, logout }
})
