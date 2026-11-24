<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { consultMessages, consultPage, hideConsultMessage, moderateConsult } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { CONSULT_MSG_STATUS, MODERATE_STATUS, SESSION_STATUS, options, tagType, text } from '@/utils/dict'
import { toDateTime } from '@/utils/format'
import { openImageViewer } from '@/utils/imageViewer'
import StatusChip from '@/components/StatusChip.vue'

const store = useAdminStore()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNum: 1, pageSize: 20, status: null, moderateStatus: null })
// 原因: 翻页与操作刷新沿用已提交筛选快照，避免未点击查询的表单草稿生效
let applied = { ...q }

const open = ref(false)
const sess = ref(null)
const msgs = ref([])
const msgTotal = ref(0)
const msgLoading = ref(false)
const msgHasMore = ref(false)

async function load() {
  loading.value = true
  try {
    const page = await consultPage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      status: applied.status ?? undefined,
      moderateStatus: applied.moderateStatus ?? undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  applied = { ...q }
  q.pageNum = 1
  load()
}

function openViewer(m) {
  openImageViewer(m.content, 0, { title: `会话 #${m.sessionId ?? sess.value?.id ?? ''} 图片` })
}

async function openRoom(row) {
  sess.value = row
  open.value = true
  msgs.value = []
  msgHasMore.value = false
  msgLoading.value = true
  try {
    const page = await consultMessages(row.id, { pageNum: 1, pageSize: 100 })
    if (sess.value?.id !== row.id) return
    msgTotal.value = page.total
    msgHasMore.value = page.records.length < page.total
    // 原因: 后端按 id 倒序返回消息，需反转为时间正序展示
    msgs.value = page.records.slice().reverse()
  } finally {
    msgLoading.value = false
  }
}

async function loadEarlier() {
  if (msgLoading.value || !msgHasMore.value || !msgs.value.length) return
  const id = sess.value.id
  msgLoading.value = true
  try {
    const page = await consultMessages(id, { pageNum: 1, pageSize: 100, beforeId: msgs.value[0].id })
    if (sess.value?.id !== id) return
    msgs.value = [...page.records].reverse().concat(msgs.value)
    msgHasMore.value = page.records.length < page.total
  } finally {
    msgLoading.value = false
  }
}

const roleName = (m) =>
  m.senderRole === 'DOCTOR' ? '医生' : m.senderRole === 'SYSTEM' ? '系统' : '患者'

async function toggleHidden(m, hidden) {
  const label = hidden === 1 ? '屏蔽' : '恢复'
  if (hidden === 1) {
    const { value } = await ElMessageBox.prompt(
      '屏蔽后患者与医生端都将看不到该内容，历史记录仍保留在本页。', '屏蔽消息', {
        inputPlaceholder: '屏蔽原因（必填，不超过 255 字）',
        inputValidator: (v) => (v && v.trim() ? true : '请填写屏蔽原因'),
        confirmButtonText: '屏蔽', cancelButtonText: '取消'
      }).catch(() => ({}))
    if (value === undefined) return
    await hideConsultMessage(m.id, 1, value.trim())
  } else {
    const ok = await ElMessageBox.confirm('恢复后该消息重新对患者与医生可见。', '恢复消息', {
      type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消'
    }).then(() => true).catch(() => false)
    if (!ok) return
    await hideConsultMessage(m.id, 0)
  }
  ElMessage.success(`已${label}`)
  await openRoom(sess.value)
  load()
}

async function moderate(row, next) {
  if (next === 0) {
    const ok = await ElMessageBox.confirm('撤销后该会话重新对患者与医生可见（已关闭的会话保持关闭）。', '撤销处置', {
      type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消'
    }).then(() => true).catch(() => false)
    if (!ok) return
    await moderateConsult(row.id, 0)
    ElMessage.success('已撤销处置')
  } else {
    const label = text(MODERATE_STATUS, next)
    const { value } = await ElMessageBox.prompt(
      next === 2 ? '屏蔽后该会话对患者与医生不可见，历史记录仍保留在本页。'
        : '关闭后该会话对患者与医生不可见，历史记录仍保留在本页。', label, {
        inputPlaceholder: '处置原因（必填，不超过 255 字）',
        inputValidator: (v) => (v && v.trim() ? true : '请填写处置原因'),
        confirmButtonText: label, cancelButtonText: '取消'
      }).catch(() => ({}))
    if (value === undefined) return
    await moderateConsult(row.id, next, value.trim())
    ElMessage.success(`已${label}`)
  }
  load()
  if (open.value && sess.value && sess.value.id === row.id) openRoom(sess.value)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w140">
        <el-option v-for="o in options(SESSION_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="q.moderateStatus" placeholder="全部处置" clearable class="w140">
        <el-option v-for="o in options(MODERATE_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-button :loading="loading" @click="search">查询</el-button>
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>问诊记录</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="没有符合条件的会话">
        <el-table-column label="宠物主人" min-width="140">
          <template #default="{ row }">
            <div>{{ row.userNickname || '—' }}</div>
            <div class="pc-muted">{{ row.userPhone || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="宠物" min-width="90" prop="petName" />
        <el-table-column label="接诊医生" min-width="90" prop="doctorName" />
        <el-table-column label="症状" min-width="180">
          <template #default="{ row }">
            <span class="pc-muted">{{ row.symptom || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusChip :tone="tagType(SESSION_STATUS, row.status)">{{ text(SESSION_STATUS, row.status) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="处置" width="104">
          <template #default="{ row }">
            <el-tooltip v-if="row.moderateStatus" :content="`${row.moderateReason || '—'}（admin_user #${row.moderateBy ?? '—'}）`" placement="top">
              <StatusChip :tone="tagType(MODERATE_STATUS, row.moderateStatus)">{{ text(MODERATE_STATUS, row.moderateStatus) }}</StatusChip>
            </el-tooltip>
            <span v-else class="pc-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近消息" width="150">
          <template #default="{ row }">{{ toDateTime(row.lastMessageTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRoom(row)">查看记录</el-button>
            <template v-if="store.isAdmin">
              <el-dropdown v-if="!row.moderateStatus" trigger="click" @command="(v) => moderate(row, v)">
                <el-button link type="warning" size="small">处置<el-icon><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :command="1">{{ text(MODERATE_STATUS, 1) }}</el-dropdown-item>
                    <el-dropdown-item :command="2">{{ text(MODERATE_STATUS, 2) }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button v-else link type="success" size="small" @click="moderate(row, 0)">撤销处置</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-drawer v-model="open" :title="`问诊记录   会话 #${sess?.id ?? ''}`" size="640px">
      <template v-if="sess">
        <el-descriptions :column="2" border size="small" class="blk">
          <el-descriptions-item label="患者">{{ sess.userNickname || '—' }}（{{ sess.userPhone || '—' }}）</el-descriptions-item>
          <el-descriptions-item label="接诊医生">{{ sess.doctorName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="宠物">{{ sess.petName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusChip :tone="tagType(SESSION_STATUS, sess.status)">{{ text(SESSION_STATUS, sess.status) }}</StatusChip>
          </el-descriptions-item>
          <el-descriptions-item label="症状描述" :span="2">{{ sess.symptom || '—' }}</el-descriptions-item>
          <el-descriptions-item label="问诊小结" :span="2">{{ sess.summary || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="msgs" v-loading="msgLoading">
          <el-empty v-if="!msgLoading && !msgs.length" description="还没有消息" :image-size="72" />
          <div v-for="m in msgs" :key="m.id" class="msg" :class="[m.senderRole.toLowerCase(), { hidden: m.hidden === 1 }]">
            <div class="meta">
              <b>{{ m.senderRole === 'SYSTEM' ? '系统' : (m.senderName || roleName(m)) }}</b>
              <span class="pc-muted">{{ roleName(m) }}   {{ toDateTime(m.createTime) }}</span>
              <span v-if="m.hidden === 1" class="hidden-tag">{{ text(CONSULT_MSG_STATUS, 1) }}：{{ m.hiddenReason || '—' }}</span>
              <el-button v-if="store.isAdmin" link size="small"
                         :type="m.hidden === 1 ? 'success' : 'warning'"
                         @click="toggleHidden(m, m.hidden === 1 ? 0 : 1)">
                {{ m.hidden === 1 ? '恢复' : '屏蔽' }}
              </el-button>
            </div>
            <div class="bubble">
              <el-image v-if="m.msgType === 'IMAGE'" :src="m.content" fit="cover" class="pic"
                        @click="openViewer(m)" />
              <template v-else-if="m.msgType === 'SUGGEST'">
                <b>建议到院就医</b><span v-if="m.content">：{{ m.content }}</span>
              </template>
              <template v-else>{{ m.content }}</template>
            </div>
          </div>
          <el-button v-if="msgHasMore" :loading="msgLoading" @click="loadEarlier">加载更早消息</el-button>
          <p class="pc-muted more">已加载 {{ msgs.length }} 条（共 {{ msgTotal }} 条）</p>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w140 { width: 140px; }
.blk { margin: 0 0 12px; }
.msgs { display: flex; flex-direction: column; gap: 10px; padding: 4px 2px; }
.msg .meta { display: flex; align-items: baseline; gap: 8px; font-size: 12px; margin-bottom: 2px; }
.msg .bubble {
  display: inline-block; max-width: 92%; padding: 8px 10px; border-radius: 8px;
  background: var(--color-surface-soft); font-size: 13px; line-height: 1.5; word-break: break-word; white-space: pre-wrap;
}
/* 医生气泡是"另一个人说的话"，用一档冷面区分，不能借用主题遮罩（那是 42% 黑） */
.msg.doctor .bubble { background: var(--color-surface-cool); }
.msg.system .bubble { background: transparent; color: var(--color-muted); }
.msg.system .meta { justify-content: center; }
.msg.doctor { text-align: right; }
.msg.doctor .meta { justify-content: flex-end; }
/* 审计视图里被屏蔽的消息仍然可见，但用灰底 + 删除线标明已屏蔽 */
.msg.hidden .bubble { background: var(--color-surface-strong); color: var(--color-muted); text-decoration: line-through; }
.hidden-tag { font-size: 12px; color: var(--color-danger-text); }
.pic { max-width: 180px; border-radius: 6px; display: block; }
.more { font-size: 12px; }
</style>
