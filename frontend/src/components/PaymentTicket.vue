<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { appointmentDetail, payAppointment } from '@/api/user'
import { canPay, money, toDate } from '@/utils/format'
import { PERIOD, text } from '@/utils/dict'
import logo from '@/assets/logo.svg'

const props = defineProps({ modelValue: Boolean, order: Object })
const emit = defineEmits(['update:modelValue', 'closed'])
const current = ref(null)
const busy = ref(false)
const success = ref(false)
const error = ref('')
const uncertain = ref(false)
const now = ref(Date.now())
const primary = ref(null)
let timer
const deadline = computed(() => new Date(String(current.value?.payDeadline || '').replace(' ', 'T')).getTime())
const allowed = computed(() => canPay(current.value, now.value))
const remaining = computed(() => Math.max(0, Math.ceil((deadline.value - now.value) / 1000)))
const countdown = computed(() => `${String(Math.floor(remaining.value / 60)).padStart(2, '0')}:${String(remaining.value % 60).padStart(2, '0')}`)
const price = computed(() => money(current.value?.amount).replace('¥ ', '').split('.'))
const unavailable = computed(() => {
  if (success.value || busy.value || uncertain.value) return ''
  if (!Number.isFinite(deadline.value)) return '未获取到支付截止时间，请查看预约确认状态。'
  if (current.value?.status !== 0 || current.value?.payStatus !== 0) return '订单状态已变更，请查看预约。'
  return allowed.value ? '' : '支付时间已结束，请查看预约状态。'
})

watch(() => props.modelValue, (open) => {
  clearInterval(timer)
  if (!open) return
  current.value = props.order
  success.value = props.order?.payStatus === 1
  error.value = ''
  uncertain.value = false
  now.value = Date.now()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
}, { immediate: true })
onBeforeUnmount(() => clearInterval(timer))

function close() {
  if (!busy.value) emit('update:modelValue', false)
}
function beforeClose(done) {
  if (!busy.value) done()
}
async function focusPrimary() {
  await nextTick()
  primary.value?.focus()
}
async function pay() {
  now.value = Date.now()
  if (busy.value || success.value || uncertain.value || !allowed.value) return
  busy.value = true
  error.value = ''
  try {
    await payAppointment(current.value.id, { silent: true })
    success.value = true
  } catch (cause) {
    // 原因: 支付请求响应丢失时后端可能已入账，提供重试前先回查订单真实支付状态
    try {
      current.value = await appointmentDetail(current.value.id, { silent: true })
      success.value = current.value?.payStatus === 1
      if (!success.value) error.value = typeof cause.code === 'number'
        ? cause.message : '支付请求未完成，请重试。'
    } catch {
      uncertain.value = true
      error.value = '暂时无法确认支付结果，请查看预约后确认。'
    }
  } finally {
    busy.value = false
    now.value = Date.now()
    await focusPrimary()
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" class="payment-ticket" width="min(432px, calc(100vw - 32px))"
    align-center :show-close="false" :close-on-click-modal="false" :close-on-press-escape="!busy"
    :before-close="beforeClose" @update:model-value="emit('update:modelValue', $event)"
    @closed="emit('closed')" @opened="focusPrimary">
    <template #header="{ titleId }">
      <div class="ticket-brand"><img :src="logo" alt="" />到院预约</div>
      <h2 :id="titleId">{{ success ? '预约已生效' : '确认这次就诊' }}</h2>
      <button class="ticket-close" aria-label="关闭支付弹窗" :disabled="busy" @click="close">×</button>
    </template>
    <template v-if="current">
      <section class="ticket-visit" aria-label="就诊信息">
        <div class="ticket-summary">
          <div><h3>{{ current.petName || '就诊宠物' }}</h3><p>接诊医生 {{ current.doctorName || '—' }}</p></div>
          <div class="ticket-queue"><b>{{ String(current.queueNo ?? '—').padStart(2, '0') }}</b><span>排队号</span></div>
        </div>
        <div class="ticket-schedule"><span>就诊时间</span><div><b>{{ toDate(current.appointmentDate) }}</b><p>{{ text(PERIOD, current.period) }}<span v-if="current.startTime && current.endTime" class="ticket-time">{{ current.startTime.slice(0, 5) }}–{{ current.endTime.slice(0, 5) }}</span></p></div></div>
        <div class="ticket-order"><span>预约单号</span><b>{{ current.orderNo }}</b></div>
      </section>
      <section class="ticket-stub" aria-label="支付信息" :aria-busy="busy">
        <div class="ticket-total">
          <div><span>{{ success ? '已支付' : '挂号费用' }}</span><div class="ticket-money"><i>¥</i>{{ price[0] }}<small>.{{ price[1] }}</small></div></div>
          <div v-if="!success" class="ticket-clock"><span>支付剩余时间</span><b :class="{ urgent: remaining <= 60 }">{{ Number.isFinite(deadline) ? countdown : '—' }}</b></div>
        </div>
        <div class="ticket-feedback-slot">
        <Transition name="ticket-feedback" mode="out-in">
          <p v-if="success" key="success" class="ticket-feedback success" role="status"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4 10-10" /></svg>支付成功，请按预约时间到院</p>
          <p v-else-if="unavailable || error" key="error" class="ticket-feedback error" role="alert">{{ unavailable || error }}</p>
          <p v-else-if="busy" key="busy" class="ticket-feedback" role="status">正在确认支付结果，请稍候…</p>
        </Transition>
        </div>
        <button v-if="success || unavailable || uncertain" ref="primary" class="ticket-primary" @click="close">查看预约</button>
        <button v-else ref="primary" class="ticket-primary" :disabled="busy" @click="pay"><span v-if="busy" class="ticket-spinner" aria-hidden="true" />{{ busy ? '正在支付…' : `${error ? '重新支付' : '确认支付'} ${money(current.amount)}` }}</button>
        <button v-if="!success && !unavailable && !uncertain" class="ticket-later" :disabled="busy" @click="close">稍后支付</button>
      </section>
    </template>
  </el-dialog>
</template>

<style>
.el-dialog.payment-ticket{padding:0;border-radius:12px;overflow:hidden;color:var(--color-ink);background:transparent;box-shadow:none;filter:drop-shadow(0 2px 2px #0000000a) drop-shadow(0 20px 28px #0003);--el-dialog-margin-top:4vh}
.payment-ticket .el-dialog__header{padding:26px 30px 0;margin:0;position:relative;background:var(--color-canvas)}
.payment-ticket .el-dialog__body{padding:0;color:inherit;overflow-wrap:anywhere}
.payment-ticket h2{font:500 27px/1.4 SimSun,serif;letter-spacing:1px;margin:22px 0 0}
.ticket-brand{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--color-muted)}
.ticket-brand img{width:25px;height:25px}
.payment-ticket button{font:inherit;cursor:pointer}
.ticket-close{position:absolute;right:12px;top:12px;width:44px;height:44px;border:0;background:none;color:var(--color-muted);font-size:25px!important;border-radius:50%}
.ticket-visit{padding:0 30px 25px;display:flow-root;background:var(--color-canvas);mask:radial-gradient(circle at left bottom,transparent 11px,#000 11.8px) left/51% 100% no-repeat,radial-gradient(circle at right bottom,transparent 11px,#000 11.8px) right/51% 100% no-repeat}
.ticket-summary{display:flex;justify-content:space-between;align-items:center;gap:16px;margin:22px 0 19px}
.ticket-summary h3{margin:0;font-size:22px;font-weight:600}.ticket-summary p{margin:6px 0 0;font-size:14px;color:var(--color-muted);line-height:1.6}
.ticket-queue{flex:none;min-width:76px;padding-left:20px;border-left:1px solid var(--color-hairline-soft);text-align:center}
.ticket-queue b{display:block;font:42px/1.1 Consolas,monospace;letter-spacing:-2px}.ticket-queue span{display:block;margin-top:5px;color:var(--color-muted);font-size:13px;letter-spacing:2px}
.ticket-schedule{border-block:1px solid var(--color-hairline-soft);padding:15px 0;display:flex;align-items:center;justify-content:space-between;gap:12px}
.ticket-schedule>span,.ticket-order>span{flex:none;font-size:13px;color:var(--color-muted)}.ticket-schedule>div{text-align:right}.ticket-schedule b{font-size:16px;font-weight:600}.ticket-schedule p{font-size:14px;color:var(--color-muted);margin:5px 0 0;line-height:1.6}
.ticket-order{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:18px}.ticket-order b{font:14px/1.6 Consolas,monospace;color:var(--color-body);text-align:right}
.ticket-time{margin-left:6px;white-space:nowrap}
.ticket-stub{position:relative;background:var(--color-surface-soft);padding:26px 30px 18px;mask:radial-gradient(circle at left top,transparent 11px,#000 11.8px) left/51% 100% no-repeat,radial-gradient(circle at right top,transparent 11px,#000 11.8px) right/51% 100% no-repeat}
.ticket-stub:before{content:'';position:absolute;top:0;left:19px;right:19px;height:1.5px;background:repeating-linear-gradient(to right,#969696 0 5px,transparent 5px 10px);box-shadow:0 1px 0 #fff}
.ticket-total{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:21px}.ticket-total span{font-size:13px;color:var(--color-muted)}
.ticket-money{display:flex;align-items:baseline;font:40px/1.2 Consolas,monospace;letter-spacing:-1.7px;margin-top:5px}.ticket-money i{font-size:21px;font-style:normal;margin-right:4px}.ticket-money small{font-size:27px;letter-spacing:-1px}
.ticket-clock{text-align:right}.ticket-clock b{display:block;font:19px/1.4 Consolas,monospace;letter-spacing:1px;margin-top:6px}.ticket-clock .urgent{color:var(--color-danger-text)}
.ticket-primary{width:100%;min-height:50px;padding:12px;border:0;border-radius:6px;background:var(--color-action);color:white;font-weight:600!important;box-shadow:inset 0 1px 0 #ffffff26;transition:background .15s,transform .1s;display:flex;align-items:center;justify-content:center;gap:8px}
.ticket-primary:not(:disabled):hover{background:var(--color-action-hover)}.ticket-primary:not(:disabled):active{transform:scale(.99);background:var(--color-action-active)}
.ticket-later{display:block;min-height:44px;margin:5px auto 0;border:0;background:none;color:var(--color-body);padding:8px 18px;font-size:14px!important}
.payment-ticket button:disabled{cursor:wait;opacity:.65}.payment-ticket button:focus-visible{outline:2px solid var(--color-ink);outline-offset:3px}
.payment-ticket .ticket-primary:disabled{opacity:1;background:var(--color-action-active)}
.ticket-feedback-slot{min-height:24px;margin-bottom:16px}
.ticket-feedback{font-size:14px;line-height:1.6;margin:0;color:var(--color-body);display:flex;gap:8px;align-items:center}.ticket-feedback.error{color:var(--color-danger-text)}.ticket-feedback.success{color:var(--color-success)}
.ticket-feedback svg{width:24px;height:24px;flex:none;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.ticket-feedback svg path{stroke-dasharray:30;animation:ticket-check .3s ease-out}
.ticket-feedback-enter-active,.ticket-feedback-leave-active{transition:opacity .16s,transform .16s}.ticket-feedback-enter-from,.ticket-feedback-leave-to{opacity:0;transform:translateY(4px)}
.ticket-spinner{width:16px;height:16px;border:2px solid #ffffff60;border-top-color:white;border-radius:50%;animation:ticket-spin .7s linear infinite}
@keyframes ticket-spin{to{transform:rotate(360deg)}}@keyframes ticket-check{from{stroke-dashoffset:30}to{stroke-dashoffset:0}}
@media(max-width:359px){.payment-ticket .el-dialog__header{padding:22px 20px 0}.ticket-visit{padding:0 20px 20px}.ticket-stub{padding:22px 20px 16px}.ticket-order{align-items:flex-start}.ticket-order b{max-width:155px}.ticket-money{font-size:34px}}
@media(max-width:400px){.ticket-feedback-slot{min-height:45px}}
@media(prefers-reduced-motion:reduce){.payment-ticket,.payment-ticket *{animation:none!important;transition:none!important}.ticket-primary:active{transform:none!important}}
</style>
