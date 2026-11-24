<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ElMessageBox } from '@/utils/messageBox'
import { accept, appointmentPage, cancel, doctorPage, finish } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { APPT_STATUS, CONSULT_TYPE, PAY_STATUS, PERIOD, options, tagType, text } from '@/utils/dict'
import { dayLabel, money, toDate, today } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'
import LoadFailure from '@/components/LoadFailure.vue'

const store = useAdminStore()
const route = useRoute()
const router = useRouter()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const doctors = ref([])
const range = ref(route.query.day ? [route.query.day, route.query.day]
  : route.query.start && route.query.end ? [route.query.start, route.query.end]
    : store.isDoctor ? [today(), today()] : [])
const failed = ref(false)
const loaded = ref(false)
const actingId = ref(null)
let requestId = 0
const shortcuts = [
  { text: '今天', value: () => [new Date(), new Date()] },
  { text: '明天', value: () => { const d = new Date(); d.setDate(d.getDate() + 1); return [d, d] } },
  { text: '未来 7 天', value: () => { const end = new Date(); end.setDate(end.getDate() + 6); return [new Date(), end] } }
]
const numberParam = (v, fallback = null) => v !== undefined && Number.isFinite(Number(v)) ? Number(v) : fallback

const q = reactive({
  pageNum: Math.max(1, numberParam(route.query.page, 1)),
  pageSize: 20,
  status: numberParam(route.query.status),
  payStatus: numberParam(route.query.payStatus),
  doctorId: numberParam(route.query.doctorId)
})

async function load() {
  const current = ++requestId
  loading.value = true
  failed.value = false
  try {
    const page = await appointmentPage({
      pageNum: Math.max(1, numberParam(route.query.page, 1)),
      pageSize: q.pageSize,
      status: numberParam(route.query.status) ?? undefined,
      payStatus: numberParam(route.query.payStatus) ?? undefined,
      doctorId: numberParam(route.query.doctorId) ?? undefined,
      start: route.query.day || route.query.start || undefined,
      end: route.query.day || route.query.end || undefined
    })
    if (current !== requestId) return
    rows.value = page.records
    total.value = page.total
  } catch {
    if (current === requestId) failed.value = true
  } finally {
    if (current === requestId) {
      loading.value = false
      loaded.value = true
    }
  }
}

function applyQuery(query) {
  const location = { path: '/admin/appointments', query }
  if (router.resolve(location).fullPath === route.fullPath) load()
  else router.push(location)
}
function navigate(page = 1) {
  // 原因: 地址中保存已应用查询条件，翻页不提交表单草稿
  applyQuery({ ...route.query, page: String(page) })
}
function search() {
  const query = { page: '1' }
  for (const key of ['status', 'payStatus', 'doctorId']) if (q[key] !== null && q[key] !== '') query[key] = String(q[key])
  if (range.value?.[0] && range.value?.[1]) Object.assign(query, { start: range.value[0], end: range.value[1] })
  applyQuery(query)
}
function reset() {
  Object.assign(q, { status: null, payStatus: null, doctorId: null })
  // 原因: 医生默认视图为今日接诊清单，重置时回到今天而非全量日期
  range.value = store.isDoctor ? [today(), today()] : []
  search()
}
watch(() => route.query, (query, previous) => {
  if (route.path !== '/admin/appointments') return
  q.pageNum = Math.max(1, numberParam(query.page, 1))
  const filtersChanged = ['status', 'payStatus', 'doctorId', 'day', 'start', 'end'].some(key => query[key] !== previous[key])
  if (filtersChanged) {
    for (const key of ['status', 'payStatus', 'doctorId']) q[key] = numberParam(query[key])
    range.value = query.day ? [query.day, query.day] : query.start && query.end ? [query.start, query.end] : []
  }
  load()
})
const detailLocation = row => ({ path: `/admin/appointment/${row.id}`, query: { returnTo: route.fullPath } })

const groups = computed(() => {
  const buckets = new Map()
  for (const row of rows.value) {
    const key = toDate(row.appointmentDate)
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(row)
  }
  return [...buckets.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, list]) => ({
      date,
      list: [...list].sort((a, b) => (a.startTime || '').localeCompare(b.startTime || '') || (a.queueNo || 0) - (b.queueNo || 0))
    }))
})
const weekday = date => new Date(`${date}T00:00:00`).toLocaleDateString('zh-CN', { weekday: 'long' })
const dayTag = date => {
  const label = dayLabel(date)
  return label === '今天' || label === '明天' ? label : ''
}
const canAccept = row => row.status === 0 && row.payStatus === 1
const isTodayOnly = () => route.query.day === today()
  || (route.query.start === today() && route.query.end === today())
const emptyText = computed(() => store.isDoctor && isTodayOnly()
  ? '今天没有符合条件的预约'
  : '没有符合条件的预约单')

const dateStr = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
function showWeek() {
  const end = new Date()
  end.setDate(end.getDate() + 6)
  range.value = [today(), dateStr(end)]
  search()
}

// 原因: 接诊与结束为一次性状态迁移，单行执行期间加锁防连点触发状态冲突报错
async function run(row, action) {
  if (actingId.value) return
  actingId.value = row.id
  try {
    await action(row)
  } finally {
    actingId.value = null
  }
}

const doAccept = row => run(row, async () => {
  await accept(row.id)
  ElMessage.success('已开始接诊')
  load()
})

async function doFinish(row) {
  const ok = await ElMessageBox.confirm('完成后该单可写病历、可由宠物主人评价，确定结束就诊？', '结束就诊', {
    type: 'warning', confirmButtonText: '确定完成', cancelButtonText: '取消'
  }).then(() => true).catch(() => false)
  if (!ok) return
  await run(row, async () => {
    await finish(row.id)
    ElMessage.success('本次就诊已完成')
    load()
  })
}

async function doCancel(row) {
  const { value } = await ElMessageBox.prompt('已支付的预约取消后将标记为已退款', '取消预约单', {
    inputPlaceholder: '取消原因（选填）', confirmButtonText: '确认取消', cancelButtonText: '再想想'
  }).catch(() => ({}))
  if (value === undefined) return
  await run(row, async () => {
    await cancel(row.id, { reason: value || undefined })
    ElMessage.success('预约单已取消')
    load()
  })
}

onMounted(() => {
  if (store.isAdmin) {
    doctorPage({ pageNum: 1, pageSize: 100 }).then((p) => (doctors.value = p.records))
    load()
    return
  }
  // 原因: 医生首次进入无日期参数时默认落到今日，并将条件写入地址栏便于分享与刷新
  if (!route.query.day && !(route.query.start && route.query.end)) {
    router.replace({
      path: '/admin/appointments',
      query: { ...route.query, start: today(), end: today() }
    })
    return
  }
  load()
})
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w130">
        <el-option v-for="o in options(APPT_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="q.payStatus" placeholder="全部支付" clearable class="w130">
        <el-option v-for="o in options(PAY_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-if="store.isAdmin" v-model="q.doctorId" placeholder="全部医生" clearable filterable class="w150">
        <el-option v-for="d in doctors" :key="d.id" :label="`${d.name}（${d.title}）`" :value="d.id" />
      </el-select>
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :shortcuts="shortcuts" start-placeholder="就诊开始日期"
                      end-placeholder="就诊结束日期" class="w240" />
      <div class="sp" />
      <el-button @click="reset">重置</el-button>
      <el-button type="primary" :loading="loading" @click="search">查询</el-button>
    </div>

    <LoadFailure v-if="failed" @retry="load" />

    <div v-else-if="store.isDoctor" class="pc-card appt-card" :aria-busy="loading">
      <div class="list-head">
        <h2>接诊清单</h2>
        <span v-if="!loading">共 {{ total }} 单<span v-if="total > q.pageSize"> · 本页 {{ rows.length }} 单</span></span>
      </div>
      <div v-loading="loading">
        <section v-for="g in groups" :key="g.date" class="day-group" :aria-label="`${toDate(g.date)}预约`">
          <header class="day-head">
            <time :datetime="g.date">{{ toDate(g.date) }}</time>
            <span class="week">{{ weekday(g.date) }}</span>
            <b v-if="dayTag(g.date)" class="today">{{ dayTag(g.date) }}</b>
            <span v-if="groups.length > 1" class="count">{{ total > q.pageSize ? '本页 ' : '' }}{{ g.list.length }} 单</span>
          </header>
          <article v-for="row in g.list" :key="row.id" class="appt-row">
            <div class="slot">
              <b>{{ row.startTime?.slice(0, 5) }}</b>
              <span>{{ text(PERIOD, row.period) }}<template v-if="row.queueNo"> · {{ row.queueNo }} 号</template></span>
            </div>
            <div class="body">
              <div class="headline">
                <b class="pet">{{ row.petName }}</b>
                <span class="pc-muted">{{ row.petSpecies }}<template v-if="row.petBreed"> · {{ row.petBreed }}</template></span>
                <StatusChip :tone="tagType(APPT_STATUS, row.status)">{{ text(APPT_STATUS, row.status) }}</StatusChip>
                <!-- 原因: 已支付是默认态，仅在未支付或已退款时渲染胶囊以突出异常状态 -->
                <StatusChip v-if="row.payStatus !== 1" :tone="tagType(PAY_STATUS, row.payStatus)">{{ text(PAY_STATUS, row.payStatus) }}</StatusChip>
              </div>
              <div class="meta">
                <span>主人 {{ row.userNickname || '—' }}</span>
                <span>{{ text(CONSULT_TYPE, row.consultType) }}</span>
                <span class="order" :title="`预约单号 ${row.orderNo}`">单号 {{ row.orderNo }}</span>
              </div>
              <p v-if="row.symptom" class="sym">{{ row.symptom }}</p>
            </div>
            <div class="ops">
              <el-button v-if="canAccept(row)" type="primary" class="act" :loading="actingId === row.id" @click="doAccept(row)">接诊</el-button>
              <template v-else-if="row.status === 1">
                <el-button type="primary" class="act" @click="router.push(`/admin/workbench/${row.id}`)">继续接诊</el-button>
                <el-button class="act" :loading="actingId === row.id" @click="doFinish(row)">完成</el-button>
              </template>
              <el-button v-else-if="row.status === 2" :type="row.recordId ? '' : 'primary'" class="act"
                         @click="router.push(`/admin/workbench/${row.id}`)">
                {{ row.recordId ? '查看病历' : '写病历' }}
              </el-button>
              <el-button link @click="router.push(detailLocation(row))">详情</el-button>
              <el-button v-if="row.status === 0" link type="danger" :disabled="actingId === row.id" @click="doCancel(row)">取消</el-button>
            </div>
          </article>
        </section>
        <el-empty v-if="!loading && loaded && !rows.length" :description="emptyText">
          <el-button v-if="isTodayOnly()" type="primary" @click="showWeek">查看未来 7 天</el-button>
          <el-button v-else @click="reset">回到今天</el-button>
        </el-empty>
      </div>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager"
                     @current-change="navigate" />
    </div>

    <div v-else class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>预约列表</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" row-key="id" empty-text="没有符合条件的预约单">
        <el-table-column label="宠物 / 预约单号" min-width="190">
          <template #default="{ row }"><div class="cell-stack"><b>{{ row.petName }} <span class="pc-muted">{{ row.petSpecies }}</span></b><router-link class="link" :to="detailLocation(row)">{{ row.orderNo }}</router-link></div></template>
        </el-table-column>
        <el-table-column label="宠物主人" min-width="130">
          <template #default="{ row }"><div class="cell-stack"><span>{{ row.userNickname }}</span><span class="pc-muted">{{ row.userPhone }}</span></div></template>
        </el-table-column>
        <el-table-column label="医生 / 科室" min-width="120">
          <template #default="{ row }"><div class="cell-stack"><span>{{ row.doctorName }}</span><span class="pc-muted">{{ row.departmentName }}</span></div></template>
        </el-table-column>
        <el-table-column label="就诊时间" min-width="168">
          <template #default="{ row }">
            <div class="cell-stack"><span>{{ toDate(row.appointmentDate) }}</span>
            <span class="pc-muted">{{ text(PERIOD, row.period) }} {{ row.startTime?.slice(0, 5) }}<span v-if="row.queueNo" class="queue"> · {{ row.queueNo }} 号</span></span></div>
          </template>
        </el-table-column>
        <el-table-column label="状态 / 支付" min-width="112">
          <template #default="{ row }"><div class="cell-stack"><StatusChip :tone="tagType(APPT_STATUS, row.status)">{{ text(APPT_STATUS, row.status) }}</StatusChip><StatusChip :tone="tagType(PAY_STATUS, row.payStatus)">{{ text(PAY_STATUS, row.payStatus) }}</StatusChip></div></template>
        </el-table-column>
        <el-table-column label="金额" width="96" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push(detailLocation(row))">详情</el-button>
            <el-button v-if="row.status === 0" link type="danger" size="small" @click="doCancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager"
                     @current-change="navigate" />
    </div>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w130 { width: 130px; }
.w150 { width: 150px; }
.w240 { width: 300px; flex: none; }
.cell-stack { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; }
.cell-stack b { font-weight: 600; }
.cell-stack b span { font-weight: 400; }
.link { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--color-muted); overflow-wrap: anywhere; }
.queue { white-space: nowrap; }

/* —— 医生接诊清单 —— */
.appt-card { padding: 20px 24px; container-type: inline-size; }
.list-head { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px 16px; margin-bottom: 8px; }
.list-head h2 { margin: 0; font-size: 16px; font-weight: 600; line-height: 1.5; }
.list-head span { color: var(--color-muted); font-size: 13px; }
.day-group { border-top: 1px solid var(--color-hairline-soft); }
.day-head { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; padding: 16px 0 6px; }
.day-head time { font-size: 15px; font-weight: 600; line-height: 1.5; font-variant-numeric: tabular-nums; }
.day-head .week { color: var(--color-muted); font-size: 13px; }
.day-head .today {
  padding: 2px 8px; border-radius: var(--radius-xs);
  background: var(--color-surface-strong); color: var(--color-ink);
  font-size: 12px; font-weight: 500; line-height: 1.6;
}
.day-head .count { margin-left: auto; color: var(--color-muted); font-size: 13px; }
.appt-row {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) auto;
  align-items: start;
  gap: 8px 20px;
  padding: 16px 0;
}
.appt-row + .appt-row { border-top: 1px solid var(--color-hairline-soft); }
.slot { display: flex; flex-direction: column; gap: 2px; padding-top: 1px; }
.slot b { font-size: 18px; font-weight: 600; line-height: 1.4; font-variant-numeric: tabular-nums; letter-spacing: -.2px; }
.slot span { color: var(--color-muted); font-size: 13px; line-height: 1.5; white-space: nowrap; }
.body { min-width: 0; }
.headline { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 8px; }
.headline .pet { font-size: 16px; font-weight: 600; line-height: 1.5; }
.headline .pc-muted { font-size: 13px; }
.meta { display: flex; align-items: center; flex-wrap: wrap; gap: 2px 12px; margin-top: 3px; color: var(--color-muted); font-size: 13px; line-height: 1.6; }
.meta .order { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--color-muted); }
.sym {
  margin: 6px 0 0;
  font-size: 13px; line-height: 1.65; color: var(--color-body); overflow-wrap: anywhere;
}
.ops { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.ops .el-button + .el-button { margin-left: 0; }
.ops .act { min-width: 84px; }
/* 文字链单独撑出可点区域：详情与取消相邻，取消还是不可逆动作 */
.ops .el-button.is-link { min-height: 32px; padding: 0 8px; }

/* 卡片实际宽度受后台侧栏影响，用容器查询而不是视口宽度决定行内排布 */
@container (max-width: 820px) {
  .appt-row { grid-template-columns: 80px minmax(0, 1fr); }
  .ops { grid-column: 1 / -1; justify-content: flex-start; padding-left: 100px; }
}
@container (max-width: 600px) {
  .appt-row { grid-template-columns: minmax(0, 1fr); gap: 6px; padding: 14px 0; }
  .slot { flex-direction: row; align-items: baseline; gap: 8px; }
  .slot b { font-size: 17px; }
  .ops { grid-column: auto; justify-content: flex-start; padding-left: 0; margin-top: 4px; gap: 6px; }
  .ops .act { flex: none; min-width: 0; padding-inline: 8px; }
  .ops .el-button.is-link { min-height: 40px; padding: 0 10px; }
  .day-head .count { margin-left: 0; }
}
@media (max-width: 700px) {
  .appt-card { padding: 16px; }
}
@media (max-width: 800px) {
  .filter > .el-select { flex: 1 1 100%; width: 100%; min-width: 0; }
  /* admin.css 给日期范围设了 flex-grow: 0，这里用深度选择器把窄屏整行布局压回去 */
  .filter > :deep(.el-date-editor) { flex: 1 1 100%; width: 100%; min-width: 0; }
  .filter > .el-button { flex: 1; margin-left: 0; }
}
</style>
