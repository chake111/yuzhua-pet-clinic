<script setup>
import { loadWithSavedScroll } from '@/utils/listScroll'
import LoadFailure from '@/components/LoadFailure.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ElMessageBox } from '@/utils/messageBox'
import { appointmentPage, cancelAppointment, payAppointment } from '@/api/user'
import { APPT_STATUS, CONSULT_TYPE, PAY_STATUS, PERIOD, tagType, text } from '@/utils/dict'
import { canPay, money, toDate, toDateTime } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

const router = useRouter()
const route = useRoute()
const tab = ref(['pending', 'accepted', 'finished', 'closed'].includes(route.query.tab) ? route.query.tab : 'all')
const list = ref([])
const total = ref(0)
const pageNum = ref(Math.max(1, parseInt(route.query.page, 10) || 1))
const pageSize = 10
const failed = ref(false)
const loading = ref(false)
const payingId = ref(null)
const now = ref(Date.now())
let timer = null
let expiryRefresh = 0

const TAB_STATUSES = [
  { key: 'pending', status: 0 },
  { key: 'accepted', status: 1 },
  { key: 'finished', status: 2 },
  { key: 'closed', status: 3 }
]
const tabs = computed(() => [
  { key: 'all', label: '全部', status: null },
  ...TAB_STATUSES.map((t) => ({ ...t, label: text(APPT_STATUS, t.status) }))
])

function left(row) {
  if (!row.payDeadline) return 0
  return Math.max(0, Math.floor((new Date(String(row.payDeadline).replace(/-/g, '/')) - now.value) / 1000))
}

const needPay = (row) => row.status === 0 && row.payStatus === 0

async function load() {
  loading.value = true
  failed.value = false
  try {
    await router.replace({ query: { tab: tab.value === 'all' ? undefined : tab.value, page: pageNum.value > 1 ? pageNum.value : undefined } })
    const s = tabs.value.find((t) => t.key === tab.value)?.status
    const page = await appointmentPage({ pageNum: pageNum.value, pageSize, status: s ?? undefined })
    list.value = page.records
    total.value = page.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function changeTab() {
  pageNum.value = 1
  load()
}

async function pay(row) {
  if (!canPay(row, now.value)) return
  if (payingId.value !== null) return
  payingId.value = row.id
  try {
    await payAppointment(row.id)
    ElMessage.success('支付成功')
    await load()
  } catch {
    // 原因: 请求层已提示失败原因，此处保留当前列表供重试
  } finally {
    payingId.value = null
  }
}

async function cancel(row) {
  const { value } = await ElMessageBox.prompt('取消原因（选填）', '取消预约', {
    inputPlaceholder: '取消原因（选填）',
    inputValue: '',
    confirmButtonText: '确认取消',
    cancelButtonText: '再想想',
    inputValidator: (v) => (v || '').length > 200 ? '取消原因不超过 200 字' : true
  }).catch(() => ({}))
  if (value === undefined) return
  await cancelAppointment(row.id, { reason: value || undefined })
  ElMessage.success('订单已取消')
  load()
}

onMounted(() => {
  loadWithSavedScroll(load)
  timer = setInterval(() => {
    now.value = Date.now()
    if (!loading.value && now.value - expiryRefresh >= 60000 && list.value.some(o => needPay(o) && !canPay(o, now.value))) {
      expiryRefresh = now.value
      load()
    }
  }, 1000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="wrap">
    <LoadFailure v-if="failed" @retry="load" />
    <h1 class="pc-title">我的预约</h1>
    <el-tabs v-model="tab" class="tabs" stretch @tab-change="changeTab">
      <el-tab-pane v-for="t in tabs" :key="t.key" :label="t.label" :name="t.key" />
    </el-tabs>

    <div v-loading="loading" class="list">
      <article v-for="o in list" :key="o.id" class="pc-card order" @click="router.push(`/order/${o.id}`)">
        <header>
          <router-link :to="`/order/${o.id}`" @click.stop><b>{{ o.doctorName }}</b></router-link>
          <span class="pc-muted">{{ o.departmentName }} {{ text(PERIOD, o.period) }}</span>
          <StatusChip class="st" :tone="tagType(APPT_STATUS, o.status)">{{ text(APPT_STATUS, o.status) }}</StatusChip>
        </header>

        <div class="grid">
          <div><i>就诊时间</i>{{ toDate(o.appointmentDate) }} {{ o.startTime?.slice(0, 5) }}</div>
          <div><i>就诊宠物</i>{{ o.petName }}（{{ o.petSpecies }}）</div>
          <div><i>问诊方式</i>{{ text(CONSULT_TYPE, o.consultType) }}</div>
          <div><i>排队号</i>{{ o.queueNo ? o.queueNo + ' 号' : '—' }}</div>
        </div>

        <p class="sym">{{ o.symptom }}</p>

        <footer @click.stop>
          <div class="money">
            <b>{{ money(o.amount) }}</b>
            <StatusChip :tone="tagType(PAY_STATUS, o.payStatus)">{{ text(PAY_STATUS, o.payStatus) }}</StatusChip>
            <span v-if="needPay(o) && left(o)" class="cd">剩 {{ Math.floor(left(o) / 60) }}:{{ String(left(o) % 60).padStart(2, '0') }} 关闭</span>
          </div>
          <div class="ops">
            <el-button size="small" @click="router.push(`/order/${o.id}`)">详情</el-button>
            <template v-if="needPay(o)">
              <el-button size="small" @click="cancel(o)">取消</el-button>
              <el-button size="small" type="primary" :loading="payingId === o.id" :disabled="!canPay(o, now) || (payingId !== null && payingId !== o.id)" @click="pay(o)">{{ canPay(o, now) ? '支付' : '支付已超时' }}</el-button>
            </template>
            <el-button v-if="o.sessionId && o.status !== 3" size="small" @click="router.push(`/consult/${o.sessionId}`)">去问诊</el-button>
            <el-button v-if="o.recordId" size="small" @click="router.push(`/record/${o.recordId}`)">病历</el-button>
            <el-button v-if="o.status === 2 && o.isReviewed !== 1" size="small" @click="router.push(`/review/${o.id}`)">评价</el-button>
            <StatusChip v-if="o.isReviewed === 1" tone="plain">已评价</StatusChip>
          </div>
        </footer>
        <p class="pc-muted ct">下单于 {{ toDateTime(o.createTime) }}</p>
      </article>

      <el-empty v-if="!failed && !loading && !list.length" description="还没有预约记录">
        <el-button type="primary" @click="router.push('/doctors')">去找医生</el-button>
      </el-empty>
    </div>

    <el-pagination v-if="total > pageSize" v-model:current-page="pageNum" :page-size="pageSize" :total="total"
                   layout="prev, pager, next" background small class="pager" @current-change="load" />
  </div>
</template>

<style scoped>
.tabs :deep(.el-tabs__nav-wrap) { padding: 0; }
.tabs :deep(.el-tabs__item) { padding: 0 4px; min-width: 0; font-size: var(--font-label); }
.list { display: grid; gap: 12px; min-height: 180px; }
.order { cursor: pointer; }
@media (hover: hover) and (pointer: fine) {
  .order:hover { border-color: var(--color-border-strong); }
}
header { display: flex; align-items: center; gap: 8px; }
header b { font-size: 16px; font-weight: 600; color: var(--color-ink); }
header .st { margin-left: auto; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; margin: 12px 0; font-size: 13px; }
.grid i { font-style: normal; color: var(--color-muted); margin-right: 4px; }
.sym {
  margin: 0; font-size: var(--font-reading); color: var(--color-body); background: var(--color-surface-soft);
  border-radius: var(--radius-sm); padding: 8px 10px; line-height: var(--line-reading);
  white-space: pre-wrap; overflow-wrap: anywhere;
}
footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.money { display: flex; align-items: center; gap: 8px; }
.money b { color: var(--color-ink); font-size: 16px; font-weight: 700; }
/* 倒计时是"待办"不是"错误"，用 warning 而不是危险色 */
.cd { font-size: var(--font-label); color: var(--color-warning); }
.ops { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ct { margin: 10px 0 0; font-size: var(--font-meta); }
.pager { justify-content: center; margin-top: 16px; }
header { flex-wrap: wrap; }
header b { overflow-wrap: anywhere; }
.grid { grid-template-columns: repeat(2, minmax(0, 1fr)); font-size: 14px; gap: 10px 16px; }
.grid > div { min-width: 0; overflow-wrap: anywhere; }
.grid i { display: block; font-size: var(--font-label); margin-bottom: 3px; }
.money { flex-wrap: wrap; }
.ops { margin-left: auto; }
.ops .el-button { margin-left: 0; }
.ct { font-size: var(--font-meta); }

</style>
