<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ElMessageBox } from '@/utils/messageBox'
import {
  batchSchedule, createSchedule, doctorPage, removeSchedule, resumeSchedule,
  schedulePage, stopSchedule, updateSchedule
} from '@/api/admin'
import { PERIOD, WORK_STATUS, options, tagType, text } from '@/utils/dict'
import { dayLabel, toDate, today } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

/**
 * 排班号源管理。号池由 totalNum / remainNum 两个数决定，
 * 这里只允许改总量与停诊状态——余量是下单结果，不能手改。
 * 归属（医生/日期/时段）一旦被任何预约引用过（含已取消）就冻结，只能另建时段（I10）。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const doctors = ref([])
const range = ref([])
const shortcuts = [
  { text: '今天', value: () => [new Date(), new Date()] },
  { text: '未来 7 天', value: () => { const end = new Date(); end.setDate(end.getDate() + 6); return [new Date(), end] } }
]

const q = reactive({ pageNum: 1, pageSize: 20, doctorId: null, status: null })
// 查询时提交筛选快照，翻页和操作后的刷新沿用已提交条件。
let applied = { ...q }
let appliedRange = []

const editOpen = ref(false)
const batchOpen = ref(false)
const editing = ref(null)
const busy = ref(false)

/**
 * 新建时 form.status 有效；编辑时不提交它——停诊与恢复只走列表行的按钮
 * （对应的 /stop、/resume 接口）。编辑弹窗里再放一个状态单选，就等于给同一条排班
 * 开两个写入点，一个停留了很久的弹窗被保存时会把中途的停诊覆盖回去。
 */
const blank = () => ({ doctorId: null, workDate: today(), period: 1, startTime: '08:30:00', endTime: '12:00:00', totalNum: 20, status: 1 })
const form = reactive(blank())

// I10：refNum 是后端给的"引用过这张排的预约条数（含已取消）"，>0 即归属冻结。
// 置灰只是提示，真正的拦截在服务层，前端不能当成鉴权用。
const frozen = computed(() => (editing.value?.refNum || 0) > 0)
const doctorStopped = computed(() => form.doctorId === editing.value?.doctorId
  ? editing.value.doctorAvailable === false
  : doctors.value.find(d => d.id === form.doctorId)?.status === 0)

const blankBatch = () => ({
  doctorId: null, startDate: today(), endDate: '', weekDays: [1, 2, 3, 4, 5],
  overrideTotalNum: null, periods: [{ period: 1, startTime: '08:30:00', endTime: '12:00:00', totalNum: 20 }]
})
const batch = reactive(blankBatch())

const periodTimes = { 1: ['08:30:00', '12:00:00'], 2: ['13:30:00', '17:30:00'], 3: ['18:00:00', '21:30:00'] }
const periodRanges = { 1: ['08:00:00', '12:00:00'], 2: ['13:00:00', '18:00:00'], 3: ['18:00:00', '22:00:00'] }
const periodRange = (v) => periodRanges[v] || []
const samePeriodTime = (a, b) => a.period === b.period && a.startTime === b.startTime && a.endTime === b.endTime
function periodError(p) {
  const [start, end] = periodRange(p.period)
  if (!start) return '请选择有效时段'
  if (!p.startTime || !p.endTime || p.startTime >= p.endTime) return '结束时间必须晚于开始时间'
  if (p.startTime < start || p.endTime > end) return `${text(PERIOD, p.period)}排班时间须在 ${start.slice(0, 5)}–${end.slice(0, 5)} 内`
  return ''
}
// 时段模板按取值 1/2/3 取时间：字典里若混进别的 key（如手改过的本地快照），
// 兜底成空数组，别让 periodTimes[v][0] 直接抛异常
const periodTime = (v) => periodTimes[v] || []

async function load() {
  loading.value = true
  try {
    const page = await schedulePage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      doctorId: applied.doctorId ?? undefined,
      status: applied.status ?? undefined,
      start: appliedRange?.[0] || undefined,
      end: appliedRange?.[1] || undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  applied = { ...q }
  appliedRange = [...(range.value || [])]
  q.pageNum = 1
  load()
}

function openCreate() {
  editing.value = null
  Object.assign(form, blank(), { doctorId: q.doctorId || doctors.value[0]?.id })
  editOpen.value = true
}

function openEdit(row) {
  editing.value = row
  // 不带 row.status：编辑不写状态列，弹窗里也不再放这个控件
  Object.assign(form, {
    doctorId: row.doctorId, workDate: row.workDate, period: row.period,
    startTime: row.startTime, endTime: row.endTime, totalNum: row.totalNum
  })
  editOpen.value = true
}

function onPeriod(v) {
  const [s, e] = periodTimes[v] || []
  form.startTime = s
  form.endTime = e
}

async function submit() {
  if (!form.doctorId) return ElMessage.warning('请选择医生')
  if (!form.workDate) return ElMessage.warning('请选择排班日期')
  if (!(form.startTime < form.endTime)) return ElMessage.warning('结束时间必须晚于开始时间')
  if (!editing.value || !samePeriodTime(form, editing.value)) {
    const error = periodError(form)
    if (error) return ElMessage.warning(error)
  }
  busy.value = true
  try {
    const payload = {
      doctorId: form.doctorId, workDate: form.workDate, period: form.period,
      startTime: form.startTime, endTime: form.endTime, totalNum: form.totalNum
    }
    // 编辑只提交这六个字段：status 不在编辑契约里，带上会被服务端忽略、也容易误导下一个人
    if (editing.value) await updateSchedule(editing.value.id, payload)
    else await createSchedule({ ...payload, status: form.status })
    ElMessage.success('已保存')
    editOpen.value = false
    load()
  } finally {
    busy.value = false
  }
}

async function submitBatch() {
  if (!batch.doctorId) return ElMessage.warning('请选择医生')
  if (!batch.endDate) return ElMessage.warning('请选择结束日期')
  if (batch.startDate > batch.endDate) return ElMessage.warning('开始日期不能晚于结束日期')
  if (!batch.weekDays.length) return ElMessage.warning('请选择每周的出诊日')
  if (!batch.periods.length) return ElMessage.warning('请至少配置一个时段')
  if (new Set(batch.periods.map((p) => p.period)).size !== batch.periods.length) {
    return ElMessage.warning('每个时段只能配置一次')
  }
  for (const p of batch.periods) {
    const error = periodError(p)
    if (error) return ElMessage.warning(error)
  }
  busy.value = true
  try {
    const r = await batchSchedule({
      ...batch,
      overrideTotalNum: batch.overrideTotalNum || undefined,
      periods: batch.periods.map((p) => ({ ...p }))
    })
    ElMessage.success(`新增 ${r.created} 个时段，跳过重复 ${r.skipped} 个`)
    batchOpen.value = false
    load()
  } finally {
    busy.value = false
  }
}

function addPeriod() {
  const used = new Set(batch.periods.map((p) => p.period))
  const next = [1, 2, 3].find((p) => !used.has(p))
  if (!next) return
  const [s, e] = periodTimes[next]
  batch.periods.push({ period: next, startTime: s, endTime: e, totalNum: 20 })
}

async function toggleStop(row) {
  if (row.status === 1) {
    await stopSchedule(row.id)
    ElMessage.success('已停诊，已成交的预约不受影响')
  } else {
    await resumeSchedule(row.id)
    ElMessage.success('排班状态已更新')
  }
  load()
}

async function del(row) {
  const ok = await ElMessageBox.confirm(
    `删除 ${toDate(row.workDate)} ${text(PERIOD, row.period)} 的排班？`
    + (row.refNum > 0
      ? `该时段已有 ${row.refNum} 条预约记录，无法删除，可改为停诊。`
      : '已有预约记录的时段无法删除，可改为停诊。'),
    '删除排班', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await removeSchedule(row.id)
  ElMessage.success('已删除')
  load()
}

const booked = (r) => r.totalNum - r.remainNum

onMounted(() => {
  doctorPage({ pageNum: 1, pageSize: 100 }).then((p) => (doctors.value = p.records))
  load()
})
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-select v-model="q.doctorId" placeholder="全部医生" clearable filterable class="w150">
        <el-option v-for="d in doctors" :key="d.id" :label="`${d.name}（${d.departmentName}）`" :value="d.id" />
      </el-select>
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w120">
        <el-option v-for="o in options(WORK_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-date-picker v-model="range" :shortcuts="shortcuts" type="daterange" value-format="YYYY-MM-DD" clearable
                      start-placeholder="排班开始日期" end-placeholder="排班结束日期" class="w250" />
      <el-button :loading="loading" @click="search">查询</el-button>
      <div class="sp" />
      <el-button @click="batchOpen = true; Object.assign(batch, blankBatch(), { doctorId: q.doctorId || doctors[0]?.id })">
        批量排班
      </el-button>
      <el-button type="primary" @click="openCreate">新建时段</el-button>
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>排班时段</b><span>共 {{ total }} 个</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="该区间还没有排班">
        <el-table-column label="医生" min-width="120">
          <template #default="{ row }"><div class="schedule-stack"><b>{{ row.doctorName }}</b><span class="pc-muted">{{ row.doctorTitle }}</span></div></template>
        </el-table-column>
        <el-table-column label="科室" min-width="100" prop="departmentName" />
        <el-table-column label="就诊日期" min-width="180">
          <template #default="{ row }">{{ toDate(row.workDate) }} <span class="pc-muted">{{ dayLabel(row.workDate) }}</span></template>
        </el-table-column>
        <el-table-column label="时段" min-width="150">
          <template #default="{ row }">{{ text(PERIOD, row.period) }} {{ row.startTime?.slice(0, 5) }}-{{ row.endTime?.slice(0, 5) }}</template>
        </el-table-column>
        <el-table-column label="号源" min-width="150">
          <template #default="{ row }">
            <span>剩余 <b>{{ row.remainNum }}</b> / {{ row.totalNum }}</span>
          </template>
        </el-table-column>
        <el-table-column label="问诊费" width="92">
          <template #default="{ row }">¥ {{ row.consultationFee }}</template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <StatusChip :tone="tagType(WORK_STATUS, row.status)">{{ text(WORK_STATUS, row.status) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">调整</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small"
                       :disabled="row.status === 0 && row.doctorAvailable === false"
                       :title="row.status === 0 && row.doctorAvailable === false ? '请先恢复医生出诊' : ''" @click="toggleStop(row)">
              {{ row.status === 1 ? '停诊' : '恢复' }}
            </el-button>
            <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-dialog v-model="editOpen" :title="editing ? '调整排班' : '新建时段'" width="480px">
      <el-form :model="form" label-width="86px">
        <h3 class="form-section">医生与就诊安排</h3>
        <el-form-item label="医生" required>
          <el-select v-model="form.doctorId" filterable :disabled="frozen" class="full">
            <el-option v-for="d in doctors" :key="d.id" :label="`${d.name}（${d.departmentName}）`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期" required>
          <el-date-picker v-model="form.workDate" type="date" value-format="YYYY-MM-DD" :disabled="frozen" class="full" />
        </el-form-item>
        <el-form-item label="时段" required>
          <el-select v-model="form.period" :disabled="frozen" class="full" @change="onPeriod">
            <el-option v-for="o in options(PERIOD)" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="起止时间" required>
          <div class="two">
            <el-time-select v-model="form.startTime" :start="periodRange(form.period)[0]" :end="periodRange(form.period)[1]"
                            :max-time="periodRange(form.period)[1]" step="00:30" format="HH:mm:ss" placeholder="开始"
                            :disabled="frozen" />
            <el-time-select v-model="form.endTime" :start="periodRange(form.period)[0]" :end="periodRange(form.period)[1]"
                            :min-time="form.startTime || periodRange(form.period)[0]" step="00:30" format="HH:mm:ss" placeholder="结束"
                            :disabled="frozen" />
          </div>
          <span v-if="frozen" class="pc-muted tip">
            已有 {{ editing.refNum }} 条预约记录（含已取消），仅可调整号源总数。更换时间请新建时段。
          </span>
        </el-form-item>
        <h3 class="form-section">号源设置</h3>
        <el-form-item label="号源总数" required>
          <el-input-number v-model="form.totalNum" :min="editing ? Math.max(1, booked(editing)) : 1" :max="200" />
        </el-form-item>
        <!-- 编辑不提供状态开关：停诊/恢复在列表行上，走 /stop 与 /resume 两个专用接口 -->
        <el-form-item v-if="!editing" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="o in options(WORK_STATUS)" :key="o.value" :value="o.value"
                      :disabled="o.value === 1 && doctorStopped">{{ o.label }}</el-radio>
          </el-radio-group>
          <span v-if="doctorStopped" class="pc-muted">医生已停诊，恢复排班前请先恢复医生出诊</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchOpen" title="批量排班" width="620px" top="6vh">
      <el-form :model="batch" label-width="100px">
        <el-form-item label="医生" required>
          <el-select v-model="batch.doctorId" filterable class="full">
            <el-option v-for="d in doctors" :key="d.id" :label="`${d.name}（${d.departmentName}）`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="排班日期" required>
          <div class="two">
            <el-date-picker v-model="batch.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
            <el-date-picker v-model="batch.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
          </div>
        </el-form-item>
        <el-form-item label="每周出诊日" required>
          <el-checkbox-group v-model="batch.weekDays">
            <el-checkbox v-for="(n, i) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']" :key="n" :value="i + 1">
              {{ n }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="出诊时段">
          <div class="tpl">
            <div v-for="(p, i) in batch.periods" :key="i" class="tpl-r">
              <el-select v-model="p.period" class="w90" @change="(v) => Object.assign(p, { startTime: periodTime(v)[0], endTime: periodTime(v)[1] })">
                <el-option v-for="o in options(PERIOD)" :key="o.value" :label="o.label" :value="o.value"
                           :disabled="batch.periods.some((other) => other !== p && other.period === o.value)" />
              </el-select>
              <el-time-select v-model="p.startTime" :start="periodRange(p.period)[0]" :end="periodRange(p.period)[1]"
                              :max-time="periodRange(p.period)[1]" step="00:30" format="HH:mm:ss" placeholder="开始时间" class="w110" />
              <el-time-select v-model="p.endTime" :start="periodRange(p.period)[0]" :end="periodRange(p.period)[1]"
                              :min-time="p.startTime || periodRange(p.period)[0]" step="00:30" format="HH:mm:ss" placeholder="结束时间" class="w110" />
              <el-input-number v-model="p.totalNum" :min="1" :max="200" class="w120" controls-position="right" />
              <el-button link type="danger" :disabled="batch.periods.length === 1" @click="batch.periods.splice(i, 1)">删除</el-button>
            </div>
            <el-button link type="primary" :disabled="batch.periods.length >= 3" @click="addPeriod">+ 再加一个时段</el-button>
          </div>
        </el-form-item>
        <el-form-item label="统一号源数">
          <el-input-number v-model="batch.overrideTotalNum" :min="1" :max="200" placeholder="选填" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitBatch">生成排班</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w90 { width: 90px; }
.w110 { width: 110px; }
.w120 { width: 120px; }
.w150 { width: 150px; }
.w250 { width: 250px; }
.full { width: 100%; }
.two { display: flex; gap: 8px; width: 100%; }
.two :deep(.el-date-editor), .two :deep(.el-select) { flex: 1; width: auto; }
.tip { margin-left: 10px; font-size: 12px; }
.tpl { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.tpl-r { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.form-section { margin: 0 0 18px; font-size: 14px; font-weight: 600; }
.schedule-stack { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; }
.schedule-stack b { font-weight: 600; }
.schedule-stack .el-tag { height: auto; white-space: normal; }
</style>
