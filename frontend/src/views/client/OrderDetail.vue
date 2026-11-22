<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import ImageGallery from '@/components/ImageGallery.vue'
import StatusChip from '@/components/StatusChip.vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ElMessageBox } from '@/utils/messageBox'
import {
  appointmentDetail, appointmentRecord, cancelAppointment, payAppointment
} from '@/api/user'
import { APPT_STATUS, CONSULT_TYPE, PERIOD, tagType, text } from '@/utils/dict'
import { canPay, money, toDate, toDateTime } from '@/utils/format'

/**
 * 预约详情。按"什么时候发生什么"排成一条行程轴：
 * 首屏是日期、时段、当前状态与排队号，下面用细线分节核对医生、宠物、病情和费用。
 * 状态机有支付这一条正交轴（未支付 / 已支付 / 已退款），
 * 行程轴与底部操作都按两条轴的组合取值，不能只看 status。
 */
const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const order = ref(null)
const record = ref(null)
const failed = ref(false)
const loading = ref(true)
const action = ref('')
const busy = computed(() => !!action.value)
let timer = null
let expiryRefresh = 0

const images = computed(() => (order.value?.images || '').split(',').filter(Boolean))
const needPay = computed(() => order.value?.status === 0 && order.value?.payStatus === 0)
const now = ref(Date.now())
const paymentAllowed = computed(() => canPay(order.value, now.value))

const closed = computed(() => [3, 4].includes(order.value?.status))
const statusText = computed(() => {
  if (needPay.value) return overdue.value ? '支付已超时' : '待支付'
  return text(APPT_STATUS, order.value?.status)
})
/* 支付超时、取消、未到诊都是"这张单子的结论"，都从首屏主角的位置说话；
   这条 computed 依赖 cancelByLabel，所以放在它后面 */
const verdict = computed(() => {
  if (overdue.value) return { text: '支付已超时', tone: 'danger', note: '已过支付时限' }
  if (order.value?.status === 3) return { text: '已取消', tone: 'terminated', note: cancelByLabel.value }
  if (order.value?.status === 4) return { text: '未到诊', tone: 'terminated', note: '这次预约已过就诊时段' }
  return null
})
const statusTone = computed(() => {
  // 超时是失败态的过渡：仍停在待接诊，但已经不能再支付，用危险色而不是待办橙
  if (needPay.value) return overdue.value ? 'danger' : 'warning'
  return tagType(APPT_STATUS, order.value?.status)
})
const queueLabel = computed(() => (closed.value ? '原排队号' : '排队号'))
const doctorMeta = computed(() =>
  [order.value?.doctorTitle, order.value?.departmentName].filter(Boolean).join(' · ')
)
const petMeta = computed(() =>
  [order.value?.petSpecies, order.value?.petBreed || '品种未填'].filter(Boolean).join(' · ')
)
const slotRange = computed(() => {
  const s = order.value?.startTime?.slice(0, 5)
  const e = order.value?.endTime?.slice(0, 5)
  return s && e ? `${s}–${e}` : ''
})

/* 首屏大日期不用完整时间串：年单独一行，月和日放开，周几跟在后面 */
const dateParts = computed(() => {
  const raw = toDate(order.value?.appointmentDate)
  if (!raw) return { year: '', md: '—', week: '' }
  const [y, m, d] = raw.split('-')
  const dt = new Date(`${raw.replace(/-/g, '/')} 00:00:00`)
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][dt.getDay()]
  return { year: y, md: `${m}.${d}`, week }
})

/* 支付倒计时只在待支付时计算；deadline 早于当前时间或缺失都按不可支付处理 */
const leftSeconds = computed(() => {
  if (!needPay.value || !order.value?.payDeadline) return 0
  const end = new Date(String(order.value.payDeadline).replace(' ', 'T')).getTime()
  return Math.max(0, Math.floor((end - now.value) / 1000))
})
const overdue = computed(() => needPay.value && !!order.value?.payDeadline && !paymentAllowed.value)
const missingDeadline = computed(() => needPay.value && !order.value?.payDeadline)
const countdown = computed(() => {
  const s = leftSeconds.value
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
})

/**
 * 行程轴。正常单子固定四段（支付 → 待接诊 → 就诊中 → 已完成），
 * 取消与未到诊是终态，只保留实际发生过的节点，避免把"已完成"画成一个还能到达的未来。
 */
const rail = computed(() => {
  const o = order.value
  if (!o) return []
  /* 超时未支付只发生过"下单"和"超时"，后面三段不会再到达 */
  if (overdue.value) {
    return [
      { label: '已下单', state: 'done' },
      { label: '支付超时', state: 'bad' }
    ]
  }
  /* 取消单先说取消，再说退款：退款是取消的结果，不能排在取消之前 */
  if (o.status === 3 || o.status === 4) {
    const first = o.payStatus === 1 || o.payStatus === 2
      ? { label: '已支付', state: 'done' }
      : { label: '未支付', state: 'skip' }
    return [first, { label: text(APPT_STATUS, o.status), state: 'stop' }]
  }
  const payLabel = o.payStatus === 2 ? '已退款' : o.payStatus === 1 ? '已支付' : '待支付'
  const steps = [
    { label: payLabel, state: 'todo' },
    { label: '待接诊', state: 'todo' },
    { label: '就诊中', state: 'todo' },
    { label: '已完成', state: 'todo' }
  ]
  if (o.status === 0) {
    if (o.payStatus === 0) {
      steps[0].state = 'now'
    } else {
      steps[0].state = 'done'
      steps[1].state = 'now'
    }
  } else if (o.status === 1) {
    steps[0].state = o.payStatus === 0 ? 'skip' : 'done'
    steps[1].state = 'done'
    steps[2].state = 'now'
  } else if (o.status === 2) {
    steps[0].state = o.payStatus === 0 ? 'skip' : 'done'
    steps[1].state = 'done'
    steps[2].state = 'done'
    steps[3].state = 'now'
  }
  return steps
})

const cancelByLabel = computed(() => {
  const by = order.value?.cancelBy
  if (by === 'USER') return '你取消了预约'
  if (by === 'DOCTOR') return '医生取消了预约'
  if (by === 'ADMIN') return '医院取消了预约'
  if (by === 'SYSTEM') return '超时未支付，系统自动取消'
  return '预约已取消'
})

/* 一段说明只承担一件事：待支付的倒计时、取消或未到诊的原因、到院提醒 */
const notice = computed(() => {
  const o = order.value
  if (!o) return null
  if (needPay.value) {
    if (missingDeadline.value) {
      return { tone: 'warning', lead: '暂时无法支付', body: '没有获取到支付截止时间，请稍后刷新重试。' }
    }
    if (overdue.value) {
      return {
        tone: 'danger',
        body: '系统正在取消这笔预约并退回号源，本页会自动刷新为最新状态。'
      }
    }
    return {
      tone: 'warning',
      lead: `请在 ${toDateTime(o.payDeadline)} 前完成支付`,
      body: '超时后系统会自动取消预约并退回号源。'
    }
  }
  if (o.status === 0) {
    return {
      tone: 'quiet',
      body: '到院后请按排队号就诊。',
      action: { label: '改期或取消', run: cancel }
    }
  }
  if (o.status === 1) {
    return {
      tone: 'quiet',
      body: o.sessionId ? '医生已接诊，可以在就诊室查看进展。' : '医生已接诊，请配合完成本次诊疗。',
      action: o.sessionId ? { label: '进入就诊室', run: () => router.push(`/consult/${o.sessionId}`) } : null
    }
  }
  if (o.status === 2 && o.isReviewed !== 1) {
    return {
      tone: 'quiet',
      lead: '本次就诊已完成',
      body: '给这次就诊写个评价，帮助其他宠物主人了解这位医生。'
    }
  }
  if (o.status === 3) {
    const parts = []
    if (o.cancelTime) parts.push(`取消于 ${toDateTime(o.cancelTime)}。`)
    if (o.payStatus === 2) parts.push(`已支付的 ${money(o.amount)} 已原路退回。`)
    if (o.cancelReason) parts.push(`原因：${o.cancelReason}`)
    return { tone: 'terminated', body: parts.join('') }
  }
  if (o.status === 4) {
    return {
      tone: 'terminated',
      body: '系统没有记录到诊。需要继续就诊时，可以重新预约。'
    }
  }
  return null
})

const payLine = computed(() => {
  const o = order.value
  if (!o) return ''
  if (o.payStatus === 1) return o.payTime ? `已支付 · ${toDateTime(o.payTime)}` : '已支付'
  if (o.payStatus === 2) return '已退款'
  return '未支付'
})
const payLineTone = computed(() => 'muted')

const ops = computed(() => {
  const o = order.value
  if (!o) return []
  const list = []
  const go = (path) => router.push(path)
  if (o.status === 0) {
    if (o.sessionId) {
      list.push({
        label: '进入图文问诊',
        kind: needPay.value ? 'secondary' : 'primary',
        run: () => go(`/consult/${o.sessionId}`)
      })
    }
  } else if (o.status === 1) {
    // 就诊中的主动作已经放在状态说明里，不再在页尾重复一次
    if (o.recordId) list.push({ label: '查看病历', kind: 'secondary', run: () => go(`/record/${o.recordId}`) })
  } else if (o.status === 2) {
    // 病历卡已经把诊断和入口摆在页面上，这里不重复"查看病历"
    if (o.isReviewed !== 1) {
      list.push({ label: '评价本次就诊', kind: 'primary', run: () => go(`/review/${o.id}`) })
    } else {
      list.push({ label: '再次预约', kind: 'primary', run: () => go(`/book/${o.doctorId}`) })
    }
  } else if (o.status === 3 || o.status === 4) {
    list.push({ label: '再次预约', kind: 'primary', run: () => go(`/book/${o.doctorId}`) })
  }
  return list
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    order.value = await appointmentDetail(id)
    // 医生没写病历时接口 data 为空，按「无病历」渲染
    record.value = (await appointmentRecord(id).catch(() => null)) || null
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function pay() {
  if (busy.value || !paymentAllowed.value) return
  action.value = 'pay'
  try {
    await payAppointment(id)
    ElMessage.success('支付成功，预约已生效')
    await load()
  } catch {
    // 请求层已提示失败，保留当前订单供重试。
  } finally {
    action.value = ''
  }
}

async function cancel() {
  if (busy.value) return
  action.value = 'cancel'
  try {
    const tip = order.value?.payStatus === 1
      ? `取消后号源会退回，已支付的 ${money(order.value.amount)} 将原路退回。`
      : '取消后号源会退回，本次预约作废。'
    const { value } = await ElMessageBox.prompt(tip, '取消这次预约？', {
      inputPlaceholder: '取消原因（选填）',
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputValidator: (v) => ((v || '').length > 200 ? '取消原因不超过 200 字' : true)
    }).catch(() => ({}))
    if (value === undefined) return
    await cancelAppointment(id, { reason: value || undefined })
    ElMessage.success('预约已取消，号源已退回')
    await load()
  } catch {
    // 请求失败保留当前预约和操作入口。
  } finally {
    action.value = ''
  }
}

onMounted(() => {
  load()
  timer = setInterval(() => {
    now.value = Date.now()
    if (needPay.value && !paymentAllowed.value && !loading.value && now.value - expiryRefresh >= 60000) {
      expiryRefresh = now.value
      load()
    }
  }, 1000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="pc-narrow detail" :class="{ 'has-cta': needPay && !overdue }" v-loading="loading">
    <h1 class="sr-only">预约详情</h1>
    <LoadFailure v-if="failed" @retry="load" />
    <template v-else-if="order">
      <section class="hero" :class="{ 'hero-verdict': verdict }">
        <template v-if="verdict">
          <div class="verdict" :class="`t-${verdict.tone}`">
            <b>{{ verdict.text }}</b>
            <span>{{ verdict.note }}</span>
          </div>
          <div class="when">
            <b>{{ dateParts.md }} {{ dateParts.week }}</b>
            <span>{{ text(PERIOD, order.period) }}&nbsp;<template v-if="slotRange">{{ slotRange }}</template></span>
          </div>
        </template>
        <template v-else>
          <div class="date">
            <span class="yr">{{ dateParts.year }}</span>
            <b>{{ dateParts.md }}</b>
            <span class="wd">{{ dateParts.week }}</span>
          </div>
          <div class="slot">
            <b>{{ text(PERIOD, order.period) }}</b>
            <span v-if="slotRange">{{ slotRange }}</span>
          </div>
        </template>
      </section>

      <div class="statusline">
        <StatusChip v-if="!verdict" :tone="statusTone">{{ statusText }}</StatusChip>
        <span class="sub">{{ text(CONSULT_TYPE, order.consultType) }}</span>
        <span v-if="order.queueNo" class="queue">{{ queueLabel }}<b>{{ order.queueNo }}</b></span>
      </div>

      <ol class="rail" :class="`t-${statusTone}`" :style="{ '--steps': rail.length }" aria-label="预约进度">
        <li
          v-for="(step, i) in rail"
          :key="`${step.label}-${i}`"
          :class="step.state"
          :aria-current="step.state === 'now' ? 'step' : undefined"
        >{{ step.label }}</li>
      </ol>

      <p v-if="notice" class="notice" :class="`t-${notice.tone}`">
        <b v-if="notice.lead">{{ notice.lead }}</b>
        <span>{{ notice.body }}<template v-if="notice.cd"> 剩余 <span class="cd">{{ countdown }}</span>。</template></span>
        <button v-if="notice.action" type="button" class="notice-link" @click="notice.action.run()">{{ notice.action.label }}</button>
      </p>

      <h2 class="sec">就诊信息</h2>
      <dl class="facts">
        <div class="row">
          <dt>医生</dt>
          <dd class="doc">
            <el-avatar :size="32" :src="order.doctorAvatar" alt="">{{ order.doctorName?.[0] }}</el-avatar>
            <span class="n">
              <b>{{ order.doctorName }}</b>
              <em v-if="doctorMeta">{{ doctorMeta }}</em>
            </span>
            <button type="button" class="go" @click="router.push(`/doctor/${order.doctorId}`)">医生主页</button>
          </dd>
        </div>
        <div class="row">
          <dt>宠物</dt>
          <dd class="pet">
            <el-avatar :size="28" shape="square" :src="order.petAvatar" alt="">{{ order.petName?.[0] }}</el-avatar>
            <b>{{ order.petName }}</b>
            <em>{{ petMeta }}</em>
          </dd>
        </div>
        <div class="row">
          <dt>下单时间</dt>
          <dd class="tnum">{{ toDateTime(order.createTime) }}</dd>
        </div>
        <div class="row">
          <dt>预约单号</dt>
          <dd class="mono">{{ order.orderNo }}</dd>
        </div>
      </dl>

      <h2 class="sec">
        病情描述
        <em v-if="images.length">附图 {{ images.length }} 张</em>
      </h2>
      <p class="sym" :class="{ empty: !order.symptom }">{{ order.symptom || '未填写病情描述' }}</p>
      <ImageGallery v-if="images.length" class="pics" :urls="images" :size="76" label="病情描述附图" />

      <h2 class="sec">费用</h2>
      <div class="fee">
        <span>问诊费</span>
        <div class="amt">
          <b>{{ money(order.amount) }}</b>
          <em :class="payLineTone">{{ payLine }}</em>
        </div>
      </div>

      <template v-if="record">
        <h2 class="sec">
          本次病历
          <em v-if="order.isReviewed === 1">已评价</em>
        </h2>
        <div class="rec">
          <div class="rec-row"><span>诊断</span><b>{{ record.diagnosis || '—' }}</b></div>
          <div class="rec-row"><span>就诊日期</span><b>{{ toDate(record.visitDate) }}</b></div>
          <button type="button" class="rec-link" @click="router.push(`/record/${record.id}`)">查看完整病历与处方 →</button>
        </div>
      </template>

      <div v-if="ops.length" class="ops">
        <el-button
          v-for="op in ops"
          :key="op.label"
          :type="op.kind === 'primary' ? 'primary' : 'default'"
          size="large"
          :class="{ 'op-main': op.kind === 'primary' }"
          @click="op.run()"
        >{{ op.label }}</el-button>
      </div>

      <div v-if="needPay && !overdue" class="pay-bar pc-cta-bar" role="region" aria-label="预约支付">
        <div class="sum">
          <span>待支付</span>
          <b>{{ money(order.amount) }}</b>
          <span v-if="paymentAllowed" class="cd">剩余 {{ countdown }}</span>
          <span v-else class="cd over">缺少支付截止时间</span>
        </div>
        <div class="pay-actions">
          <el-button link :disabled="busy" @click="cancel">取消预约</el-button>
          <el-button
            type="primary"
            size="large"
            :loading="action === 'pay'"
            :disabled="busy || !paymentAllowed"
            @click="pay"
          >立即支付</el-button>
        </div>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无预约信息" />
  </div>
</template>

<style scoped>
/* 数字用等宽数字形，日期和金额不跳字；字族仍回落项目现有的中文栈 */
.detail {
  --font-num: 'Bahnschrift', 'DIN Alternate', 'Inter', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  padding-top: 20px;
}

.hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; }
.date { display: flex; align-items: flex-end; gap: 8px; min-width: 0; }
.yr {
  align-self: flex-start; padding-top: 4px;
  font-size: 12.5px; color: var(--color-muted); letter-spacing: 0.06em;
}
.date b {
  font-family: var(--font-num);
  font-size: 64px; line-height: 0.82; font-weight: 700;
  letter-spacing: -0.03em; font-variant-numeric: tabular-nums;
}
.wd { padding-bottom: 3px; font-size: 14px; color: var(--color-muted); }
.slot { text-align: right; line-height: 1.45; padding-bottom: 3px; }
.slot b { display: block; font-size: 16px; font-weight: 700; }
.slot span { font-size: 13px; color: var(--color-muted); font-variant-numeric: tabular-nums; }

.statusline {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px 10px;
  margin-top: 18px; padding-bottom: 14px;
  border-bottom: 1px solid var(--color-hairline);
}
.statusline .sub { font-size: 13px; color: var(--color-muted); }
.queue { margin-left: auto; font-size: 13px; color: var(--color-muted); white-space: nowrap; }
.queue b {
  margin-left: 4px;
  font-family: var(--font-num); font-size: 20px; font-weight: 600; color: var(--color-ink);
  font-variant-numeric: tabular-nums;
}

/* 行程轴：四段等分，圆点居中，连线只在节点之间 */
.rail {
  display: grid; grid-template-columns: repeat(var(--steps, 4), 1fr);
  margin: 18px 0 0; padding: 0; list-style: none;
}
.rail li {
  position: relative; padding-top: 24px;
  text-align: center; font-size: 12px; line-height: 1.4; color: var(--color-muted);
}
.rail li::before {
  content: ''; position: absolute; top: 4px; left: 50%; width: 100%; height: 1px;
  background: var(--color-hairline);
}
.rail li:last-child::before { display: none; }
.rail li::after {
  content: ''; position: absolute; top: 0; left: calc(50% - 5px);
  width: 10px; height: 10px; border-radius: 50%; box-sizing: border-box;
  background: var(--color-canvas); border: 1px solid var(--color-border-strong);
  transition: background-color .2s var(--ease), border-color .2s var(--ease), box-shadow .2s var(--ease);
}
.rail li.done { color: var(--color-ink); }
.rail li.done::before { background: var(--color-ink); }
.rail li.done::after { background: var(--color-ink); border-color: var(--color-ink); }
.rail li.now { font-weight: 600; color: var(--now-color, var(--color-warning)); }
.rail li.now::after {
  background: var(--now-color, var(--color-warning));
  border-color: var(--now-color, var(--color-warning));
  box-shadow: 0 0 0 4px var(--now-halo, var(--color-warning-soft));
}
/* 终态是这张单子的结论，比中间步骤更重，不能用更淡的灰 */
.rail li.stop { font-weight: 700; color: var(--color-ink); }
.rail li.stop::after { background: var(--color-ink); border-color: var(--color-ink); }
.rail li.bad { font-weight: 600; color: var(--color-danger-text); }
.rail li.bad::after { background: var(--color-danger-text); border-color: var(--color-danger-text); }
.rail.t-warning { --now-color: var(--color-warning); --now-halo: var(--color-warning-soft); }
.rail.t-ongoing { --now-color: var(--color-ongoing); --now-halo: var(--color-ongoing-soft); }
.rail.t-success { --now-color: var(--color-success); --now-halo: var(--color-success-soft); }

/* 状态说明：底色只承担语义，文字都用同色系深色 */
.notice {
  margin: 16px 0 0; padding: 12px 14px;
  border-radius: var(--radius-sm);
  font-size: 14px; line-height: 1.6; overflow-wrap: anywhere;
}
.notice b { display: block; font-size: 14px; font-weight: 600; }
.notice b + span { display: block; margin-top: 3px; }
.notice.t-warning { background: var(--color-warning-soft); color: var(--color-warning); }
.notice.t-danger { background: var(--color-danger-soft); color: var(--color-danger-text); }
.notice.t-terminated { background: var(--color-terminated-soft); color: var(--color-terminated); }
.notice.t-quiet { background: var(--color-surface-soft); color: var(--color-body); }
.notice .cd {
  font-family: var(--font-num); font-weight: 600; font-variant-numeric: tabular-nums;
}
.notice-link {
  appearance: none; border: 0; background: none; padding: 6px 0; margin-left: 4px;
  font-family: inherit; font-size: 14px; font-weight: 500; color: var(--color-ink);
  text-decoration: underline; text-underline-offset: 3px; cursor: pointer;
}

.sec {
  display: flex; align-items: baseline; justify-content: space-between; gap: 12px;
  margin: 34px 0 0; padding-top: 12px;
  border-top: 1px solid var(--color-ink);
  font-size: 15px; font-weight: 700; letter-spacing: 0.03em;
}
.sec em {
  font-style: normal; font-size: 12.5px; font-weight: 400;
  color: var(--color-muted); letter-spacing: 0;
}

/* 终态/超时：结论占首屏主角位，日期退成一行说明 */
.hero-verdict { display: block; }
.verdict { display: flex; flex-direction: column; gap: 6px; }
.verdict b {
  font-size: 40px; line-height: 1.1; font-weight: 700; letter-spacing: -0.01em;
}
.verdict span { font-size: 14px; color: var(--color-muted); }
.verdict.t-danger b { color: var(--color-danger-text); }
.verdict.t-terminated b { color: var(--color-ink); }
.hero-verdict .when {
  display: flex; align-items: baseline; justify-content: space-between; gap: 12px;
  margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--color-hairline);
}
.hero-verdict .when b { font-size: 15px; font-weight: 600; }
.hero-verdict .when span { font-size: 13px; color: var(--color-muted); font-variant-numeric: tabular-nums; }

.facts { display: grid; gap: 0; margin: 2px 0 0; }
.facts .row {
  display: grid; grid-template-columns: 56px minmax(0, 1fr); gap: 10px;
  padding: 13px 0; border-bottom: 1px solid var(--color-hairline-soft);
}
.facts dt { padding-top: 2px; font-size: 12.5px; color: var(--color-muted); }
.facts dd { margin: 0; min-width: 0; font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.facts .doc { display: flex; align-items: center; gap: 10px; }
.doc .n { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.doc .n b { font-size: 15px; font-weight: 600; }
.doc .n em { font-style: normal; font-size: 12.5px; font-weight: 400; color: var(--color-muted); }
.doc .go {
  margin-left: auto; flex: none;
  appearance: none; border: 0; background: none; padding: 6px 0; cursor: pointer;
  font-family: inherit; font-size: 13px; font-weight: 500; color: var(--color-ink);
  text-decoration: underline; text-underline-offset: 3px;
}
.facts .pet { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 8px; }
.facts .pet em { font-style: normal; font-size: 12.5px; font-weight: 400; color: var(--color-muted); }
.facts .tnum { font-variant-numeric: tabular-nums; }
.facts .mono {
  font-size: 12.5px; font-weight: 500; color: var(--color-muted);
  letter-spacing: 0.02em; overflow-wrap: anywhere;
}

.sym {
  margin: 14px 0 0;
  font-size: 15px; line-height: 1.8; color: var(--color-body);
  white-space: pre-wrap; overflow-wrap: anywhere;
}
.sym.empty { color: var(--color-muted); }
.pics { margin-top: 14px; }

.rec-row {
  display: flex; justify-content: space-between; gap: 16px;
  padding: 13px 0; border-bottom: 1px solid var(--color-hairline-soft);
  font-size: 15px;
}
.rec-row span { flex: none; padding-top: 2px; font-size: 12.5px; color: var(--color-muted); }
.rec-row b { min-width: 0; text-align: right; overflow-wrap: anywhere; }
.rec-link {
  appearance: none; border: 0; background: none; padding: 12px 0; cursor: pointer;
  font-family: inherit; font-size: 14px; font-weight: 500; color: var(--color-ink);
  text-decoration: underline; text-underline-offset: 3px;
}

.fee {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  padding: 14px 0 4px; border-bottom: 1px solid var(--color-hairline-soft);
}
.fee > span { padding-top: 7px; font-size: 13px; color: var(--color-muted); }
.fee .amt { min-width: 0; text-align: right; }
.fee .amt b {
  display: block;
  font-family: var(--font-num); font-size: 26px; font-weight: 600;
  letter-spacing: 0.01em; font-variant-numeric: tabular-nums;
}
.fee .amt em { display: block; margin-top: 3px; font-style: normal; font-size: 12.5px; font-weight: 500; }
.fee .amt .muted { color: var(--color-muted); }

.ops { display: flex; flex-wrap: wrap; gap: 10px; margin: 28px 0 8px; }
.ops .el-button + .el-button { margin-left: 0; }
.ops .op-main { flex: 1 1 100%; }
.ops .el-button:not(.op-main) { flex: 1 1 140px; }

/* 位置、宽度上限与白底投影走公共 .pc-cta-bar；这里留高度与内部排布。 */
.pay-bar {
  min-height: var(--cta-bar-height);
  display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 12px 16px;
}
.sum { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.sum > span { font-size: 12px; color: var(--color-muted); }
.sum b {
  font-family: var(--font-num); font-size: 22px; font-weight: 600;
  font-variant-numeric: tabular-nums; overflow-wrap: anywhere;
}
.sum .cd { color: var(--color-warning); font-variant-numeric: tabular-nums; }
.sum .cd.over { color: var(--color-danger-text); }
.pay-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.pay-actions .el-button + .el-button { margin-left: 0; }

@media (min-width: 600px) {
  /* 宽屏保持单列记录表：字段值仍然右对齐，标签与值横跨整行，不会被拆成半截分隔线 */
  .facts .row { grid-template-columns: 96px minmax(0, 1fr); }
  /* 医生主页是这位医生的入口，宽屏上跟着姓名走，不推到整行最右端 */
  .doc .go { margin-left: 4px; }
}
@media (max-width: 359px) {
  .date b { font-size: 52px; }
  .facts .row { grid-template-columns: 52px minmax(0, 1fr); gap: 8px; }
  .sum b { font-size: 20px; }
}
@media (prefers-reduced-motion: no-preference) {
  .pay-bar { animation: payment-enter .2s var(--ease); }
  .pay-actions .el-button--primary { transition: transform .15s var(--ease), background-color .15s var(--ease); }
  .pay-actions .el-button--primary:active { transform: scale(.98); }
}
@keyframes payment-enter { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
</style>
