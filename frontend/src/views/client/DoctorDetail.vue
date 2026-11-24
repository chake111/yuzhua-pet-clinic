<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { doctorDetail, doctorReviews, doctorSchedules } from '@/api/user'
import { useUserStore } from '@/stores/client'
import { PERIOD, text } from '@/utils/dict'
import { dayLabel, money, slotEnded, toDate } from '@/utils/format'
import { groupSlotsByDate, localNow } from '@/utils/schedule'
import ImageGallery from '@/components/ImageGallery.vue'
import StatusChip from '@/components/StatusChip.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const id = Number(route.params.id)

const doctor = ref(null)
const failed = ref(false)
const loading = ref(true)
const reviewsFailed = ref(false)
const slots = ref([])
const reviews = ref([])
const reviewTotal = ref(0)
const reviewPage = ref(1)
const loadingSlots = ref(false)
const days = 7

const grouped = computed(() => groupSlotsByDate(slots.value))
const specialty = computed(() =>
  (doctor.value?.specialty || '').split(',').map((s) => s.trim()).filter(Boolean)
)

function dayRemain(g) {
  return g.list.reduce((n, s) => n + (bookable(s) ? s.remainNum : 0), 0)
}

const selectedDate = ref('')
const currentDay = computed(() => grouped.value.find((g) => g.date === selectedDate.value) || null)

function firstBookable(list) {
  return (list || []).find(bookable) || null
}
const nextSlot = computed(() => firstBookable(currentDay.value?.list))

// 原因: nowTick 每分钟刷新一次以驱动 bookable 自动禁用跨过结束时间的时段
const nowTick = ref('')
let nowTimer = null
const clockNow = () => {
  const n = localNow()
  nowTick.value = `${n.date} ${n.hhmm}`
}

function bookable(s) {
  if (slotEnded(s, nowTick.value.slice(0, 10), nowTick.value.slice(11, 16))) return false
  return s.doctorAvailable !== false && s.status === 1 && s.remainNum > 0
}

function dayHasBookable(g) {
  return g.list.some(bookable)
}

function slotState(s) {
  if (s.doctorAvailable === false) return '医生停诊'
  if (s.status !== 1) return '已停诊'
  if (slotEnded(s, nowTick.value.slice(0, 10), nowTick.value.slice(11, 16))) return '已结束'
  if (s.remainNum <= 0) return '约满'
  return `余 ${s.remainNum} / ${s.totalNum}`
}

function pickDate() {
  if (grouped.value.some((g) => g.date === selectedDate.value)) return
  const first = grouped.value.find(dayHasBookable) || grouped.value[0]
  selectedDate.value = first?.date || ''
}

function goBook(s) {
  if (!bookable(s)) return
  if (!store.logged) {
    ElMessage.info('请先登录后挂号')
    return router.push({ path: '/login', query: { redirect: `/book/${id}?scheduleId=${s.id}` } })
  }
  router.push(`/book/${id}?scheduleId=${s.id}`)
}

function goBookNext() {
  if (nextSlot.value) return goBook(nextSlot.value)
  router.push(`/book/${id}`)
}

function goConsult() {
  if (!store.logged) {
    ElMessage.info('请先登录后发起图文问诊')
    return router.push({ path: '/login', query: { redirect: `/consult/new/${id}` } })
  }
  router.push(`/consult/new/${id}`)
}

async function loadReviews() {
  reviewsFailed.value = false
  try {
    const page = await doctorReviews(id, { pageNum: reviewPage.value, pageSize: 5 })
    reviews.value = page.records
    reviewTotal.value = page.total
  } catch {
    reviewsFailed.value = true
  }
}

onMounted(() => {
  clockNow()
  nowTimer = setInterval(clockNow, 60000)
})

onBeforeUnmount(() => {
  if (nowTimer) clearInterval(nowTimer)
})

async function load() {
  loading.value = true
  loadingSlots.value = true
  failed.value = false
  try {
    const [d, s] = await Promise.all([doctorDetail(id), doctorSchedules(id, { days })])
    doctor.value = d
    slots.value = s || []
    pickDate()
    if (d) await loadReviews()
  } catch {
    failed.value = true
  } finally {
    loading.value = false
    loadingSlots.value = false
  }
}
onMounted(load)

</script>

<template>
  <div class="pc-narrow has-cta doctor-detail" v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <template v-else-if="doctor">
      <section class="pc-card profile">
        <div class="profile-main">
          <el-avatar :size="76" :src="doctor.avatar" class="ava" alt="">{{ doctor.name?.[0] }}</el-avatar>
          <div class="who">
            <h1>
              {{ doctor.name }}
              <i v-if="doctor.onlineStatus === 1" class="dot">在线</i>
            </h1>
            <p class="cred">
              <span>{{ doctor.title }}</span>
              <span>{{ doctor.departmentName }}</span>
              <span>从业 {{ doctor.workYears || 0 }} 年</span>
            </p>
          </div>
        </div>
        <p class="rating">
          <span class="rate-group">
            <el-rate :model-value="Number(doctor.rating) || 0" disabled size="small" allow-half />
            <b>{{ Number(doctor.rating).toFixed(1) }} 分</b>
          </span>
          <span class="pc-muted">{{ doctor.reviewCount || 0 }} 条评价</span>
          <span class="pc-muted">累计接诊 {{ doctor.appointmentCount || 0 }} 次</span>
        </p>
        <div v-if="specialty.length" class="specialty">
          <span class="lab">擅长</span>
          <StatusChip v-for="t in specialty" :key="t" tone="plain">{{ t }}</StatusChip>
        </div>
      </section>

      <div class="sec">
        <h2>近期号源</h2>
        <span class="pc-muted">未来 {{ days }} 天</span>
      </div>
      <div v-loading="loadingSlots" class="sched pc-card">
        <template v-if="grouped.length">
          <div class="date-strip pc-date-strip" role="group" aria-label="选择就诊日期">
            <button v-for="g in grouped" :key="g.date" type="button" class="date-option pc-date-option"
                    :class="{ on: selectedDate === g.date }" :aria-pressed="selectedDate === g.date"
                    @click="selectedDate = g.date">
              <b>{{ dayLabel(g.date).split(' ').at(-1) }}</b>
              <span>{{ toDate(g.date).slice(5).replace('-', '/') }}</span>
              <span class="date-state">{{ dayHasBookable(g) ? `余 ${dayRemain(g)}` : '暂无号源' }}</span>
            </button>
          </div>
          <Transition name="day-switch" mode="out-in">
            <div v-if="currentDay" :key="selectedDate" class="slots" role="group"
                 :aria-label="`${toDate(selectedDate)} 就诊时段`">
              <button v-for="s in currentDay.list" :key="s.id" type="button" class="slot"
                      :class="{ off: !bookable(s), next: nextSlot?.id === s.id }" :disabled="!bookable(s)"
                      :aria-label="`${text(PERIOD, s.period)} ${s.startTime?.slice(0, 5)} 至 ${s.endTime?.slice(0, 5)}，${slotState(s)}`"
                      @click="goBook(s)">
                <span class="pd">{{ text(PERIOD, s.period) }}</span>
                <span class="tm">{{ s.startTime?.slice(0, 5) }}–{{ s.endTime?.slice(0, 5) }}</span>
                <span class="rm">{{ slotState(s) }}</span>
              </button>
            </div>
          </Transition>
        </template>
        <el-empty v-else-if="!loadingSlots" description="近期暂无可预约时段" />
      </div>

      <div class="sec">
        <h2>医生简介</h2>
      </div>
      <p class="intro">{{ doctor.introduction || '这位医生还没有填写简介。' }}</p>

      <div class="sec">
        <h2>就诊评价</h2>
        <span class="pc-muted">{{ Number(doctor.rating).toFixed(1) }} · {{ reviewTotal }} 条</span>
      </div>
      <div class="revs">
        <article v-for="r in reviews" :key="r.id" class="rev">
          <div class="rev-h">
            <el-avatar :size="32" :src="r.userAvatar" alt="">{{ r.userNickname?.[0] }}</el-avatar>
            <div class="rev-who">
              <div class="rev-name">
                <b>{{ r.userNickname }}</b>
                <el-rate :model-value="r.rating" disabled size="small" class="stars" />
              </div>
              <span class="when">{{ toDate(r.createTime) }}</span>
            </div>
          </div>
          <p v-if="r.attitudeScore || r.effectScore" class="rev-meta">
            <template v-if="r.attitudeScore">服务态度 <b>{{ Number(r.attitudeScore).toFixed(1) }}</b></template>
            <template v-if="r.attitudeScore && r.effectScore"> · </template>
            <template v-if="r.effectScore">诊疗效果 <b>{{ Number(r.effectScore).toFixed(1) }}</b></template>
          </p>
          <p class="body">{{ r.content || '该用户没有填写评价内容' }}</p>
          <ImageGallery class="pics" :urls="r.images" :size="64" label="就诊评价附图" />
          <p v-if="r.reply" class="reply"><b>医生回复</b>{{ r.reply }}</p>
        </article>
        <LoadFailure v-if="reviewsFailed" @retry="loadReviews" />
        <div v-if="!reviewsFailed && !reviews.length" class="pc-empty">还没有评价</div>
        <el-pagination v-if="reviewTotal > 5" v-model:current-page="reviewPage" :page-size="5"
                       :total="reviewTotal" layout="prev, pager, next" background small
                       class="pager" @current-change="loadReviews" />
      </div>

      <div class="cta-bar pc-cta-bar">
        <p class="fee">
          <span>到院问诊费</span>
          <b>{{ money(doctor.consultationFee) }}</b>
        </p>
        <el-button size="large" class="consult" @click="goConsult">图文问诊 免费</el-button>
        <el-button size="large" type="primary" class="go" @click="goBookNext">立即挂号</el-button>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无医生信息" />
  </div>
</template>

<style scoped>
.doctor-detail { padding: 20px var(--page-margin) calc(var(--cta-bar-height) + 24px); }

/* —— 医生身份：头像、姓名和评分沿同一条左对齐基线，比居中的模板式资料卡好扫 —— */
.profile { padding: 20px; }
.profile-main { display: flex; align-items: center; gap: 16px; }
.profile-main .ava { flex-shrink: 0; font-size: 28px; }
.who { flex: 1; min-width: 0; }
.who h1 {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
  margin: 0; font-size: 22px; font-weight: 700; line-height: 1.3; overflow-wrap: anywhere;
}
.dot {
  font-style: normal; font-size: 13px; font-weight: 500; line-height: 1.4;
  padding: 2px 8px; border-radius: var(--radius-full);
  background: var(--color-success-soft); color: var(--color-success);
}
/* 靠间距分开三项资质：句点分隔在窄屏换行时会落到行首 */
.cred {
  display: flex; flex-wrap: wrap; gap: 2px 14px;
  margin: 6px 0 0; color: var(--color-muted); font-size: var(--font-label); line-height: 1.6;
}
.cred span { overflow-wrap: anywhere; }

.rating { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; margin: 10px 0 0; font-size: var(--font-label); }
.rating .pc-muted { font-size: var(--font-meta); }
.rate-group { display: inline-flex; align-items: center; gap: 6px; }
.rating b { font-size: 15px; font-weight: 600; }
.rating :deep(.el-rate) { height: auto; }

.specialty {
  display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
  margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--color-hairline-soft);
}
.specialty .lab { font-size: var(--font-label); color: var(--color-muted); }

/* —— 区块标题：与首页的 A 方向一致，19px 靠字号分层，不再加装饰条 —— */
.sec {
  display: flex; align-items: baseline; justify-content: space-between;
  gap: 8px 12px; flex-wrap: wrap; margin: 24px 0 12px;
}
.sec h2 { margin: 0; font-size: 19px; font-weight: 700; line-height: 1.4; }
.sec .pc-muted { font-weight: 400; }
.intro {
  margin: 0; color: var(--color-body); font-size: var(--font-reading); line-height: var(--line-reading);
  white-space: pre-wrap; overflow-wrap: anywhere; text-wrap: pretty;
}

/* —— 号源：日期条与挂号页同一语法，避免把七天铺成七段。
   外壳与选中态走公共 .pc-date-strip / .pc-date-option，这里只留本页的间距 —— */
.sched { padding: 12px 16px 14px; }
.date-strip {
  overflow-x: auto; padding: 2px 2px 8px; margin: 0 -2px 8px;
}
.date-option {
  flex: 0 0 78px;
  padding: 8px 6px;
}

.slots { display: grid; grid-template-columns: minmax(0, 1fr); gap: 6px; }
.slot {
  display: grid; grid-template-columns: 44px minmax(0, 1fr) auto; align-items: center; gap: 10px;
  min-height: 52px; padding: 8px 12px; text-align: left; font: inherit;
  border: 1px solid var(--color-hairline); border-radius: var(--radius-sm);
  background: var(--color-canvas); cursor: pointer;
  transition: border-color .18s var(--ease), background-color .18s var(--ease);
}
.slot .pd { font-size: var(--font-label); font-weight: 600; }
.slot .tm { font-size: var(--font-meta); color: var(--color-muted); font-variant-numeric: tabular-nums; }
.slot .rm { font-size: var(--font-meta); color: var(--color-body); white-space: nowrap; }
/* 不可约的时段不用虚线自创编码：实线 + 浅底 + 状态文字，和挂号页一致 */
.slot.off { cursor: not-allowed; background: var(--color-surface-soft); }
.slot.off .pd, .slot.off .tm, .slot.off .rm { color: var(--color-muted); }
/* 最近可约的一条描 ink：底部「立即挂号」点下去带的就是这个号 */
.slot.next { border-color: var(--color-ink); box-shadow: inset 0 0 0 1px var(--color-ink); }
.slot.next .rm { color: var(--color-ink); font-weight: 600; }
@media (hover: hover) and (pointer: fine) {
  .slot:not(.off):hover { border-color: var(--color-border-strong); background: var(--color-surface-soft); }
}
.day-switch-enter-active, .day-switch-leave-active { transition: opacity .12s var(--ease), transform .12s var(--ease); }
.day-switch-enter-from, .day-switch-leave-to { opacity: 0; transform: translateY(3px); }

/* —— 评价：直接排在画布上，靠分隔线分组，不再套一层大卡片 —— */
.revs { display: flex; flex-direction: column; }
.rev { padding: 16px 0; border-bottom: 1px solid var(--color-hairline-soft); }
.rev:first-child { padding-top: 4px; }
.rev:last-of-type { border-bottom: none; }
.rev-h { display: flex; align-items: center; gap: 10px; }
.rev-h .el-avatar { flex-shrink: 0; }
.rev-who { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.rev-name { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 8px; min-width: 0; }
.rev-name b { font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.when { font-size: var(--font-meta); color: var(--color-muted); }
.stars { flex-shrink: 0; }
.rev-meta { margin: 8px 0 0; font-size: var(--font-meta); color: var(--color-muted); }
.rev-meta b { color: var(--color-ink); font-weight: 600; }
.body { margin: 8px 0 0; font-size: var(--font-reading); line-height: var(--line-reading); color: var(--color-body); overflow-wrap: anywhere; }
.pics { margin-top: 8px; }
.reply {
  margin: 10px 0 0; padding: 10px 12px; border-radius: var(--radius-sm);
  background: var(--color-canvas); border: 1px solid var(--color-hairline-soft);
  color: var(--color-body);
  font-size: var(--font-label); line-height: 1.65; overflow-wrap: anywhere;
}
.reply b { margin-right: 6px; font-weight: 600; color: var(--color-ink); }
.pager { justify-content: center; margin-top: 12px; }

/* sticky CTA 条要坐在 tab bar 之上，不是屏幕底部；
   照抄 Airbnb 的自贴底会把「立即挂号」压在 tab bar 后面，全面屏还会被 home indicator 再吃掉一截。
   位置、宽度上限与白底投影走公共 .pc-cta-bar，这里只留高度与内部排布。 */
.cta-bar {
  height: var(--cta-bar-height);
  /* 和正文的 20px 页边距共线：按钮右缘与卡片右缘对齐 */
  display: flex; align-items: center; gap: 10px; padding: 0 20px;
}
.fee { display: flex; flex-direction: column; line-height: 1.25; margin: 0 auto 0 0; min-width: 0; }
.fee span { font-size: var(--font-meta); color: var(--color-muted); white-space: nowrap; }
.fee b { font-size: 18px; font-weight: 700; color: var(--color-ink); white-space: nowrap; font-variant-numeric: tabular-nums; }
.cta-bar .el-button { margin: 0; }
/* 次按钮回到中性描边：Element 默认的 #dcdfe6 偏蓝，和卡片的纯灰 hairline 不是一套 */
.cta-bar .consult {
  flex: 0 0 auto;
  --el-button-border-color: var(--color-hairline);
  --el-button-hover-border-color: var(--color-border-strong);
  --el-button-text-color: var(--color-ink);
  --el-button-hover-text-color: var(--color-ink);
  --el-button-hover-bg-color: var(--color-surface-soft);
  padding-inline: 12px;
}
.cta-bar .go { flex: 1 1 0; min-width: 0; }

/* 320px 一档：操作条压回单行，资料卡与日期条收紧，
   让首屏能完整看到第一条时段 */
@media (max-width: 359px) {
  .doctor-detail { padding: 16px 16px calc(var(--cta-bar-height) + 24px); --cta-bar-height: 72px; }
  .profile { padding: 12px 14px; }
  .profile-main { gap: 12px; }
  .profile-main .ava { --el-avatar-size: 56px; }
  .who h1 { font-size: 20px; }
  .rating { margin-top: 8px; }
  .specialty { margin-top: 10px; padding-top: 10px; gap: 6px; }
  .specialty :deep(.chip) { padding-inline: 7px; }
  .sec { margin: 20px 0 10px; }
  .sched { padding: 10px 12px 12px; }
  .date-strip { margin-bottom: 6px; padding-bottom: 6px; }
  .date-option { padding: 5px 6px; }
  .cta-bar { flex-wrap: nowrap; gap: 6px; height: var(--cta-bar-height); padding: 8px 16px; }
  .fee { flex-shrink: 0; }
  .fee span { font-size: 12px; }
  .cta-bar .consult, .cta-bar .go { flex: 1 1 0; padding-inline: 8px; }
}

/* 200% 放大阅读（390px 设备上约 195px 可用宽）：头像与姓名改上下排，
   操作条改单列网格，否则按钮并排会把文字压扁、姓名一字一行 */
@media (max-width: 279px) {
  .profile-main { flex-wrap: wrap; }
  .who { flex-basis: 100%; }
  .rating { flex-direction: column; align-items: flex-start; gap: 4px; }
  .rate-group { flex-wrap: wrap; }
  .doctor-detail { --cta-bar-height: 148px; }
  .cta-bar {
    display: grid; grid-template-columns: minmax(0, 1fr);
    align-content: center; gap: 8px;
  }
  .fee { flex-direction: row; align-items: baseline; gap: 6px; width: 100%; margin: 0; }
  .cta-bar .consult, .cta-bar .go { width: 100%; min-width: 0; }
}

@media (min-width: 560px) {
  .date-strip { overflow-x: visible; }
  .date-option { flex: 1 1 0; min-width: 0; }
  .slots { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .slot { display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 4px; min-height: 84px; padding: 12px 14px; }
}

/* 宽屏资料卡改成左右两栏：身份在左，评分与擅长在右，别让右半边空着 */
@media (min-width: 640px) {
  .profile {
    display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    column-gap: 32px; align-items: start; padding: 24px;
  }
  .profile-main { grid-column: 1; grid-row: 1; }
  .rating { grid-column: 1; grid-row: 2; }
  .specialty {
    grid-column: 2; grid-row: 1 / span 2;
    align-content: flex-start;
    margin: 0; padding: 0 0 0 32px; border-top: 0; border-left: 1px solid var(--color-hairline-soft);
  }
}

@media (prefers-reduced-motion: reduce) {
  .date-option, .slot, .day-switch-enter-active, .day-switch-leave-active { transition: none; }
}
</style>
