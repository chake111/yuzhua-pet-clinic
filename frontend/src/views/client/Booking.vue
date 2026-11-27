<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import PaymentTicket from '@/components/PaymentTicket.vue'
import {
  createAppointment, doctorDetail, doctorSchedules, pets
} from '@/api/user'
import { PERIOD, text } from '@/utils/dict'
import { dayLabel, money, slotEnded, toDate } from '@/utils/format'
import { newIdempotencyKey } from '@/utils/idempotencyKey'
import { groupSlotsByDate, localNow } from '@/utils/schedule'
import ImageUpload from '@/components/ImageUpload.vue'
import PetForm from '@/components/PetForm.vue'
import PetPicker, { pickAfterLoad } from '@/components/PetPicker.vue'

const route = useRoute()
const router = useRouter()
const doctorId = Number(route.params.doctorId)
const doctor = ref(null)
const slotList = ref([])
const myPets = ref([])
const failed = ref(false)
const petsFailed = ref(false)
const loading = ref(true)
const busy = ref(false)

const petOpen = ref(false)
const payOpen = ref(false)
const paidOrder = ref(null)
// 原因: 同一次下单意图与网络失败后的原样重试共用同一个幂等键，防止服务端二次占号
const idemKey = ref(newIdempotencyKey())
const selectedDate = ref('')
const errors = reactive({ scheduleId: '', petId: '', symptom: '' })
const petSection = ref(null)
const scheduleSection = ref(null)
const symptomSection = ref(null)
const ctaBar = ref(null)
const ctaHeight = ref(112)
let ctaObserver = null

const form = reactive({
  petId: null,
  scheduleId: route.query.scheduleId ? Number(route.query.scheduleId) : null,
  symptom: '',
  images: ''
})

const grouped = computed(() => groupSlotsByDate(slotList.value))

const picked = computed(() => slotList.value.find((s) => s.id === form.scheduleId) || null)
const pickedPet = computed(() => myPets.value.find((p) => p.id === form.petId) || null)
const currentDay = computed(() => grouped.value.find((g) => g.date === selectedDate.value))
const amount = computed(() => doctor.value?.consultationFee ?? 0)

const slotLabel = computed(() => picked.value
  ? `${toDate(picked.value.workDate).slice(5).replace('-', '/')} ${text(PERIOD, picked.value.period)} ${picked.value.startTime?.slice(0, 5)}–${picked.value.endTime?.slice(0, 5)}`
  : '')

// 原因: nowTick 每分钟刷新一次以驱动 bookable 自动禁用跨过结束时间的时段
const nowTick = ref('')
let nowTimer = null
const clockNow = () => {
  const n = localNow()
  nowTick.value = `${n.date} ${n.hhmm}`
}
const bookable = (s) => {
  if (slotEnded(s, nowTick.value.slice(0, 10), nowTick.value.slice(11, 16))) return false
  return (!doctor.value || doctor.value.status === 1) && s.doctorAvailable !== false && s.status === 1 && s.remainNum > 0
}

const dayRemain = (g) => g.list.filter(bookable).reduce((sum, s) => sum + s.remainNum, 0)

const hasBookable = computed(() => grouped.value.some((g) => g.list.some(bookable)))

const nextHint = computed(() => {
  // 原因: 停诊原因已在医生卡片下方展示，底栏仅显示结果文案避免重复提示
  if (doctor.value?.status !== 1) return '暂不可挂号'
  if (!grouped.value.length) return '该医生近 7 天暂无排班'
  if (!hasBookable.value) return '近期号源已约满，换个日期或医生'
  if (!picked.value) return '请选择就诊时段'
  if (!pickedPet.value) return `${slotLabel.value} · 请选择宠物`
  return `${pickedPet.value.name} · ${slotLabel.value}`
})

function selectDate(date) {
  selectedDate.value = date
  if (picked.value?.workDate !== date) form.scheduleId = null
}

function selectSlot(slot) {
  // 原因: 切换日期的离场动画期间旧时段仍可能接收点击，须校验所属日期匹配
  if (slot.workDate === selectedDate.value && bookable(slot)) form.scheduleId = slot.id
}

watch(() => form.petId, (value) => { if (value) errors.petId = '' })
watch(() => form.scheduleId, (value) => { if (value) errors.scheduleId = '' })
watch(() => form.symptom, (value) => { if (value.trim()) errors.symptom = '' })
// 原因: 更换宠物或时段属于新下单意图须换新键；请求在途时冻结换键，防止超时重试重复建单
watch(() => [form.petId, form.scheduleId], () => {
  if (busy.value) return
  idemKey.value = newIdempotencyKey()
})
watch([selectedDate, scheduleSection], async () => {
  await nextTick()
  const strip = scheduleSection.value?.querySelector('.date-strip')
  const active = strip?.querySelector('.date-option.on')
  if (active) strip.scrollTo({ left: active.offsetLeft, behavior: 'auto' })
})

watch(ctaBar, (element) => {
  ctaObserver?.disconnect()
  if (!element) return
  ctaObserver = new ResizeObserver(() => { ctaHeight.value = Math.ceil(element.getBoundingClientRect().height) })
  ctaObserver.observe(element)
})

async function focusError(field) {
  await nextTick()
  const section = { petId: petSection, scheduleId: scheduleSection, symptom: symptomSection }[field].value
  if (!section) return
  const target = section.querySelector('input, textarea, .slot:not(:disabled)') || section.querySelector('button:not(:disabled)') || section
  target.focus({ preventScroll: true })
  section.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
}

// 原因: 页面停留期间若跨过已选时段结束时间，立即清空选中防带入提交
watch(nowTick, () => {
  if (!form.scheduleId || !picked.value) return
  if (slotEnded(picked.value, nowTick.value.slice(0, 10), nowTick.value.slice(11, 16))) {
    form.scheduleId = null
    errors.scheduleId = '已选时段已结束，请重新选择'
  }
})

async function loadPets(keep) {
  petsFailed.value = false
  try {
    myPets.value = (await pets()) || []
    form.petId = pickAfterLoad(myPets.value, form.petId, keep)
  } catch {
    // 原因: 宠物档案加载失败不影响医生与号源展示，仅将该区块切换为局部重试
    myPets.value = []
    form.petId = null
    petsFailed.value = true
  }
}

async function reloadSlots() {
  slotList.value = (await doctorSchedules(doctorId, { days: 7 })) || []
  if (form.scheduleId && (!picked.value || !bookable(picked.value))) {
    form.scheduleId = null
    errors.scheduleId = '原选时段已不可预约，请重新选择'
  }
  if (picked.value) selectedDate.value = picked.value.workDate
  else if (!grouped.value.some((g) => g.date === selectedDate.value)) {
    selectedDate.value = (grouped.value.find((g) => g.list.some(bookable)) || grouped.value[0])?.date || ''
  }
}

async function submit() {
  if (busy.value || loading.value) return
  if (paidOrder.value) return router.replace(`/order/${paidOrder.value.id}`)
  errors.scheduleId = form.scheduleId ? '' : '请选择就诊时段'
  errors.petId = form.petId ? '' : '请选择就诊宠物'
  errors.symptom = form.symptom.trim() ? '' : '请描述宠物的症状'
  // 原因: 60 秒轮询时钟可能未触发更新，提交前必须取当前实时时刻兜底重校
  const n = localNow()
  if (form.scheduleId && (!picked.value || !bookable(picked.value) || slotEnded(picked.value, n.date, n.hhmm))) {
    form.scheduleId = null
    errors.scheduleId = '已选时段已不可预约，请重新选择'
  }
  const invalidField = Object.keys(errors).find((field) => errors[field])
  if (invalidField) return focusError(invalidField)
  busy.value = true
  try {
    const vo = await createAppointment(
      { ...form, doctorId, symptom: form.symptom.trim() },
      { headers: { 'Idempotency-Key': idemKey.value } }
    )
    paidOrder.value = vo
    // 原因: 下单成功后即结束当前意图并换新键，避免后续新单复用旧键命中已完成预约
    idemKey.value = newIdempotencyKey()
    payOpen.value = true
  } catch {
    // 原因: 请求层已提示失败原因，此处保留表单输入与当前幂等键供重试
  } finally {
    busy.value = false
  }
}

function leavePayment() {
  if (paidOrder.value) router.replace(`/order/${paidOrder.value.id}`)
}

function goBack() {
  if (window.history.state?.back) router.back()
  else router.replace(`/doctor/${doctorId}`)
}

onMounted(() => {
  clockNow()
  nowTimer = setInterval(clockNow, 60000)
})

onBeforeUnmount(() => {
  if (nowTimer) clearInterval(nowTimer)
  ctaObserver?.disconnect()
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [d] = await Promise.all([doctorDetail(doctorId), reloadSlots(), loadPets(true)])
    doctor.value = d
    if (!d) return
    if (d.status !== 1) form.scheduleId = null
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)

</script>

<template>
  <div class="pc-narrow has-cta booking-page" :style="{ '--cta-bar-height': `${ctaHeight}px` }" v-loading="loading">
    <header class="page-head">
      <button class="back" type="button" aria-label="返回上一页" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <h1 class="pc-title">确认挂号</h1>
    </header>
    <LoadFailure v-if="failed" @retry="load" />
    <el-empty v-else-if="!loading && !doctor" description="暂无医生信息" />
    <template v-else-if="doctor">

    <section class="who">
      <el-avatar :size="48" :src="doctor.avatar" alt="">{{ doctor.name?.[0] }}</el-avatar>
      <div class="who-id">
        <b>{{ doctor.name }}</b>
        <span>{{ doctor.title }} · {{ doctor.departmentName }}</span>
      </div>
    </section>
    <p v-if="doctor.status !== 1" class="notice" role="status">
      <span class="dot" aria-hidden="true" />该医生目前停诊，暂时无法挂号
    </p>

    <!-- 原因: 排班时段会被其他用户抢占且多数入口已带号进来，置于首位优先确认 -->
    <section ref="scheduleSection" class="pc-card blk" :class="{ invalid: errors.scheduleId }" tabindex="-1">
      <h2 class="bh-t">就诊时段</h2>
      <div v-if="grouped.length" class="days">
        <div class="date-strip pc-date-strip" role="group" aria-label="选择就诊日期">
          <button v-for="g in grouped" :key="g.date" type="button" class="date-option pc-date-option"
                  :class="{ on: selectedDate === g.date && doctor.status === 1, empty: !dayRemain(g) }"
                  :aria-pressed="selectedDate === g.date && doctor.status === 1"
                  :aria-label="`${dayLabel(g.date)} ${toDate(g.date)}，${dayRemain(g) ? `${dayRemain(g)} 个号` : '无号'}`"
                  @click="selectDate(g.date)">
            <b>{{ dayLabel(g.date).split(' ').at(-1) }}</b>
            <span class="dt">{{ toDate(g.date).slice(5).replace('-', '/') }}</span>
            <span class="date-state">{{ dayRemain(g) ? `${dayRemain(g)} 个号` : '无号' }}</span>
          </button>
        </div>
        <Transition name="day-switch" mode="out-in">
          <div v-if="currentDay" :key="selectedDate" class="row" role="group" :aria-label="`${toDate(selectedDate)} 就诊时段`" :aria-describedby="errors.scheduleId ? 'booking-slot-error' : undefined">
            <button v-for="s in currentDay.list" :key="s.id" type="button" class="slot"
                    :class="{ off: !bookable(s), on: form.scheduleId === s.id }"
                    :aria-pressed="form.scheduleId === s.id" :disabled="!bookable(s)" @click="selectSlot(s)">
              <span class="pd">{{ text(PERIOD, s.period) }}</span>
              <span class="tm">{{ s.startTime?.slice(0, 5) }}–{{ s.endTime?.slice(0, 5) }}</span>
              <span class="rm">
                {{ doctor.status !== 1 ? '医生停诊' : s.doctorAvailable === false || s.status !== 1 ? '已停诊' : slotEnded(s, nowTick.slice(0, 10), nowTick.slice(11, 16)) ? '已结束' : s.remainNum > 0 ? `剩 ${s.remainNum} 个号` : '约满' }}
              </span>
            </button>
          </div>
        </Transition>
      </div>
      <el-empty v-else :image-size="60" description="该医生近 7 天暂无排班">
        <el-button @click="router.push('/doctors')">换一位医生</el-button>
      </el-empty>
      <p v-if="errors.scheduleId" id="booking-slot-error" class="field-error" role="alert">{{ errors.scheduleId }}</p>
    </section>

    <section ref="petSection" class="pc-card blk" :class="{ invalid: errors.petId }" tabindex="-1">
      <div v-if="petsFailed" class="block-fail">
        <h2 class="bh-t">就诊宠物</h2>
        <p class="pc-muted">宠物档案暂时没加载出来</p>
        <el-button @click="loadPets(true)">重新加载</el-button>
      </div>
      <PetPicker v-else v-model="form.petId" :pets="myPets" radio-name="booking-pet" title="就诊宠物"
                 :error="errors.petId" error-id="booking-pet-error" @add="petOpen = true" />
    </section>

    <section ref="symptomSection" class="pc-card blk" :class="{ invalid: errors.symptom }" tabindex="-1">
      <h2 class="bh-t">症状描述<span class="pc-muted required">必填</span></h2>
      <el-input v-model="form.symptom" type="textarea" aria-label="症状描述" aria-required="true" :aria-invalid="!!errors.symptom" :aria-describedby="errors.symptom ? 'booking-symptom-error' : undefined" :rows="3" maxlength="500" show-word-limit
                placeholder="何时开始不适？有哪些症状？是否用过药？"
                @focus="event => event.target.scrollIntoView({ block: 'center' })" />
      <p v-if="errors.symptom" id="booking-symptom-error" class="field-error" role="alert">{{ errors.symptom }}</p>
      <h2 class="bh-t extra">患处照片<span class="pc-muted required">选填</span></h2>
      <ImageUpload v-model="form.images" :max="6" />
    </section>

    <div ref="ctaBar" class="cta-bar pc-cta-bar">
      <div class="cta-info">
        <p class="selection" :class="{ picked: picked && pickedPet }" aria-live="polite">{{ nextHint }}</p>
        <p class="sum">
          <span class="pc-muted">到院问诊费</span>
          <b>{{ money(amount) }}</b>
        </p>
      </div>
      <el-button type="primary" size="large" class="go" :loading="busy" :disabled="doctor.status !== 1 || !hasBookable" @click="submit">提交挂号</el-button>
    </div>

    <PetForm v-model="petOpen" @saved="loadPets(true)" />

    <PaymentTicket v-model="payOpen" :order="paidOrder" @closed="leavePayment" />
    </template>
  </div>
</template>

<style scoped>
/* 标题与返回同一行：返回不额外占一行高度，首屏尽量早露出时段 */
.page-head { display: flex; align-items: center; gap: 4px; margin: 8px 0 16px; }
.page-head .pc-title { margin: 0; }
.back {
  width: 44px; height: 44px; margin-left: -12px; padding: 0; border: 0;
  display: grid; place-items: center; background: none; color: var(--color-ink);
  font-size: 18px; cursor: pointer; border-radius: var(--radius-full);
}
@media (hover: hover) and (pointer: fine) {
  .back:hover { background: var(--color-surface-strong); }
}

/* 医生是谁属于上下文，不做成又一张卡片，也不再加分隔线——下面的卡已经足够分开 */
.who { display: flex; align-items: center; gap: 12px; padding: 0 2px 4px; }
.who-id { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.who-id b { font-size: 17px; font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; }
.who-id span { color: var(--color-muted); font-size: var(--font-label); line-height: 1.5; overflow-wrap: anywhere; }
/* 停诊是终止态，用灰而不是错误红：用户没有做错事 */
.notice {
  display: flex; align-items: flex-start; gap: 8px;
  margin: 14px 0 0; padding: 10px 12px; border-radius: var(--radius-sm);
  background: var(--color-terminated-soft); color: var(--color-body);
  font-size: var(--font-label); line-height: 1.6;
}
.notice .dot { flex: none; width: 6px; height: 6px; margin-top: 7px; border-radius: 50%; background: var(--color-terminated); }

.blk { margin-top: 12px; scroll-margin-top: 80px; scroll-margin-bottom: calc(var(--tab-bar-height) + var(--cta-bar-height) + 16px); }
.blk.invalid { border-color: var(--color-danger-text); }
.field-error { margin: 10px 0 0; color: var(--color-danger-text); font-size: var(--font-label); line-height: 1.6; }
.bh-t { margin: 0 0 12px; font-size: 16px; font-weight: 600; line-height: 1.5; }
.bh-t.extra { margin-top: 22px; }
.required { margin-left: 8px; font-weight: 400; }

/* 宠物卡的同名样式已随 PetPicker 走；时段名 .pd 仍要这条基础样式 */
.pd { font-size: var(--font-label); color: var(--color-muted); white-space: normal; overflow-wrap: anywhere; }

.days { margin-top: 4px; }
/* 日期条外壳与选中态走公共 .pc-date-strip / .pc-date-option；
   这里只留本页的滚动容器偏移与「整天无号」态。 */
.date-strip { position: relative; overflow-x: auto; padding: 4px 3px 10px; margin: -4px -3px 4px; }
.date-option {
  flex: 0 0 78px; min-height: 80px; justify-content: center;
  padding: 8px 6px;
}
/* 整天无号：弱化成虚线，和可约日期拉开，但保留点开查看的能力 */
.date-option.empty:not(.on) { background: var(--color-surface-soft); border-style: dashed; }
.date-option.empty:not(.on) .dt, .date-option.empty:not(.on) .date-state { color: var(--color-muted-soft); }
.day-switch-enter-active, .day-switch-leave-active { transition: opacity .12s var(--ease), transform .12s var(--ease); }
.day-switch-enter-from, .day-switch-leave-to { opacity: 0; transform: translateY(3px); }
.row {
  display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px;
  scroll-snap-type: x mandatory; -webkit-overflow-scrolling: touch;
}
/* 三个时段在手机宽度内铺满；更长的时段表才横向滚动 */
.slot {
  flex: 1 1 0; min-width: 96px; min-height: 76px; scroll-snap-align: start;
  border: 1px solid var(--color-hairline); background: var(--color-canvas);
  border-radius: var(--radius-sm); padding: 10px 12px; cursor: pointer; display: flex;
  flex-direction: column; justify-content: center; gap: 2px; text-align: left;
  transition: border-color .18s var(--ease), background-color .18s var(--ease), color .18s var(--ease);
}
.slot .pd { font-size: var(--font-label); font-weight: 600; line-height: 1.5; }
.slot .tm { font-size: var(--font-meta); color: var(--color-muted); line-height: 1.5; white-space: nowrap; font-variant-numeric: tabular-nums; }
.slot .rm { font-size: var(--font-meta); color: var(--color-body); line-height: 1.5; white-space: nowrap; }
@media (hover: hover) and (pointer: fine) {
  .slot:not(.off):hover { border-color: var(--color-border-strong); }
}
.slot.on { border-color: var(--color-ink); background: var(--color-ink); color: var(--color-on-primary); }
.slot.on .pd, .slot.on .tm, .slot.on .rm { color: var(--color-on-primary); }
.slot.on .tm, .slot.on .rm { opacity: .82; }
.slot.off { cursor: not-allowed; background: var(--color-surface-soft); border-style: dashed; }
.slot.off .pd, .slot.off .tm, .slot.off .rm { color: var(--color-muted-soft); }

.block-fail p { margin: 0 0 12px; }

/* sticky CTA 条坐在 tab bar 之上，不是屏幕底部。
   位置、宽度上限与白底投影走公共 .pc-cta-bar；这里留高度与内部排布。
   min-height 不用 height：内容换行时条要跟着长，ResizeObserver 再把实测值写回 --cta-bar-height。 */
.cta-bar {
  min-height: 84px;
  display: flex; align-items: center; gap: 12px; padding: 12px 16px;
}
.cta-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.selection { margin: 0; color: var(--color-muted); font-size: var(--font-label); line-height: 1.5; overflow-wrap: anywhere; }
.selection.picked { color: var(--color-ink); font-weight: 600; }
.sum { margin: 0; display: flex; align-items: baseline; gap: 6px; min-width: 0; overflow-wrap: anywhere; }
.sum b { font-size: 22px; font-weight: 700; line-height: 1.35; }
.cta-bar .go { flex: none; margin-left: 0; min-width: 128px; }
@media (max-width: 359px) {
  .cta-bar .go { min-width: 112px; }
  /* 320px 放不下三个时段，横滚会把第三块切成半截；改成两列换行更好读 */
  .row { flex-wrap: wrap; overflow-x: visible; }
  .slot { flex: 1 1 calc(50% - 4px); min-width: 0; }
}
@media (max-width: 279px) {
  .cta-bar { flex-direction: column; align-items: stretch; gap: 8px; }
  .cta-bar .go { width: 100%; min-width: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .day-switch-enter-active, .day-switch-leave-active { transition: none; }
  .day-switch-enter-from, .day-switch-leave-to { transform: none; }
}

</style>
