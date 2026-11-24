<script setup>
import brandLogo from '@/assets/logo.svg'
import { BRAND } from '@/brand.js'
import { computed, markRaw, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Avatar, Bell, Calendar, ChatDotRound, Clock, Collection, DataLine, Document,
  Expand, Fold, Notebook, OfficeBuilding, Star, Tickets, User
} from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { consultUnread } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import StatusChip from '@/components/StatusChip.vue'

const store = useAdminStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(window.innerWidth < 900)
const unread = ref(0)
let timer = null

const all = [
  { to: '/admin/dashboard', label: '工作台', icon: markRaw(DataLine) },
  { to: '/admin/appointments', label: '预约管理', icon: markRaw(Tickets) },
  { to: '/admin/schedule', label: '排班管理', icon: markRaw(Calendar), roles: ['ADMIN'] },
  { to: '/admin/my-schedule', label: '我的排班', icon: markRaw(Clock), roles: ['DOCTOR'] },
  { to: '/admin/doctors', label: '医生管理', icon: markRaw(Avatar), roles: ['ADMIN'] },
  { to: '/admin/users', label: '宠物主人', icon: markRaw(User), roles: ['ADMIN'] },
  { to: '/admin/pets', label: '宠物档案', icon: markRaw(Collection), roles: ['ADMIN'] },
  { to: '/admin/departments', label: '科室管理', icon: markRaw(OfficeBuilding), roles: ['ADMIN'] },
  { to: '/admin/records', label: '病历查询', icon: markRaw(Notebook) },
  { to: '/admin/reviews', label: '评价管理', icon: markRaw(Star) },
  { to: '/admin/consult-records', label: '问诊记录', icon: markRaw(Document), roles: ['ADMIN'] },
  { to: '/admin/consult', label: '问诊会话', icon: markRaw(ChatDotRound), roles: ['DOCTOR'], badge: true },
  { to: '/admin/notices', label: '公告科普', icon: markRaw(Bell), roles: ['ADMIN'] }
]

const menus = computed(() => all.filter((m) => !m.roles || m.roles.includes(store.role)))
const groups = computed(() => [
  { label: '概览', paths: ['dashboard'] },
  { label: '日常业务', paths: ['appointments', 'schedule', 'my-schedule', 'records', 'reviews', 'consult-records', 'consult'] },
  { label: '档案管理', paths: ['doctors', 'users', 'pets', 'departments'] },
  { label: '内容管理', paths: ['notices'] }
].map(g => ({ ...g, items: g.paths.map(p => menus.value.find(m => m.to === `/admin/${p}`)).filter(Boolean) })).filter(g => g.items.length))
const detailCrumb = [
  { re: /^\/admin\/workbench\//, label: '接诊工作台', parent: '/admin/appointments' },
  { re: /^\/admin\/appointment\//, label: '预约详情', parent: '/admin/appointments' },
  { re: /^\/admin\/record\//, label: '病历详情', parent: '/admin/records' },
  { re: /^\/admin\/consult\//, label: '问诊会话', parent: '/admin/consult' },
  { re: /^\/admin\/profile$/, label: '账号设置', parent: '' }
]
const detail = computed(() => detailCrumb.find(d => d.re.test(route.path)))
const active = computed(() => detail.value ? detail.value.parent : menus.value.find(m => route.path === m.to)?.to || '')
const parentLabel = computed(() => menus.value.find(m => m.to === active.value)?.label)
const crumb = computed(() => detail.value?.label || parentLabel.value || '工作台')

async function poll() {
  if (!store.isDoctor) {
    unread.value = 0
    return
  }
  try {
    unread.value = (await consultUnread()) || 0
  } catch {
    // 原因: 轮询角标非关键路径，失败静默忽略以免频繁弹窗
  }
}

watch(() => store.role, () => {
  poll()
  clearInterval(timer)
  if (store.isDoctor) timer = setInterval(poll, 30000)
}, { immediate: true })

onBeforeUnmount(() => clearInterval(timer))

// 原因: localStorage 缓存可能滞后于资料修改，挂载后以服务端最新数据为准
onMounted(() => { store.refresh().catch(() => {}) })

async function logout() {
  await ElMessageBox.confirm('退出后需要重新登录，确定吗？', '退出登录', {
    type: 'warning', confirmButtonText: '退出登录', cancelButtonText: '取消'
  })
  store.logout()
  router.replace('/admin/login')
}

function onResize() {
  collapsed.value = window.innerWidth < 900
}

window.addEventListener('resize', onResize)
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
</script>

<template>
  <div class="shell admin-shell" :class="{ 'consult-shell': route.name === 'admin-consult-room' }">
    <aside class="side" :class="{ tight: collapsed }">
      <div class="brand">
        <img class="brand-logo" :src="brandLogo" width="32" height="32" :alt="collapsed ? BRAND.name : ''" />
        <span v-if="!collapsed">{{ BRAND.name }}<small>院务工作台</small></span>
      </div>
      <el-menu :default-active="active" :collapse="collapsed" :collapse-transition="false" router>
        <template v-for="group in groups" :key="group.label">
        <div v-if="!collapsed" class="nav-group">{{ group.label }}</div>
        <el-menu-item v-for="m in group.items" :key="m.to" :index="m.to">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>
            <span>{{ m.label }}</span>
            <em v-if="m.badge && unread" class="dot">{{ unread > 99 ? '99+' : unread }}</em>
          </template>
        </el-menu-item>
        </template>
      </el-menu>
    </aside>

    <div class="main">
      <header class="top">
        <el-button text class="fold" :aria-label="collapsed ? '展开导航' : '收起导航'" :aria-expanded="!collapsed" @click="collapsed = !collapsed">
          <el-icon :size="18"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </el-button>
        <div class="crumb">
          <router-link v-if="detail?.parent" :to="detail.parent">{{ parentLabel }}</router-link>
          <span v-if="detail?.parent" aria-hidden="true"> / </span>
          <b>{{ crumb }}</b>
        </div>
        <el-dropdown trigger="click">
          <span class="me">
            <el-avatar :size="28" :src="store.user?.avatar">{{ store.name.slice(0, 1) }}</el-avatar>
            <span>
              <b>{{ store.name }}</b>
              <i>
                <StatusChip tone="plain">{{ store.isAdmin ? '管理员' : '医生' }}</StatusChip>
              </i>
            </span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="router.push('/admin/profile')">账号与密码</el-dropdown-item>
              <el-dropdown-item v-if="store.isDoctor" @click="router.push('/admin/consult')">问诊会话</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <section class="body">
        <div v-if="!detail" class="admin-page-heading"><h1>{{ crumb }}</h1></div>
        <router-view />
      </section>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

.side {
  width: 216px;
  flex: none;
  background: #ffffff;
  border-right: 1px solid var(--color-hairline-soft);
  transition: width .18s ease;
}

.side.tight {
  width: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 15px 16px;
  color: var(--color-ink);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: .3px;
  white-space: nowrap;
  overflow: hidden;
}

/* 导航以中性色为主，品牌色标记当前位置。 */
.side :deep(.el-menu) {
  --el-menu-bg-color: #ffffff;
  --el-menu-text-color: #555555;
  --el-menu-hover-bg-color: #f7f7f7;
  --el-menu-active-color: var(--color-primary-active);
  border-right: 0;
}

.side :deep(.el-menu-item.is-active) {
  background: var(--color-primary-soft);
  border-radius: 8px;
  font-weight: 600;
}

/* 菜单行是 flex 且继承 56px 行高，不定死高度的话角标会被撑成竖长胶囊 */
.dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  margin-left: 8px;
  border-radius: var(--radius-full);
  background: var(--color-warning);
  color: #ffffff;
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.top {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 14px 0 4px;
  background: #fff;
  border-bottom: 1px solid var(--color-hairline);
}

.crumb {
  flex: 1;
  min-width: 0;
}

.crumb b {
  display: block;
  font-size: 15px;
}

.crumb i {
  font-style: normal;
  font-size: 12px;
  color: var(--color-muted);
}

.me {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.me span {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.25;
}

.me b {
  font-size: 13px;
}

.consult-shell .body { background: var(--color-canvas); }

.body {
  flex: 1;
  padding: 24px;
  min-width: 0;
}

@media (width <= 700px) {
  .body {
    padding: 10px;
  }
}
.side { position: sticky; top: 0; height: 100dvh; overflow-y: auto; }
.brand { min-height: 76px; }
.brand small { display: block; margin-top: 4px; font-size: 12px; font-weight: 400; color: var(--color-muted); }
.brand-logo { display: block; flex: none; width: 32px; height: 32px; }
.nav-group { padding: 20px 20px 8px; color: var(--color-muted); font-size: 12px; }
.side :deep(.el-menu-item) { height: 42px; line-height: 42px; margin: 3px 10px; border-radius: 8px; }
.side.tight :deep(.el-menu-item) { margin: 3px 0; }
.crumb { display: flex; align-items: center; gap: 10px; }
.crumb a { color: var(--color-muted); font-size: 13px; }
@media (max-width: 700px) {
  .consult-shell .side, .consult-shell .fold { display: none; }
  .consult-shell .top { padding-left: 14px; }
}
@media (prefers-reduced-motion: reduce) { .side { transition: none; } }
</style>
