<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { schedulePage } from '@/api/admin'
import { PERIOD, WORK_STATUS, options, text } from '@/utils/dict'
import { slotEnded, toDate } from '@/utils/format'
import { groupSlotsByDate, localNow } from '@/utils/schedule'
import StatusChip from '@/components/StatusChip.vue'
import LoadFailure from '@/components/LoadFailure.vue'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNum: 1, pageSize: 20, days: [], status: null })
const now = ref(localNow())
const failed = ref(false)
const groups = computed(() => groupSlotsByDate(rows.value).map(group => ({
  ...group, list: [...group.list].sort((a, b) => a.startTime.localeCompare(b.startTime))
})))
const slotState = row => row.doctorAvailable === false || row.status !== 1 ? { label: '已停诊', tone: 'danger' }
  : slotEnded(row, now.value.date, now.value.hhmm) ? { label: '已结束', tone: 'terminated' }
  : row.remainNum <= 0 ? { label: '已约满', tone: 'warning' } : { label: '可预约', tone: 'success' }
const weekday = date => new Date(`${date}T00:00:00`).toLocaleDateString('zh-CN', { weekday: 'long' })
let requestId = 0
let timer = null

async function load() {
  const id = ++requestId
  loading.value = true
  failed.value = false
  try {
    const page = await schedulePage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      start: q.days?.[0] || undefined,
      end: q.days?.[1] || undefined,
      status: q.status ?? undefined
    })
    if (id !== requestId) return
    rows.value = page.records
    total.value = page.total
  } catch {
    if (id === requestId) failed.value = true
  } finally {
    if (id === requestId) loading.value = false
  }
}

function search() {
  q.pageNum = 1
  load()
}

onMounted(() => { load(); timer = setInterval(() => { now.value = localNow() }, 30000) })
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="page schedule-page">
    <div class="pc-card schedule-filters">
      <div class="filter-field dates">
        <label>出诊日期</label>
        <el-date-picker v-model="q.days" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始日期" end-placeholder="结束日期" aria-label="出诊日期范围" @change="search" />
      </div>
      <div class="filter-field status-filter">
        <label>排班状态</label>
        <el-select v-model="q.status" placeholder="全部状态" aria-label="排班状态" clearable @change="search">
          <el-option v-for="o in options(WORK_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
    </div>

    <div v-loading="loading" class="pc-card schedule-list" :aria-busy="loading">
      <LoadFailure v-if="failed" @retry="load" />
      <template v-else>
        <header class="list-heading">
          <h2>出诊安排</h2>
          <span v-if="!loading">共 {{ total }} 个时段<span v-if="total > q.pageSize"> · 本页 {{ rows.length }} 个</span></span>
        </header>
        <section v-for="group in groups" :key="group.date" class="day-group" :aria-label="group.date + '排班'">
          <header class="day-heading">
            <time :datetime="group.date">{{ toDate(group.date) }}</time>
            <span>{{ weekday(group.date) }}</span>
            <b v-if="group.date === now.date" class="today">今天</b>
          </header>
          <ul class="slots">
            <li v-for="row in group.list" :key="row.id" class="slot-row">
              <div class="slot-time">
                <span class="period">{{ text(PERIOD, row.period) }}</span>
                <strong>{{ row.startTime?.slice(0, 5) }} – {{ row.endTime?.slice(0, 5) }}</strong>
              </div>
              <div class="capacity"><span>剩余</span> <b>{{ row.remainNum }}</b><span> / 总量 {{ row.totalNum }}</span></div>
              <StatusChip class="slot-status" :tone="slotState(row).tone" size="lg">{{ slotState(row).label }}</StatusChip>
            </li>
          </ul>
        </section>
        <el-empty v-if="!loading && !rows.length" description="没有符合条件的排班" />
        <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                       :total="total" layout="prev, pager, next" :pager-count="5" background small class="pager" @current-change="load" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.schedule-page { display: flex; flex-direction: column; gap: 16px; max-width: 1080px; }
.schedule-filters { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 16px; padding: 20px 24px; }
.filter-field { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.filter-field label { color: var(--color-muted); font-size: 14px; line-height: 1.5; }
.dates { width: 320px; max-width: 100%; }
.dates :deep(.el-date-editor) { width: 100%; min-width: 0; }
.status-filter { width: 180px; }
.schedule-list { container-type: inline-size; padding: 24px; min-height: 200px; }
.list-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px 16px; margin-bottom: 24px; }
.list-heading h2 { font-size: 18px; line-height: 1.5; margin: 0; font-weight: 600; }
.list-heading > span { font-size: 14px; line-height: 1.6; color: var(--color-muted); }
.day-group { display: grid; grid-template-columns: 164px minmax(0, 1fr); border-top: 1px solid var(--color-hairline-soft); }
.day-heading { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 22px 16px 22px 0; }
.day-heading time { font-size: 16px; line-height: 1.6; font-weight: 600; font-variant-numeric: tabular-nums; }
.day-heading > span { font-size: 14px; color: var(--color-muted); }
.today { font-size: 13px; line-height: 1.6; font-weight: 500; color: var(--color-ink); background: var(--color-surface-strong); padding: 2px 8px; border-radius: var(--radius-xs); }
.slots { padding: 0; margin: 0; list-style: none; min-width: 0; }
.slot-row { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(150px, .8fr) auto; align-items: center; gap: 16px; padding: 16px 0; }
.slot-row + .slot-row { border-top: 1px solid var(--color-hairline-soft); }
.slot-time { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.period { font-size: 14px; color: var(--color-muted); }
.slot-time strong { font-size: 16px; font-weight: 600; line-height: 1.6; font-variant-numeric: tabular-nums; }
.capacity { font-size: 14px; line-height: 1.6; color: var(--color-muted); font-variant-numeric: tabular-nums; }
.capacity b { font-size: 18px; font-weight: 600; color: var(--color-ink); }
.slot-status { justify-self: end; }
@container (max-width: 750px) {
  .day-group { grid-template-columns: 140px minmax(0, 1fr); }
  .slot-row { grid-template-columns: 1fr auto; gap: 8px 12px; }
  .slot-time, .capacity { grid-column: 1; }
  .slot-status { grid-column: 2; grid-row: 1 / 3; }
}
@container (max-width: 450px) {
  .day-group { display: block; }
  .day-heading { flex-direction: row; align-items: center; flex-wrap: wrap; gap: 8px; padding: 16px 0 4px; }
}
@media (max-width: 1100px) {
  .day-group { grid-template-columns: 140px minmax(0, 1fr); }
  .slot-row { grid-template-columns: 1fr auto; gap: 8px 12px; }
  .slot-time { grid-column: 1; }
  .capacity { grid-column: 1; }
  .slot-status { grid-column: 2; grid-row: 1 / 3; }
}
@media (max-width: 600px) {
  .schedule-filters, .schedule-list { padding: 16px; }
  .filter-field { width: 100%; }
  .day-group { display: block; }
  .day-heading { flex-direction: row; align-items: center; flex-wrap: wrap; gap: 8px; padding: 16px 0 4px; }
  .slot-row { padding: 16px 0; }
  .slot-time { gap: 8px; }
  .list-heading { margin-bottom: 16px; }
}
@media (max-width: 380px) {
  .slot-row { display: flex; flex-wrap: wrap; gap: 8px 12px; }
  .slot-time { width: 100%; }
  .slot-status { margin-left: auto; }
}
</style>
