<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Document } from '@element-plus/icons-vue'
import { acceptConsult, closeConsult, consultDetail } from '@/api/admin'
import { SESSION_STATUS, text } from '@/utils/dict'
import { maskPhone } from '@/utils/format'
import ConsultPanel from '@/components/ConsultPanel.vue'
import ImageGallery from '@/components/ImageGallery.vue'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const previewOwner = Symbol('consult-preview')
const session = ref(null)
const loading = ref(true)
const busy = ref(false)
const finishOpen = ref(false)
const summary = ref('')
const detailsOpen = ref(false)
const phone = ref(window.innerWidth <= 700)
const viewportHeight = ref(null)
function resizeLayout() {
  phone.value = window.innerWidth <= 700
  if (window.visualViewport?.scale === 1) viewportHeight.value = `${window.visualViewport.height}px`
}
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeLayout)
  window.visualViewport?.removeEventListener('resize', resizeLayout)
})
// 原因: 接诊/结束动作后递增 key 强制面板重挂，从库中拉取最新系统消息
const panelKey = ref(0)

const closed = computed(() => session.value?.status === 2)

async function load() {
  try {
    session.value = await consultDetail(id)
  } catch {
    session.value = null
  }
}

async function accept() {
  busy.value = true
  try {
    await acceptConsult(id)
    ElMessage.success('已接诊，可以开始沟通了')
    await load()
    panelKey.value += 1
  } finally {
    busy.value = false
  }
}

async function finish() {
  busy.value = true
  try {
    await closeConsult(id, { summary: summary.value.trim() })
    ElMessage.success('本次问诊已结束')
    finishOpen.value = false
    await load()
    panelKey.value += 1
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  resizeLayout()
  window.addEventListener('resize', resizeLayout)
  window.visualViewport?.addEventListener('resize', resizeLayout)
  try {
    await load()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="consult-page" :style="{ '--chat-viewport': viewportHeight }" v-loading="loading">
    <template v-if="session">
      <header class="head">
        <el-button text class="back" aria-label="返回问诊列表" @click="router.push('/admin/consult')">
          <el-icon :size="20"><ArrowLeft /></el-icon>
        </el-button>
        <div class="identity">
          <h1>{{ session.petName || '未命名宠物' }}</h1>
          <span class="pc-muted">主人 · {{ session.userNickname || '未命名用户' }}</span>
          <span class="session-status" role="status">{{ text(SESSION_STATUS, session.status) }}</span>
        </div>
        <el-button text class="details-button" @click="detailsOpen = true"><el-icon><Document /></el-icon>{{ closed ? '资料与小结' : '病情资料' }}</el-button>
        <el-button v-if="session.status === 0" type="primary" :loading="busy" @click="accept">
          接诊
        </el-button>
        <el-button v-if="!closed" :disabled="busy" @click="finishOpen = true">结束问诊</el-button>
      </header>

      <div class="workspace">
        <section class="panel" aria-label="问诊沟通">
          <ConsultPanel :key="panelKey" :session-id="id" kind="admin" :closed="closed" peer-label="宠物主人" :preview-owner="previewOwner"
                         @status-changed="load" />
        </section>
        <el-drawer v-model="detailsOpen" title="病情资料" :direction="phone ? 'btt' : 'rtl'"
                   :size="phone ? 'auto' : '400px'" :style="phone ? { maxHeight: '80dvh', borderRadius: '16px 16px 0 0' } : {}">
          <div id="consult-details" class="details-body">
            <section v-if="session.summary" class="detail-section summary">
              <h3>问诊小结</h3>
              <p>{{ session.summary }}</p>
            </section>
            <section class="detail-section">
              <h3>症状描述</h3>
              <p>{{ session.symptom || '未填写症状描述' }}</p>
            </section>
            <section v-if="session.images" class="detail-section">
              <h3>症状照片</h3>
              <ImageGallery :urls="session.images" :size="72" :gap="8" label="宠物主人上传的症状照片" :preview-owner="previewOwner" />
            </section>
            <section class="detail-section contact">
              <h3>联系方式</h3>
              <p>{{ maskPhone(session.userPhone) || '未填写' }}</p>
            </section>
          </div>
        </el-drawer>
      </div>

      <el-dialog v-model="finishOpen" title="结束问诊" width="min(440px, 92vw)">
        <p class="pc-muted dlg-tip">
          结束后将不能继续回复。
        </p>
        <el-input v-model="summary" type="textarea" :rows="3" maxlength="500" show-word-limit
                  placeholder="记录本次沟通要点、观察事项及到院建议（选填）" />
        <template #footer>
          <el-button @click="finishOpen = false">取消</el-button>
          <el-button type="primary" :loading="busy" @click="finish">确认结束</el-button>
        </template>
      </el-dialog>
    </template>

    <el-empty v-else-if="!loading" description="找不到这个会话，或它不属于你">
      <el-button type="primary" @click="router.push('/admin/consult')">返回列表</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.consult-page { display: flex; flex-direction: column; height: calc(var(--chat-viewport, 100dvh) - 104px); min-height: 0; gap: 14px; background: var(--color-canvas); }
.head { display: flex; align-items: center; gap: 12px; flex-shrink: 0; flex-wrap: wrap; min-height: 44px; }
.head .el-button { margin: 0; }
.back { width: 32px; padding: 0; color: var(--color-muted); }
.identity { flex: 1; min-width: 100px; display: flex; align-items: baseline; flex-wrap: wrap; gap: 2px 12px; overflow-wrap: anywhere; }
.identity h1 { margin: 0; font-size: 21px; line-height: 1.35; font-weight: 650; }
.identity .session-status { display: inline-flex; align-items: center; gap: 12px; color: var(--color-muted); font-weight: 400; }
.session-status::before { content: ""; width: 1px; height: 12px; background: var(--color-hairline); }
.details-button .el-icon { margin-right: 6px; }
.identity > span { font-size: 13px; line-height: 1.5; }
.workspace { flex: 1; min-height: 0; display: flex; }
.panel { min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; flex: 1; background: var(--color-canvas); }
.detail-section + .detail-section { margin-top: 24px; }
.detail-section h3 { margin: 0 0 10px; font-size: 13px; font-weight: 500; color: var(--color-muted); }
.detail-section p { margin: 0; font-size: 15px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.contact p { font-variant-numeric: tabular-nums; }
.summary { padding-bottom: 20px; border-bottom: 1px solid var(--color-hairline-soft); }
.summary h3 { color: var(--color-ink); font-weight: 600; }
.dlg-tip { margin: 0 0 10px; font-size: 13px; line-height: 1.7; }
@media (max-width: 700px) {
  .consult-page { height: calc(var(--chat-viewport, 100dvh) - 76px); gap: 10px; }
  .head { gap: 8px; }
  .identity { flex-basis: calc(100% - 48px); }
  .identity h1 { font-size: 18px; }
  .head > .el-button { padding-inline: 8px; }
}
</style>
