<script>
let echartsPromise = null

function loadEcharts() {
  if (!echartsPromise) {
    echartsPromise = Promise.all([
      import('echarts/core'),
      import('echarts/charts'),
      import('echarts/components'),
      import('echarts/renderers')
    ]).then(([
      echarts,
      { BarChart, LineChart, PieChart },
      { GridComponent, LegendComponent, TooltipComponent },
      { CanvasRenderer }
    ]) => {
      echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
      return echarts
    }).catch((err) => {
      echartsPromise = null
      throw err
    })
  }
  return echartsPromise
}
</script>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { consultUnread, dashboard } from '@/api/admin'
import LoadFailure from '@/components/LoadFailure.vue'
import { useAdminStore } from '@/stores/admin'
import { APPT_STATUS, tagType, text } from '@/utils/dict'
import { money, today } from '@/utils/format'

/**
 * 后台看板。图表子模块动态按需加载，不阻塞 KPI 卡片与待办区块先渲染。
 */

const store = useAdminStore()
const router = useRouter()
const days = ref(14)
const vo = ref(null)
const loading = ref(false)
const failed = ref(false)
const unreadCount = ref(null)
const unreadLoading = ref(false)
const unreadFailed = ref(false)
let requestId = 0
let unreadRequestId = 0
let echartsLib = null

const elTrend = ref(null)
const elStatus = ref(null)
const elDept = ref(null)
const elTop = ref(null)
const charts = []
const chartResize = new ResizeObserver(entries => {
  for (const { target } of entries) {
    if (target.clientWidth && target.clientHeight) echartsLib?.getInstanceByDom(target)?.resize()
  }
})

function disposeCharts() {
  chartResize.disconnect()
  charts.forEach(c => c.dispose())
  charts.length = 0
}

function mount(echarts, el, option) {
  // 空数据时容器隐藏；等内容可见再创建画布。
  if (!el?.isConnected || !el.clientWidth || !el.clientHeight) return
  const c = echarts.getInstanceByDom(el) || echarts.init(el)
  c.setOption(option, true)
  c.resize()
  if (!charts.includes(c)) {
    charts.push(c)
    chartResize.observe(el)
  }
}

/* 图表色板不借用品牌红（红在这套系统里只表示动作/错误），用语义色 + 中性灰 */
const ONGOING = '#1d63c8'
const SUCCESS = '#1f7a4d'
const WARNING = '#b3541e'
/** 与 main.css 的 --color-* 同值：ECharts 画在 canvas 上，吃不到 CSS 变量 */
const TONE_COLOR = { ongoing: ONGOING, success: SUCCESS, warning: WARNING, danger: '#c13515', terminated: '#6a6a6a' }
const NEUTRAL = '#929292'

async function draw(v, current) {
  let echarts
  try {
    echarts = await loadEcharts()
  } catch {
    return
  }
  if (current !== requestId) return
  echartsLib = echarts

  const t = v.trend || []
  mount(echarts, elTrend.value, {
    tooltip: { trigger: 'axis' },
    legend: { data: ['预约量', '营收'], left: 'center', top: 0 },
    grid: { left: 42, right: 60, top: 58, bottom: 26 },
    xAxis: { type: 'category', data: t.map((x) => String(x.day).slice(5)), boundaryGap: true },
    yAxis: [
      { type: 'value', name: '预约量', minInterval: 1, splitLine: { lineStyle: { type: 'dashed' } } },
      { type: 'value', name: '营收（元）', axisLabel: { formatter: '{value}' }, splitLine: { show: false } }
    ],
    series: [
      { name: '预约量', type: 'bar', data: t.map((x) => x.cnt), barMaxWidth: 22, itemStyle: { color: ONGOING, borderRadius: [4, 4, 0, 0] } },
      { name: '营收', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 5, data: t.map((x) => Number(x.income || 0)), itemStyle: { color: SUCCESS } }
    ]
  })

  mount(echarts, elStatus.value, {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, type: 'scroll' },
    series: [{
      type: 'pie', radius: ['46%', '68%'], center: ['50%', '44%'], avoidLabelOverlap: true,
      label: { formatter: '{b}\n{c}' },
      // 色随字典的 tag 走：statusDist 是 GROUP BY status 的结果，某状态没有数据就不会出现，
      // 用位置色板会整体错位一格
      data: (v.statusDist || []).map((x) => ({
        name: text(APPT_STATUS, x.status),
        value: x.cnt,
        itemStyle: { color: TONE_COLOR[tagType(APPT_STATUS, x.status)] || NEUTRAL }
      }))
    }]
  })

  const dd = v.deptDist || []
  mount(echarts, elDept.value, {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 76, right: 24, top: 16, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { type: 'dashed' } } },
    yAxis: { type: 'category', data: dd.map((x) => x.name).reverse() },
    series: [{ type: 'bar', data: dd.map((x) => x.cnt).reverse(), barMaxWidth: 16, itemStyle: { color: ONGOING, borderRadius: [0, 4, 4, 0] } }]
  })

  const td = v.topDoctors || []
  mount(echarts, elTop.value, {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 76, right: 24, top: 16, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { type: 'dashed' } } },
    yAxis: { type: 'category', data: td.map((x) => x.name).reverse() },
    series: [{ type: 'bar', data: td.map((x) => x.cnt).reverse(), barMaxWidth: 16, itemStyle: { color: WARNING, borderRadius: [0, 4, 4, 0] } }]
  })
}

async function load() {
  const current = ++requestId
  loading.value = true
  failed.value = false
  try {
    const result = await dashboard(days.value)
    if (current !== requestId) return
    vo.value = result
  } catch {
    if (current === requestId) {
      disposeCharts()
      failed.value = true
    }
    return
  } finally {
    if (current === requestId) loading.value = false
  }
  // 图表容器在 v-if="vo" 里，不等这一帧 ref 还是 null
  await nextTick()
  if (current !== requestId) return
  await draw(vo.value, current)
}

async function loadUnread() {
  const current = ++unreadRequestId
  unreadLoading.value = true
  unreadFailed.value = false
  try {
    const count = await consultUnread()
    if (current === unreadRequestId) unreadCount.value = count
  } catch {
    if (current === unreadRequestId) {
      unreadCount.value = null
      unreadFailed.value = true
    }
  } finally {
    if (current === unreadRequestId) unreadLoading.value = false
  }
}

onMounted(() => {
  load()
  if (store.isDoctor) loadUnread()
})
onBeforeUnmount(() => {
  requestId++
  unreadRequestId++
  disposeCharts()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <template v-else-if="vo">
      <div class="bar"><h2>{{ store.isDoctor ? '今日接诊' : '今日概览' }}</h2><span class="pc-muted">{{ today() }}</span></div>
      <div v-if="store.isDoctor" class="kpis doctor-tasks">
        <button class="kpi" @click="router.push({ path: '/admin/appointments', query: { day: today(), status: 0 } })">
          <span class="lb">今日待接诊</span><b>{{ vo.todayPending }}</b>
          <i class="pc-muted">含待支付预约 · 查看预约 →</i>
        </button>
        <button class="kpi" @click="router.push({ path: '/admin/appointments', query: { day: today(), status: 1 } })">
          <span class="lb">今日接诊中</span><b>{{ vo.todayAccepted }}</b>
          <i class="pc-muted">继续接诊 →</i>
        </button>
        <div class="kpi">
          <span class="lb">未读问诊</span>
          <b :class="{ unknown: unreadLoading || unreadFailed }" aria-live="polite">
            {{ unreadLoading ? '正在获取…' : unreadFailed ? '暂未获取' : unreadCount }}
          </b>
          <i v-if="!unreadLoading && !unreadFailed" class="pc-muted">{{ unreadCount ? '个问诊有新消息' : '暂无新消息' }}</i>
          <div class="task-actions">
            <el-button link type="primary" @click="router.push('/admin/consult')">查看问诊 →</el-button>
            <el-button v-if="unreadFailed" link @click="loadUnread">重试</el-button>
          </div>
        </div>
      </div>
      <div v-else class="kpis">
        <button class="kpi" @click="router.push({ path: '/admin/appointments', query: { day: today() } })">
          <span class="lb">今日预约</span><b>{{ vo.todayOrders }}</b>
          <i class="pc-muted">{{ text(APPT_STATUS, 0) }} {{ vo.todayPending }}   {{ text(APPT_STATUS, 1) }} {{ vo.todayAccepted }}   {{ text(APPT_STATUS, 2) }} {{ vo.todayFinished }}</i>
        </button>
        <div class="kpi">
          <span class="lb">今日营收</span><b class="money">{{ money(vo.todayIncome) }}</b>
          <i class="pc-muted">模拟支付口径</i>
        </div>
        <div class="kpi">
          <span class="lb">医生在线</span><b>{{ vo.onlineDoctors }} / {{ vo.totalDoctors }}</b>
        </div>
      </div>

      <section class="pc-card pending">
        <h2>待关注事项</h2>
        <div class="pending-item"><span>待回复评价</span><b>{{ vo.reviewPendingReply }}</b><el-button @click="router.push('/admin/reviews')">查看评价</el-button></div>
        <div class="pending-item"><span>待支付预约</span><b>{{ vo.unpaidTimeoutRisk }}</b><el-button @click="router.push({ path: '/admin/appointments', query: { status: 0, payStatus: 0 } })">查看预约</el-button></div>
      </section>
      <p class="archive-summary pc-muted">{{ store.isDoctor ? '已服务宠物主人' : '宠物主人' }} {{ vo.totalUsers }} 位 · 宠物档案 {{ vo.totalPets }} 份</p>
      <div class="bar"><h2>业务统计</h2><el-radio-group v-model="days" size="small" @change="load"><el-radio-button :value="7">近 7 天</el-radio-button><el-radio-button :value="14">近 14 天</el-radio-button><el-radio-button :value="30">近 30 天</el-radio-button></el-radio-group></div>
      <div v-if="store.isDoctor" class="doctor-summary pc-card">
        <el-button link @click="router.push({ path: '/admin/appointments', query: { day: today() } })">今日预约 {{ vo.todayOrders }} 单 →</el-button>
        <span>已完成 {{ vo.todayFinished }} 单</span>
        <span>今日营收 {{ money(vo.todayIncome) }}（模拟支付）</span>
        <span>医生在线 {{ vo.onlineDoctors }} / {{ vo.totalDoctors }}</span>
      </div>
      <div class="grid">
        <section class="pc-card chart trend">
          <h3>预约量与营收趋势</h3>
          <div v-show="vo.trend?.length" ref="elTrend" class="cv" /><el-empty v-if="!vo.trend?.length" description="该时段暂无统计数据" />
        </section>
        <section class="pc-card chart">
          <h3>预约状态分布</h3>
          <div v-show="vo.statusDist?.length" ref="elStatus" class="cv" /><el-empty v-if="!vo.statusDist?.length" description="该时段暂无统计数据" />
        </section>
        <section class="pc-card chart">
          <h3>科室预约量</h3>
          <div v-show="vo.deptDist?.length" ref="elDept" class="cv" /><el-empty v-if="!vo.deptDist?.length" description="该时段暂无统计数据" />
        </section>
        <section class="pc-card chart">
          <h3>医生接诊量排行</h3>
          <div v-show="vo.topDoctors?.length" ref="elTop" class="cv" /><el-empty v-if="!vo.topDoctors?.length" description="该时段暂无统计数据" />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { gap: 14px; }
.bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.kpis { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.kpi {
  background: var(--color-canvas); border: 1px solid var(--color-hairline); border-radius: var(--radius-md);
  padding: 22px; text-align: left; display: flex; flex-direction: column; gap: 10px;
  font-family: inherit; cursor: default;
}
button.kpi { cursor: pointer; }
button.kpi:hover { border-color: var(--color-primary); }
.kpi .lb { font-size: 13px; color: var(--color-muted); }
.kpi b { font-size: 30px; line-height: 1.2; color: var(--color-ink); font-variant-numeric: tabular-nums; }
/* 金额用 ink：warning 橙在别处表示"待处理"，借给营收会读成告警（design.md 色彩约定） */
.kpi .money { color: var(--color-ink); }
.kpi i { font-style: normal; font-size: 12px; }
.kpi .unknown { font-size: 18px; color: var(--color-muted); }
.doctor-tasks .kpi { min-width: 0; }
.task-actions { display: flex; align-items: center; gap: 12px; margin-top: auto; }
.task-actions .el-button + .el-button { margin-left: 0; }
.doctor-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 24px; font-size: 13px; color: var(--color-muted); }
.grid { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); gap: 12px; }
.chart h3 { margin: 0 0 8px; font-size: 14px; }
.cv { height: 260px; }
h2 { margin: 0; font-size: 16px; font-weight: 600; }
.pending { display: flex; align-items: center; flex-wrap: wrap; gap: 24px; }
.pending-item { display: flex; align-items: center; gap: 16px; font-size: 14px; }
.pending-item b { font-size: 24px; font-variant-numeric: tabular-nums; }
.chart { min-width: 0; }
@media (max-width: 900px) { .grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 650px) { .kpis { grid-template-columns: minmax(0, 1fr); } .pending-item { flex-wrap: wrap; } }
.archive-summary { margin: 0 0 4px; font-size: 13px; }
</style>
