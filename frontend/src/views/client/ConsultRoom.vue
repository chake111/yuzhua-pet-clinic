<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { closeConsult, consultDetail } from '@/api/user'
import { SESSION_STATUS, tagType, text } from '@/utils/dict'
import ConsultPanel from '@/components/ConsultPanel.vue'
import ImageGallery from '@/components/ImageGallery.vue'
import StatusChip from '@/components/StatusChip.vue'

/**
 * 用户端聊天室外壳：会话抬头 + 共用的聊天面板。
 * 会话详情由后端校验参与方后返回。
 */
const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const previewOwner = Symbol('consult-preview')
const session = ref(null)
const failed = ref(false)
const loading = ref(true)
const detailsOpen = ref(false)
const presence = ref('')
const viewportHeight = ref(null)
/** 接诊/结束这类动作后让面板重挂一次，把系统消息重新从库里拉回来 */
const panelKey = ref(0)

const closed = computed(() => session.value?.status === 2)

async function load() {
  loading.value = true
  failed.value = false
  try {
    session.value = await consultDetail(id)
  } catch {
    session.value = null
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function closeSession() {
  try {
    await ElMessageBox.confirm('医生还没接诊，关闭后这次问诊就结束了。确定关闭？', '关闭问诊', {
      type: 'warning', confirmButtonText: '关闭', cancelButtonText: '再等等'
    })
  } catch {
    return
  }
  await closeConsult(id)
  ElMessage.success('已关闭')
  await load()
  panelKey.value += 1
}

function resizeViewport() {
  // 软键盘会缩小 visualViewport，部分手机浏览器的 dvh 不随之变化。
  if (window.visualViewport?.scale === 1) viewportHeight.value = window.visualViewport.height
}

onMounted(() => {
  load()
  resizeViewport()
  window.visualViewport?.addEventListener('resize', resizeViewport)
})
onBeforeUnmount(() => window.visualViewport?.removeEventListener('resize', resizeViewport))

</script>

<template>
  <div class="pc-narrow page" :style="viewportHeight ? { height: `${viewportHeight}px` } : undefined" v-loading="loading">
    <header class="head">
      <el-button text class="back" aria-label="返回问诊列表" @click="router.push('/consult')">
        <el-icon :size="22"><ArrowLeft /></el-icon>
      </el-button>
      <el-avatar v-if="session" :size="36" :src="session.doctorAvatar" class="ava" alt="">{{ session.doctorName?.[0] }}</el-avatar>
      <div class="identity">
        <h1>{{ session?.doctorName || '图文问诊' }}</h1>
        <div v-if="session" class="meta">
          <span>{{ session.petName }}的问诊</span>
          <span class="presence" role="status">{{ closed ? '已结束' : session.status === 0 ? '等待医生接诊' : presence }}</span>
        </div>
      </div>
      <el-button v-if="session" class="details-button" @click="detailsOpen = true">
        {{ closed ? '资料与小结' : '问诊资料' }}
      </el-button>
    </header>
    <LoadFailure v-if="failed" @retry="load" />
    <template v-else-if="session">
      <div class="panel">
        <ConsultPanel :key="panelKey" :session-id="id" kind="client" :closed="closed" :preview-owner="previewOwner"
                     @status-changed="load" @presence-changed="presence = $event"
                      :doctor-id="session.doctorId" peer-label="医生" />
      </div>
      <el-drawer v-model="detailsOpen" title="问诊资料" direction="btt" size="auto"
                 :style="{ maxWidth: '760px', margin: '0 auto', maxHeight: '80dvh', borderRadius: '16px 16px 0 0' }">
        <div class="details">
          <StatusChip :tone="tagType(SESSION_STATUS, session.status)">{{ text(SESSION_STATUS, session.status) }}</StatusChip>
          <section><h2>就诊宠物</h2><p>{{ session.petName }}</p></section>
          <section><h2>病情描述</h2><p>{{ session.symptom || '未填写病情描述' }}</p></section>
          <section v-if="session.images">
            <h2>症状照片</h2>
            <ImageGallery :urls="session.images" :size="80" :gap="8" :preview-owner="previewOwner" label="症状照片" />
          </section>
          <section><h2>接诊医生</h2><p>{{ session.doctorName }}</p></section>
          <section><h2>问诊小结</h2><p>{{ session.summary || (closed ? '本次问诊未填写小结' : '医生结束问诊后可在这里查看。') }}</p></section>
          <el-button v-if="session.status === 0" class="close-session" @click="closeSession">关闭会话</el-button>
        </div>
      </el-drawer>
    </template>

    <el-empty v-else-if="!loading" description="找不到这个问诊会话">
      <el-button type="primary" @click="router.push('/consult')">返回列表</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.page {
  display: flex; flex-direction: column;
  height: 100dvh;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
.head { display: flex; align-items: center; gap: 12px; padding: 12px 16px; flex-shrink: 0; background: var(--color-canvas); border-bottom: 1px solid var(--color-hairline-soft); }
.head .el-button { margin: 0; }
.back { flex-shrink: 0; width: 44px; padding: 0; }
.ava { background: var(--color-ink); color: var(--color-on-primary); }
.identity { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.identity h1 { margin: 0 0 4px; font-size: var(--font-reading); font-weight: 600; line-height: 1.5; }
.meta { display: flex; flex-wrap: wrap; gap: 2px 12px; color: var(--color-muted); font-size: 13px; line-height: 1.6; }
.details-button { flex-shrink: 0; padding-inline: 12px; }
.panel { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.details { padding-bottom: env(safe-area-inset-bottom); }
.details section { margin-top: 20px; }
.details h2 { margin: 0 0 8px; font-size: 14px; font-weight: 600; }
.details p { margin: 0; font-size: var(--font-reading); line-height: var(--line-reading); white-space: pre-wrap; overflow-wrap: anywhere; }
.close-session { margin-top: 24px; }
@media (max-width: 480px) {
  .head { gap: 8px; padding: 10px 12px; }
  .head > .ava { display: none; }
}
</style>
