<script setup>
import brandLogo from '@/assets/logo.svg'
import { BRAND } from '@/brand.js'
import { computed, markRaw, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, HomeFilled, Search, User } from '@element-plus/icons-vue'
import { consultUnread } from '@/api/user'
import { useUserStore } from '@/stores/client'

/**
 * 用户端外壳。整体是一个居中的窄栏（.pc-narrow），底部四个 tab——
 * 预约场景绝大多数在手机上来完成，宽屏上也保持 760px 读得更舒服。
 */
const store = useUserStore()
const route = useRoute()
const router = useRouter()
const isConsultRoom = computed(() => route.name === 'consultRoom')
const unread = ref(0)
let timer = null

const tabs = [
  { to: '/', label: '首页', icon: markRaw(HomeFilled) },
  { to: '/doctors', label: '找医生', icon: markRaw(Search) },
  { to: '/consult', label: '问诊', icon: markRaw(ChatDotRound), badge: true },
  { to: '/me', label: '我的', icon: markRaw(User) }
]

// 从已完成的路由确定位置，兼容返回、深链接和登录重定向。
const activeTab = computed(() => {
  const path = route.path
  if (path === '/' || /^\/notices?(\/|$)/.test(path)) return 0
  if (/^\/(doctors?|book)(\/|$)/.test(path)) return 1
  if (/^\/consult(\/|$)/.test(path)) return 2
  if (/^\/(me|order|record|review)(\/|$)/.test(path)) return 3
  return -1
})

async function pollUnread() {
  if (!store.logged) {
    unread.value = 0
    return
  }
  try {
    unread.value = (await consultUnread()) || 0
  } catch {
    // 会话过期由 axios 拦截器统一处理，这里不打扰用户
  }
}

function loop(on) {
  clearInterval(timer)
  timer = on ? setInterval(pollUnread, 30000) : null
}

watch(() => store.logged, (on) => {
  pollUnread()
  loop(on)
}, { immediate: true })

// 聊天页读过的消息要立刻反映到角标上，返回外壳时主动刷一次
watch(() => route.path, (p) => {
  if (p.startsWith('/consult') || p.startsWith('/me')) pollUnread()
})

onBeforeUnmount(() => clearInterval(timer))

function logout() {
  store.logout()
  router.push('/')
}
</script>

<template>
  <div class="shell client-shell" :class="{ 'chat-shell': isConsultRoom }">
    <header v-if="!isConsultRoom" class="top">
      <router-link to="/" class="brand" :aria-label="BRAND.fullName + '，返回首页'">
        <img class="logo" :src="brandLogo" width="32" height="32" alt="" />
        <span class="txt">
          <b>{{ BRAND.name }}</b>
          <i>{{ BRAND.tagline }}</i>
        </span>
      </router-link>
      <div class="who">
        <template v-if="store.logged">
          <el-dropdown trigger="click">
            <span class="me">
              <el-avatar :size="26" :src="store.user?.avatar" alt="">{{ store.nickname.slice(0, 1) }}</el-avatar>
              <em>{{ store.nickname }}</em>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/me/orders')">我的预约</el-dropdown-item>
                <el-dropdown-item @click="router.push('/me/pets')">宠物档案</el-dropdown-item>
                <el-dropdown-item @click="router.push('/me/profile')">账号设置</el-dropdown-item>
                <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <el-button v-else type="primary" size="small" @click="router.push('/login')">登录</el-button>
      </div>
    </header>

    <main class="body">
      <router-view />
    </main>

    <nav v-if="!isConsultRoom" class="tabs" aria-label="主导航" :style="{ '--active-tab': activeTab }">
      <span v-if="activeTab >= 0" class="tab-indicator" aria-hidden="true" />
      <router-link
        v-for="(t, index) in tabs"
        :key="t.to"
        :to="t.to"
        custom
        v-slot="{ navigate }"
      >
        <button class="tab" :class="{ on: activeTab === index }" :aria-current="activeTab === index ? 'page' : undefined" @click="navigate">
          <span class="ico">
            <el-badge :value="t.badge ? unread : 0" :hidden="!t.badge || !unread" :max="99">
              <el-icon :size="24"><component :is="t.icon" /></el-icon>
            </el-badge>
          </span>
          <span>{{ t.label }}</span>
        </button>
      </router-link>
    </nav>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

/* 顶栏与正文共用一条内容栏：760 列再让出 --page-margin（16px），得到 728px 的内容盒。
   之前这里写死 (100% - 720px)，正文却按 .pc-narrow 的 16px 留白排到 728，左边缘差 4px；
   首页又用 20px 留白（720），三处各差一点。统一成一条公式后，顶栏、正文、底栏左边缘重合。 */
.top {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 60px;
  padding: 10px max(var(--page-margin), calc((100% - var(--content-width)) / 2 + var(--page-margin)));
  background: var(--color-canvas);
  border-bottom: 1px solid var(--color-hairline);
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 品牌图形使用独立 SVG，文字保留原有排版。 */
.logo {
  display: block;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
}

.txt {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.brand b {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
}

.brand i {
  font-style: normal;
  font-size: 13px;
  line-height: 1.3;
  color: var(--color-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.me {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  border-radius: var(--radius-sm);
}

.me em {
  font-style: normal;
  font-size: 13px;
  max-width: 96px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--color-body);
}

/* 为底栏和设备安全区预留空间。 */
.body {
  flex: 1;
  padding-bottom: calc(var(--tab-bar-height) + env(safe-area-inset-bottom) + 8px);
}
.chat-shell .body { padding-bottom: 0; }

/* 指示块在四个等宽入口之间移动，业务路由无需等待动画。
   底栏是浮动面板，宽度取内容盒（760 - 2 × 16 = 728），白底边缘与正文卡片对齐；
   之前用满 --content-width，白底比卡片两边各宽 16px。 */
.tabs {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  display: flex;
  max-width: calc(var(--content-width) - var(--page-margin) * 2);
  margin: 0 auto;
  background: var(--color-canvas);
  border-top: 1px solid var(--color-hairline);
  padding-bottom: env(safe-area-inset-bottom);
}

.tab {
  position: relative;
  z-index: 1;
  flex: 1;
  min-width: 0;
  min-height: var(--tab-bar-height);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 4px 0;
  border: 0;
  background: none;
  color: var(--color-muted);
  font-size: var(--font-label);
  line-height: 20px;
  font-weight: 600;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: color .2s var(--ease);
}

.tab-indicator {
  position: absolute;
  inset: 8px auto auto 0;
  width: 25%;
  height: 28px;
  pointer-events: none;
  transform: translateX(calc(var(--active-tab) * 100%));
  transition: transform .28s var(--ease);
}
.tab-indicator::after {
  content: '';
  display: block;
  width: 52px;
  height: 100%;
  margin: auto;
  border-radius: var(--radius-full);
  background: var(--color-primary-soft);
}

.ico {
  display: grid;
  place-items: center;
  width: min(56px, 100%);
  height: 24px;
  border-radius: var(--radius-full);
  transition: transform .2s var(--ease);
}

.tab.on {
  color: var(--color-action);
}

.tab.on .ico {
  transform: translateY(-2px);
}
.tab:active .ico { transform: scale(.92); }
.tab:focus-visible { outline: 2px solid var(--color-ink); outline-offset: -4px; border-radius: var(--radius-sm); }
@media (max-width: 359px) {
  /* 留白已由 --page-margin 的下限接管，这里只剩挤不下时的间距收紧 */
  .top { gap: 8px; }
  .me em { display: none; }
  .brand b { font-size: 14px; }
}
/* 200% 放大阅读下产品名与副标题会折行，顶栏只剩 logo（链接仍有可访问名称） */
@media (max-width: 279px) {
  .brand .txt { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .tab, .ico, .tab-indicator { transition: none; }
}
</style>
