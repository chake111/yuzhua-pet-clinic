<script setup>
import { markRaw, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Notebook, Setting, Sunny, Tickets } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/client'
import { maskPhone } from '@/utils/format'

const store = useUserStore()
const route = useRoute()

const nav = [
  { to: '/me/orders', label: '我的预约', icon: markRaw(Tickets) },
  { to: '/me/pets', label: '宠物档案', icon: markRaw(Sunny) },
  { to: '/me/records', label: '病历处方', icon: markRaw(Notebook) },
  { to: '/me/profile', label: '账号设置', icon: markRaw(Setting) }
]

onMounted(() => {
  // 原因: 首屏先渲染本地缓存资料避免阻塞，再静默刷新最新用户信息
  store.refresh().catch(() => {})
})
const on = (to) => route.path === to
</script>

<template>
  <!-- 原因: 四个个人中心子页共用此外壳容器，统一控制页面边距 -->
  <div class="pc-narrow">
    <section class="pc-card prof">
      <el-avatar :size="52" :src="store.user?.avatar" alt="">{{ store.nickname?.[0] }}</el-avatar>
      <div class="txt">
        <b>{{ store.nickname || '未命名用户' }}</b>
        <span class="pc-muted">{{ maskPhone(store.user?.phone) }} 账号 {{ store.user?.username }}</span>
      </div>
    </section>

    <nav class="tabs" aria-label="个人中心导航">
      <router-link v-for="n in nav" :key="n.to" :to="n.to" class="tab" :class="{ on: on(n.to) }" :aria-label="n.label" :aria-current="on(n.to) ? 'page' : undefined">
        <el-icon aria-hidden="true"><component :is="n.icon" /></el-icon>
        {{ n.label }}
      </router-link>
    </nav>

    <router-view />
  </div>
</template>

<style scoped>
.prof { display: flex; align-items: center; gap: 12px; }
.txt { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.txt b { font-size: 16px; font-weight: 600; color: var(--color-ink); }
.txt .pc-muted { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tabs { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 16px 0; }
.tab {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  background: var(--color-canvas); border: 1px solid var(--color-hairline);
  border-radius: var(--radius-sm);
  padding: 10px 2px; font-size: 13px; font-weight: 600; color: var(--color-muted);
}
@media (hover: hover) and (pointer: fine) {
  .tab:hover { border-color: var(--color-border-strong); }
}
/* 选中是"当前在哪"，不是"可以点"：用 ink 实心，不用品牌红 */
.tab.on {
  border-color: var(--color-ink);
  background: var(--color-ink);
  color: var(--color-on-primary);
}
.prof { padding: 12px 16px; }
.txt b { overflow-wrap: anywhere; }
.tab { min-height: 52px; justify-content: center; }
</style>
