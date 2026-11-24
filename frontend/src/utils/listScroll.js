import { nextTick } from 'vue'

// 原因: 等待异步内容渲染后再恢复滚动位置，避免空列表将路由保存的滚动值夹零
export async function loadWithSavedScroll(load) {
  const saved = window.history.state?.scroll
  const path = window.location.pathname
  await load()
  await nextTick()
  if (saved && window.location.pathname === path) window.scrollTo(saved)
}
