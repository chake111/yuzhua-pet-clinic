import { createPinia } from 'pinia'
import { createApp } from 'vue'
import './styles/entry-el.css'
import './styles/main.css'
import './styles/admin.css'
import App from './App.vue'
import router from './router'

const PRELOAD_RELOAD_KEY = 'yuzhua_preload_reload_at'
const PRELOAD_RELOAD_WINDOW_MS = 30_000

// 原因: 滚动发版后旧页触发懒加载 chunk 404 时执行受 30 秒防循环保护的单次刷新自愈
window.addEventListener('vite:preloadError', (event) => {
  try {
    const now = Date.now()
    const last = Number(window.sessionStorage.getItem(PRELOAD_RELOAD_KEY) || 0)
    if (!last || now - last > PRELOAD_RELOAD_WINDOW_MS) {
      window.sessionStorage.setItem(PRELOAD_RELOAD_KEY, String(now))
      event.preventDefault()
      window.location.reload()
    }
  } catch {
    // 原因: 隐私模式或受限存储环境无法持久化防循环标记，放行错误避免无限刷新
  }
})

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
