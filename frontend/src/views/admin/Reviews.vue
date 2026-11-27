<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { doctorPage, replyReview, reviewPage, reviewStatus } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { REVIEW_STATUS, options, tagType, text } from '@/utils/dict'
import { toDate } from '@/utils/format'
import ImageGallery from '@/components/ImageGallery.vue'
import LoadFailure from '@/components/LoadFailure.vue'
import StatusChip from '@/components/StatusChip.vue'

const store = useAdminStore()
const route = useRoute()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const failed = ref(false)
const doctors = ref([])
const q = reactive({ pageNum: 1, pageSize: 20, doctorId: null, status: null })
// 原因: 翻页与操作刷新沿用已提交筛选快照，避免未点查询的草稿生效
let applied = { ...q }

const open = ref(false)
const editing = ref(null)
const busy = ref(false)
const reply = ref('')

async function load() {
  loading.value = true
  failed.value = false
  try {
    const page = await reviewPage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      doctorId: applied.doctorId ?? undefined,
      status: applied.status ?? undefined
    })
    rows.value = page.records
    total.value = page.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function search() {
  applied = { ...q }
  q.pageNum = 1
  load()
}

function reset() {
  q.doctorId = null
  q.status = null
  search()
}

function doReply(row) {
  editing.value = row
  reply.value = row.reply || ''
  open.value = true
}

async function submit() {
  if (!reply.value.trim()) return ElMessage.warning('请填写回复内容')
  busy.value = true
  try {
    await replyReview(editing.value.id, { reply: reply.value.trim() })
    ElMessage.success('回复已发布')
    open.value = false
    load()
  } finally {
    busy.value = false
  }
}

async function toggle(row) {
  await reviewStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success(row.status === 1 ? '已隐藏' : '已恢复显示')
  load()
}

const imgs = (row) => (row.images || '').split(',').filter(Boolean)

const appointmentLink = (row) => ({
  path: `/admin/appointment/${row.appointmentId}`,
  query: { returnTo: route.fullPath }
})

const scoreDims = (row) => [
  { key: 'rating', label: '综合评分', value: row.rating },
  { key: 'attitude', label: '服务态度', value: row.attitudeScore },
  { key: 'effect', label: '诊疗效果', value: row.effectScore }
]
const scorePct = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? Math.min(100, Math.max(0, (n / 5) * 100)) : 0
}

onMounted(() => {
  if (store.isAdmin) {
    doctorPage({ pageNum: 1, pageSize: 100 })
      .then((p) => (doctors.value = p.records))
      .catch(() => {})
  }
  load()
})
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-select v-if="store.isAdmin" v-model="q.doctorId" placeholder="全部医生" clearable filterable class="w150">
        <el-option v-for="d in doctors" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w130">
        <el-option v-for="o in options(REVIEW_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <div class="sp" />
      <el-button @click="reset">重置</el-button>
      <el-button type="primary" :loading="loading" @click="search">查询</el-button>
    </div>

    <LoadFailure v-if="failed" @retry="load" />
    <template v-else>
      <div class="admin-list-summary"><b>评价列表</b><span>共 {{ total }} 条</span></div>

      <div v-loading="loading" class="list">
        <article v-for="r in rows" :key="r.id" class="pc-card rev">
          <header>
            <el-avatar :size="32" :src="r.userAvatar">{{ r.userNickname?.[0] }}</el-avatar>
            <div class="who">
              <b>{{ r.userNickname }}</b>
            </div>
            <div class="meta">
              <span v-if="r.petName" class="visit">
                {{ r.petName }}<i v-if="r.petSpecies">{{ r.petSpecies }}</i> · {{ toDate(r.appointmentDate) }} 就诊
              </span>
              <router-link v-if="r.orderNo" class="order" :to="appointmentLink(r)" :title="`查看预约单 ${r.orderNo}`">
                预约单 {{ r.orderNo }}
              </router-link>
              <span v-if="r.doctorName" class="doc">{{ r.doctorName }}<i>{{ r.doctorTitle }}</i></span>
              <span v-if="r.isAnonymous === 1" class="anon">匿名提交</span>
              <span class="posted">评价于 {{ toDate(r.createTime) }}</span>
              <StatusChip v-if="r.status !== 1" :tone="tagType(REVIEW_STATUS, r.status)">
                {{ text(REVIEW_STATUS, r.status) }}
              </StatusChip>
            </div>
            <dl class="scores">
              <div v-for="s in scoreDims(r)" :key="s.key" class="score">
                <dt>{{ s.label }}</dt>
                <dd>
                  <b>{{ s.value ?? '—' }}</b><span class="of">/5</span>
                </dd>
                <span class="bar" aria-hidden="true"><i :style="{ width: `${scorePct(s.value)}%` }" /></span>
              </div>
            </dl>
          </header>

          <p class="body">{{ r.content || '用户未填写评价内容' }}</p>
          <ImageGallery v-if="imgs(r).length" class="pics" :urls="imgs(r)" :size="76" label="评价附图" />
          <p v-if="r.reply" class="rep">
            <span class="rep-text">医生回复：{{ r.reply }}</span>
            <i class="pc-muted">{{ toDate(r.replyTime) }}</i>
          </p>

          <footer>
            <el-button size="small" type="primary" plain @click="doReply(r)">{{ r.reply ? '修改回复' : '回复' }}</el-button>
            <el-button v-if="store.isAdmin" size="small" plain @click="toggle(r)">
              {{ r.status === 1 ? '隐藏' : '恢复显示' }}
            </el-button>
          </footer>
        </article>

        <div v-if="!loading && !rows.length" class="pc-card empty-card">
          <el-empty description="还没有收到评价" />
        </div>
      </div>

      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </template>

    <el-dialog v-model="open" title="回复评价" width="520px">
      <p v-if="editing" class="quote">{{ editing.content || '（用户未填写内容）' }}</p>
      <el-input v-model="reply" type="textarea" :rows="4" maxlength="300" show-word-limit
                placeholder="请输入回复，将公开展示在医生主页" />
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">发布回复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w130 { width: 130px; }
.w150 { width: 150px; }
.list { display: grid; gap: 12px; }
.rev header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  grid-template-areas:
    "avatar who scores"
    "avatar meta scores";
  align-items: start;
  gap: 3px 10px;
}
.rev header > .el-avatar { grid-area: avatar; }
.who { grid-area: who; min-width: 0; }
.who b { font-size: 14px; line-height: 1.5; }
.meta {
  grid-area: meta;
  display: flex; align-items: center; flex-wrap: wrap; gap: 2px 12px;
  color: var(--color-muted); font-size: 13px; line-height: 1.5;
}
.meta .doc i { margin-left: 4px; font-style: normal; color: var(--color-muted); }
.meta .anon { color: var(--color-muted); }
.meta .visit { color: var(--color-ink); }
.meta .visit i { margin-left: 4px; font-style: normal; color: var(--color-muted); }
.order { color: var(--color-ink); font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; text-decoration: underline; text-decoration-color: var(--color-hairline); text-underline-offset: 3px; }
.order:hover { text-decoration-color: currentColor; }
.order:focus-visible { outline: 2px solid var(--color-ink); outline-offset: 3px; border-radius: var(--radius-xxs); }
.scores { grid-area: scores; display: flex; align-items: flex-start; align-self: center; margin: 0; }
.score { padding: 0 16px; border-left: 1px solid var(--color-hairline-soft); }
.score:first-child { border-left: 0; }
.score dt { color: var(--color-muted); font-size: 13px; line-height: 1.4; }
.score dd { display: flex; align-items: baseline; margin: 2px 0 0; }
.score dd b { color: var(--color-ink); font-size: 18px; font-weight: 600; line-height: 1.3; font-variant-numeric: tabular-nums; }
.score .of { color: var(--color-muted); font-size: 12px; }
.bar { display: block; width: 72px; height: 4px; margin-top: 8px; border-radius: var(--radius-full); background: var(--color-hairline); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: inherit; background: var(--color-ink); }
.body { overflow-wrap: anywhere; margin: 10px 0 0; font-size: 14px; line-height: 1.75; }
.pics { margin-top: 10px; }
.rep {
  display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 8px;
  margin: 10px 0 0; padding: 8px 12px; background: var(--color-surface-soft); border-radius: 8px;
  font-size: 13px; line-height: 1.7; color: var(--color-ink);
}
.rep-text { overflow-wrap: anywhere; }
.rep i { flex: none; font-style: normal; font-size: 12px; white-space: nowrap; }
footer { display: flex; gap: 10px; margin-top: 12px; }
.pager { align-self: flex-end; }
.empty-card { padding: 8px 16px; }
.quote {
  margin: 0 0 12px; padding: 9px 12px; background: var(--color-surface-soft); border-left: 3px solid var(--color-hairline);
  border-radius: 0 8px 8px 0; font-size: 13px; line-height: 1.7;
}

/* 评分块随卡片宽度让位：宽屏跟在标题行右侧，窄屏独占一行三等分。 */
@media (max-width: 900px) {
  .rev header {
    grid-template-areas:
      "avatar who who"
      "meta meta meta"
      "scores scores scores";
  }
  .scores {
    width: 100%;
    margin-top: 2px;
    padding-top: 10px;
    border-top: 1px solid var(--color-hairline-soft);
  }
  .score { flex: 1; padding: 0 0 0 16px; }
  .score:first-child { padding-left: 0; }
  .bar { width: 100%; max-width: 88px; }
}

@media (max-width: 800px) {
  .filter > .el-select { flex: 1 1 100%; width: 100%; min-width: 0; }
  .filter > .el-button { flex: 1; margin-left: 0; }
}
</style>
