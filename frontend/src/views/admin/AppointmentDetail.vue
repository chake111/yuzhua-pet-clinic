<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ElMessageBox } from '@/utils/messageBox'
import { accept, appointmentDetail, cancel, finish, recordOfAppointment } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { APPT_STATUS, CANCEL_BY, CONSULT_TYPE, PAY_STATUS, PERIOD, tagType, text } from '@/utils/dict'
import { money, toDate, toDateTime } from '@/utils/format'
import ImageGallery from '@/components/ImageGallery.vue'
import StatusChip from '@/components/StatusChip.vue'
import LoadFailure from '@/components/LoadFailure.vue'

const route = useRoute()
const router = useRouter()
const store = useAdminStore()
const id = Number(route.params.id)
const row = ref(null)
const record = ref(null)
const loading = ref(true)
const failed = ref(false)
// 原因: 允许从评价管理页跳转进入，返回时优先回到来源页而非固定跳回预约列表
const returnTo = computed(() => typeof route.query.returnTo === 'string' && /^\/admin\/(?:appointments|reviews)(?:\?|$)/.test(route.query.returnTo) ? route.query.returnTo : '/admin/appointments')
const backLabel = computed(() => returnTo.value.startsWith('/admin/reviews') ? '返回评价管理' : '返回预约列表')

const images = computed(() => (row.value?.images || '').split(',').filter(Boolean))
const canAccept = computed(() => store.isDoctor && row.value?.status === 0 && row.value?.payStatus === 1)
const canFinish = computed(() => store.isDoctor && row.value?.status === 1)

async function load() {
  loading.value = true
  failed.value = false
  try {
    row.value = await appointmentDetail(id)
    record.value = (await recordOfAppointment(id).catch(() => null)) || null
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

async function act(kind) {
  if (kind === 'accept') {
    await accept(id)
    ElMessage.success('已开始接诊')
  } else if (kind === 'finish') {
    await finish(id)
    ElMessage.success('本次就诊已完成')
  } else {
    const { value } = await ElMessageBox.prompt('已支付的预约取消后将标记为已退款', '取消预约单', {
      inputPlaceholder: '取消原因（选填）', confirmButtonText: '确认取消', cancelButtonText: '再想想'
    }).catch(() => ({}))
    if (value === undefined) return
    await cancel(id, { reason: value || undefined })
    ElMessage.success('预约单已取消')
  }
  load()
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <article v-else-if="row" class="appointment-sheet">
      <el-button text class="back-link" @click="router.push(returnTo)">← {{ backLabel }}</el-button>
      <header class="detail-heading">
        <div><h1>{{ row.petName || '宠物' }}的预约</h1><p class="pc-muted">预约单号 {{ row.orderNo }}</p></div>
        <div class="line">
          <StatusChip size="lg" :tone="tagType(APPT_STATUS, row.status)">{{ text(APPT_STATUS, row.status) }}</StatusChip>
          <StatusChip size="lg" :tone="tagType(PAY_STATUS, row.payStatus)">{{ text(PAY_STATUS, row.payStatus) }}</StatusChip>
        </div>
      </header>
      <div class="visit-strip">
        <div><span>就诊时间</span><b>{{ toDate(row.appointmentDate) }} · {{ text(PERIOD, row.period) }}</b><small>{{ row.startTime?.slice(0, 5) }}–{{ row.endTime?.slice(0, 5) }}</small></div>
        <div><span>接诊医生</span><b>{{ row.doctorName }} <small>{{ row.doctorTitle }}</small></b><small>{{ row.departmentName }}</small></div>
        <div><span>排队号</span><b>{{ row.queueNo ? row.queueNo + ' 号' : '—' }}</b><small>{{ text(CONSULT_TYPE, row.consultType) }}</small></div>
        <div><span>诊费</span><b>{{ money(row.amount) }}</b></div>
      </div>

      <div class="cols">
        <section class="pc-card">
          <h3>预约记录</h3>
          <div class="pc-kv"><span>预约创建时间</span><b>{{ toDateTime(row.createTime) }}</b></div>
          <div v-if="row.payTime" class="pc-kv"><span>支付时间</span><b>{{ toDateTime(row.payTime) }}</b></div>
          <div v-if="row.acceptTime" class="pc-kv"><span>接诊时间</span><b>{{ toDateTime(row.acceptTime) }}</b></div>
          <div v-if="row.finishTime" class="pc-kv"><span>完成时间</span><b>{{ toDateTime(row.finishTime) }}</b></div>
          <div v-if="row.cancelTime" class="pc-kv"><span>取消</span><b>{{ text(CANCEL_BY, row.cancelBy) }}   {{ toDateTime(row.cancelTime) }}</b></div>
          <div v-if="row.cancelReason" class="pc-kv"><span>取消原因</span><b>{{ row.cancelReason }}</b></div>
        </section>

        <section class="pc-card">
          <h3>宠物与主人</h3>
          <div class="pc-kv"><span>主人</span><b>{{ row.userNickname || '—' }}</b></div>
          <div class="pc-kv"><span>联系电话</span><b>{{ row.userPhone || '—' }}</b></div>
          <div class="pc-kv"><span>就诊宠物</span><b><el-avatar :size="28" shape="square" :src="row.petAvatar" class="pa">{{ row.petName?.[0] }}</el-avatar>{{ row.petName }}（{{ row.petSpecies }}   {{ row.petBreed || '品种未填' }}）</b></div>
        </section>
      </div>

      <section class="pc-card">
        <h3>病情描述</h3>
        <p class="sym">{{ row.symptom || '未填写病情描述' }}</p>
        <ImageGallery v-if="images.length" class="pics" :urls="images" :size="88" label="病情描述附图" />
      </section>

      <section class="pc-card">
        <div class="rec-h">
          <h3>病历 / 处方</h3>
          <el-button v-if="store.isDoctor && row.status !== 3" link type="primary"
                     @click="router.push(`/admin/workbench/${id}`)">
            {{ record ? '修订病历' : '进入接诊工作台' }} →
          </el-button>
        </div>
        <template v-if="record">
          <div class="pc-kv"><span>病历号</span><b>{{ record.recordNo }}</b></div>
          <div class="pc-kv"><span>诊断结论</span><b>{{ record.diagnosis || '—' }}</b></div>
          <div v-if="record.prescription" class="pc-kv"><span>处方</span><b>{{ record.prescription }}</b></div>
          <el-button link type="primary" @click="router.push(`/admin/record/${record.id}`)">查看完整病历</el-button>
        </template>
        <p v-else class="pc-muted empty">暂无病历。</p>
      </section>

      <div v-if="row.status === 0 || canFinish" class="ops foot">
        <div class="sp" />
        <el-button v-if="row.status === 0" type="danger" plain @click="act('cancel')">取消预约单</el-button>
        <el-button v-if="canAccept" type="primary" @click="act('accept')">开始接诊</el-button>
        <el-button v-if="canFinish" @click="act('finish')">结束就诊</el-button>
      </div>
    </article>
  </div>
</template>

<style scoped>
.appointment-sheet { width: 100%; max-width: 1120px; margin: 0 auto; padding: 24px 36px 0; border: 1px solid var(--color-hairline); background: var(--color-canvas); }
.back-link { padding-left: 0; color: var(--color-muted); }
.detail-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 0 24px; border-bottom: 2px solid var(--color-ink); }
.detail-heading h1 { font-size: 27px; line-height: 1.4; margin: 0 0 8px; overflow-wrap: anywhere; }
.detail-heading p { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
.line { display: flex; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }
.visit-strip { display: grid; grid-template-columns: 1.4fr 1.3fr .7fr .6fr; gap: 24px; padding: 24px 0; border-bottom: 1px solid var(--color-hairline); }
.visit-strip > div { display: flex; flex-direction: column; gap: 6px; min-width: 0; overflow-wrap: anywhere; }
.visit-strip span, .visit-strip small { color: var(--color-muted); font-size: 13px; line-height: 1.7; font-weight: 400; }
.visit-strip b { font-size: 17px; font-weight: 600; line-height: 1.6; }
.cols { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 32px; border-bottom: 1px solid var(--color-hairline-soft); }
.appointment-sheet .pc-card { min-width: 0; padding: 24px 0; border: 0; border-radius: 0; box-shadow: none; background: transparent; }
.appointment-sheet > .pc-card { border-bottom: 1px solid var(--color-hairline-soft); }
h3 { margin: 0 0 16px; font-size: 16px; }
.pc-kv { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 16px; align-items: start; }
.pc-kv b { overflow-wrap: anywhere; white-space: pre-wrap; font-weight: 400; line-height: 1.8; font-size: 14px; text-align: left; }
.pc-kv > span { font-size: 13px; }
.pa { vertical-align: middle; margin-right: 8px; }
.sym { margin: 0; font-size: 16px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.pics { margin-top: 16px; }
.rec-h { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; margin-bottom: 12px; }
.rec-h h3 { margin: 0; }
.empty { margin: 0; font-size: 14px; line-height: 1.7; }
.foot { position: sticky; bottom: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 16px 0 max(16px, env(safe-area-inset-bottom)); background: var(--color-canvas); border-top: 1px solid var(--color-hairline); z-index: 2; }
.foot .sp { flex: 1; }
.foot-note { font-size: 13px; color: var(--color-muted); }
.foot .el-button { margin: 0; min-height: 40px; }
/* 后台不挂 body.client-mode，实心主按钮的深浅必须在这里自己绑；
   色值走全局动作色令牌，与用户端同一组，hover 接受 #c71e40 的色相微调。 */
.appointment-sheet :deep(.el-button--primary) { --el-button-bg-color: var(--color-action); --el-button-border-color: var(--color-action); --el-button-hover-bg-color: var(--color-action-hover); --el-button-hover-border-color: var(--color-action-hover); }
@media (max-width: 1100px) {
  .appointment-sheet { padding: 20px 24px 0; }
  .visit-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .cols { gap: 24px; }
  .pc-kv { grid-template-columns: 80px minmax(0, 1fr); gap: 10px; }
}
@media (max-width: 760px) {
  .appointment-sheet { padding: 16px 16px 0; }
  .detail-heading { align-items: flex-start; flex-direction: column; padding-top: 12px; }
  .detail-heading h1 { font-size: 23px; }
  .cols { grid-template-columns: minmax(0, 1fr); gap: 0; }
  .cols > .pc-card { padding: 16px 0; }
  .cols > .pc-card + .pc-card { border-top: 1px solid var(--color-hairline-soft); }
  .visit-strip { gap: 20px 16px; }
  .visit-strip b { font-size: 15px; }
  .foot-note { width: 100%; }
  .foot .sp { display: none; }
  .foot .el-button { flex: 1; }
}
</style>
