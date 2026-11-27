<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { accept, appointmentDetail, finish, recordOfAppointment, saveRecord } from '@/api/admin'
import { APPT_STATUS, PERIOD, tagType, text } from '@/utils/dict'
import { money, toDate, toDateTime } from '@/utils/format'
import ImageGallery from '@/components/ImageGallery.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import StatusChip from '@/components/StatusChip.vue'
import LoadFailure from '@/components/LoadFailure.vue'

const route = useRoute()
const router = useRouter()
const id = computed(() => Number(route.params.orderId))
const order = ref(null)
const loading = ref(true)
const failed = ref(false)
const action = ref('')
const busy = computed(() => Boolean(action.value))
const saved = ref(false)
const finishOpen = ref(false)
const symptomsOpen = ref(true)
let loadRequest = 0

const blank = () => ({
  chiefComplaint: '', physicalExam: '', diagnosis: '', prescription: '',
  treatment: '', advice: '', nextVisitDate: '', images: ''
})
const form = reactive(blank())
// 原因: 固定字段顺序并将清空后的 null 与空串归一，保证脏状态比对稳定
const snapshot = (record = form) => JSON.stringify(Object.fromEntries(Object.keys(form).map(key => [key, record[key] || ''])))
const baseline = ref(snapshot())
const dirty = computed(() => snapshot() !== baseline.value)
const saveState = computed(() => {
  if (action.value === 'save') return '正在保存…'
  if (action.value === 'finish') return '正在结束就诊…'
  if (dirty.value) return '有未保存修改'
  return saved.value ? '病历已保存' : '病历尚未填写'
})

const imgs = computed(() => (order.value?.images || '').split(',').filter(Boolean))
// 原因: 后端白名单仅允许已接诊(1)与已完成(2)写病历，按钮显隐与该状态对齐
const canRecord = computed(() => order.value?.status === 1 || order.value?.status === 2)
const canAccept = computed(() => order.value?.status === 0 && order.value?.payStatus === 1)

async function load() {
  const current = ++loadRequest
  loading.value = true
  failed.value = false
  try {
    const [appointment, record] = await Promise.all([appointmentDetail(id.value), recordOfAppointment(id.value)])
    if (current !== loadRequest) return
    order.value = appointment
    Object.keys(form).forEach(key => { form[key] = record?.[key] || '' })
    saved.value = Boolean(record)
    baseline.value = snapshot()
  } catch {
    if (current === loadRequest) failed.value = true
  } finally {
    if (current === loadRequest) loading.value = false
  }
}

async function doAccept() {
  if (busy.value || !canAccept.value) return
  action.value = 'accept'
  try {
    await accept(id.value)
    order.value.status = 1
    ElMessage.success('已开始接诊')
  } catch {
    // 原因: 请求层已提示失败原因，保留当前表单内容以便重试
  } finally {
    action.value = ''
  }
}

async function persistRecord() {
  if (!form.diagnosis.trim()) {
    ElMessage.warning('请填写诊断结论')
    return false
  }
  const submitted = snapshot()
  const payload = JSON.parse(submitted)
  const record = await saveRecord(id.value, { ...payload, nextVisitDate: payload.nextVisitDate || null })
  // 原因: 基线仅确认本次提交的内容，异步上传等请求期间的新增修改仍保留为脏状态
  baseline.value = snapshot(record)
  if (snapshot() === submitted) Object.assign(form, JSON.parse(baseline.value))
  saved.value = true
  return true
}

async function save() {
  if (busy.value || !canRecord.value) return
  action.value = 'save'
  try {
    if (await persistRecord()) ElMessage.success(dirty.value ? '病历已保存，还有新的修改待保存' : '病历已保存')
  } catch {
    // 原因: 保存失败时不修改基线也不清空输入，保留未落库修改供重试
  } finally {
    action.value = ''
  }
}

async function doFinish(mode) {
  if (busy.value || order.value?.status !== 1) return
  action.value = 'finish'
  const discarded = snapshot()
  try {
    if (mode === 'save') {
      if (!await persistRecord()) return
      if (dirty.value) {
        ElMessage.warning('还有新的修改待保存，请确认后再结束就诊')
        return
      }
    }
    await finish(id.value)
    order.value.status = 2
    // 原因: 仅在明确放弃且请求等待期间无新改动时才回退至已存基线
    if (mode === 'discard' && snapshot() === discarded) Object.assign(form, JSON.parse(baseline.value))
    finishOpen.value = false
    ElMessage.success(dirty.value ? '就诊已结束，当前修改仍未保存' : '本次就诊已完成')
  } catch {
    // 原因: 保存或结束失败时保留输入与弹窗，允许继续编辑或重试
  } finally {
    action.value = ''
  }
}

async function confirmLeave() {
  if (busy.value) {
    ElMessage.warning('正在处理，请稍候再离开')
    return false
  }
  if (!dirty.value) return true
  return ElMessageBox.confirm('病历有未保存的修改，离开后将丢失。确定离开？', '离开接诊工作台', {
    type: 'warning', confirmButtonText: '放弃修改并离开', cancelButtonText: '继续编辑',
    closeOnClickModal: false
  }).then(() => true).catch(() => false)
}

function beforeUnload(event) {
  if (!dirty.value && !busy.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => to.params.orderId === from.params.orderId || confirmLeave())
watch(id, () => {
  order.value = null
  Object.assign(form, blank())
  baseline.value = snapshot()
  saved.value = false
  finishOpen.value = false
  symptomsOpen.value = true
  load()
})
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  load()
})
onBeforeUnmount(() => {
  loadRequest++
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="page" v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <article v-else-if="order" class="record-sheet" aria-label="门诊病历">
      <header class="sheet-heading">
        <div>
          <h1>门诊病历</h1>
          <p class="sheet-subtitle">{{ order.departmentName }} · {{ order.doctorName }}医生</p>
        </div>
        <div class="visit-meta">
          <StatusChip :tone="tagType(APPT_STATUS, order.status)">{{ text(APPT_STATUS, order.status) }}</StatusChip>
          <p>{{ toDate(order.appointmentDate) }} · {{ text(PERIOD, order.period) }} {{ order.startTime?.slice(0, 5) }} · {{ order.queueNo }} 号</p>
        </div>
      </header>
      <div class="patient-line">
        <b>{{ order.petName || '未命名宠物' }}</b>
        <span>{{ order.petSpecies }} · {{ order.petBreed || '品种未填' }}</span>
        <span class="owner">主人 {{ order.userNickname || '未命名用户' }} · {{ order.userPhone || '电话未填' }}</span>
      </div>
      <section class="symptoms" aria-labelledby="symptoms-title">
        <div class="symptoms-heading">
          <h2 id="symptoms-title">主人描述</h2>
          <el-button text :aria-expanded="symptomsOpen" aria-controls="symptoms-content" @click="symptomsOpen = !symptomsOpen">
            {{ symptomsOpen ? '收起' : '展开病情' }}
            <el-icon class="disclosure-icon" :class="{ expanded: symptomsOpen }"><ArrowDown /></el-icon>
          </el-button>
        </div>
        <el-collapse-transition>
          <div v-show="symptomsOpen" id="symptoms-content">
            <p class="sym">{{ order.symptom || '未填写病情描述' }}</p>
            <ImageGallery v-if="imgs.length" class="pics" :urls="imgs" :size="76" label="病情描述附图" />
          </div>
        </el-collapse-transition>
      </section>
      <fieldset class="record-fields" :disabled="busy || !canRecord">
        <el-form :model="form" label-position="top" :disabled="busy || !canRecord">
        <section class="record-section" aria-labelledby="exam-title">
          <h2 id="exam-title">问诊与检查</h2>
          <div class="field-pair">
          <el-form-item label="主诉"><el-input v-model="form.chiefComplaint" type="textarea" :rows="2" maxlength="500" /></el-form-item>
          <el-form-item label="体格检查"><el-input v-model="form.physicalExam" type="textarea" :rows="2" maxlength="500" /></el-form-item>
          </div>
        </section>
        <section class="record-section" aria-labelledby="diagnosis-title">
          <h2 id="diagnosis-title">诊断</h2>
          <el-form-item label="诊断结论" required><el-input v-model="form.diagnosis" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        </section>
        <section class="record-section" aria-labelledby="treatment-title">
          <h2 id="treatment-title">治疗</h2>
          <div class="field-pair">
          <el-form-item label="处方 / 用药建议"><el-input v-model="form.prescription" type="textarea" :rows="2" maxlength="1000" placeholder="药名、剂量、用法、疗程" /></el-form-item>
          <el-form-item label="处置措施"><el-input v-model="form.treatment" type="textarea" :rows="2" maxlength="500" /></el-form-item>
          </div>
        </section>
        <section class="record-section" aria-labelledby="advice-title">
          <h2 id="advice-title">医嘱</h2>
          <el-form-item label="照护与复诊建议"><el-input v-model="form.advice" type="textarea" :rows="2" maxlength="500" placeholder="饮食、复诊、注意事项" /></el-form-item>
        </section>
        <div class="record-extras">
          <el-form-item label="建议复诊日期">
            <el-date-picker v-model="form.nextVisitDate" type="date" value-format="YYYY-MM-DD" placeholder="选填" class="full" />
          </el-form-item>
          <el-form-item label="检查影像">
            <ImageUpload v-model="form.images" :max="6" kind="admin" />
          </el-form-item>
        </div>
        </el-form>
      </fieldset>
      <div class="record-meta">
        <span>预约于 {{ toDateTime(order.createTime) }} · 诊费 {{ money(order.amount) }}</span>
        <el-button link @click="router.push(`/admin/appointment/${id}`)">预约详情</el-button>
      </div>
      <footer class="record-toolbar">
        <span class="save-state" :class="{ unsaved: dirty, saved: saved && !dirty && !busy }" role="status">{{ canRecord ? saveState : '接诊后可填写病历' }}</span>
        <div class="rec-ops">
          <el-button v-if="canAccept" type="primary" :loading="action === 'accept'" :disabled="busy" @click="doAccept">开始接诊</el-button>
          <el-button v-else-if="order.status === 0" disabled>预约未支付</el-button>
          <el-button v-if="order.status === 1" :disabled="busy" @click="finishOpen = true">结束就诊</el-button>
          <el-button v-if="canRecord" type="primary" :loading="action === 'save'" :disabled="busy || (saved && !dirty)" @click="save">保存病历</el-button>
        </div>
      </footer>
    </article>

    <el-dialog v-model="finishOpen" title="结束本次就诊" width="540px" :show-close="!busy"
               :close-on-click-modal="!busy" :close-on-press-escape="!busy">
      <p v-if="dirty" class="finish-tip">病历有未保存的修改。可以保存后结束，或放弃本次修改后结束。</p>
      <p v-else class="finish-tip">{{ saved ? '确认结束本次就诊？病历之后仍可修订。' : '病历尚未填写，结束后仍可补写。确认结束本次就诊？' }}</p>
      <template #footer>
        <div class="finish-actions">
          <el-button :disabled="busy" @click="finishOpen = false">继续编辑</el-button>
          <el-button v-if="dirty" type="danger" plain :disabled="busy" @click="doFinish('discard')">放弃修改并结束</el-button>
          <el-button type="primary" :loading="action === 'finish'" :disabled="busy" @click="doFinish(dirty ? 'save' : 'finish')">{{ dirty ? '保存并结束' : '确认结束' }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.record-sheet { width: 100%; max-width: 1120px; margin: 0 auto; padding: 28px 36px 0; background: var(--color-canvas); border: 1px solid var(--color-hairline); animation: sheet-in .24s var(--ease); }
.sheet-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 22px; }
.sheet-heading h1 { margin: 0; font-size: 27px; line-height: 1.4; letter-spacing: 1px; }
.sheet-subtitle, .visit-meta p { margin: 6px 0 0; color: var(--color-muted); font-size: 13px; line-height: 1.7; }
.visit-meta { text-align: right; }
.patient-line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 20px; padding: 16px 0; border-top: 2px solid var(--color-ink); border-bottom: 1px solid var(--color-hairline); font-size: 14px; overflow-wrap: anywhere; }
.patient-line b { font-size: 21px; }
.owner { margin-left: auto; color: var(--color-muted); }
.symptoms { display: grid; grid-template-columns: 100px minmax(0, 1fr) auto; align-items: start; gap: 18px; padding: 16px 0; border-bottom: 1px solid var(--color-hairline); }
.symptoms-heading { display: contents; }
.symptoms h2 { margin: 0; font-size: 13px; font-weight: 500; color: var(--color-muted); }
.symptoms-heading h2 { grid-column: 1; padding-top: 4px; }
.symptoms-heading .el-button { grid-column: 3; grid-row: 1; padding: 8px 0 8px 12px; }
#symptoms-content { grid-column: 2; grid-row: 1; min-width: 0; }
.disclosure-icon { margin-left: 6px; transition: transform .2s var(--ease); }
.disclosure-icon.expanded { transform: rotate(180deg); }
.sym { margin: 0; font-size: 16px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.pics { margin-top: 10px; }
.record-section { display: grid; grid-template-columns: 100px minmax(0, 1fr); align-items: start; gap: 18px; padding: 18px 0; border-bottom: 1px solid var(--color-hairline-soft); }
.record-section h2 { margin: 27px 0 0; font-size: 15px; line-height: 1.6; }
.field-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.record-extras { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 32px; padding: 20px 0 12px; }
.record-meta { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; align-items: center; padding: 0 0 16px; color: var(--color-muted); font-size: 13px; line-height: 1.7; }
.record-toolbar { position: sticky; bottom: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 0 max(16px, env(safe-area-inset-bottom)); background: var(--color-canvas); border-top: 1px solid var(--color-hairline); }
.save-state { font-size: 13px; color: var(--color-muted); transition: color .18s var(--ease); }
.save-state.unsaved { color: var(--color-warning); }
.save-state.saved { color: var(--color-success); }
.record-fields { min-width: 0; margin: 0; padding: 0; border: 0; }
.record-fields :deep(.el-form-item) { min-width: 0; margin-bottom: 0; }
.record-fields :deep(.el-form-item__label) { font-size: 13px; line-height: 1.6; padding: 0; margin-bottom: 8px; color: var(--color-muted); }
.record-fields :deep(.el-textarea__inner) { padding: 10px 12px; border-radius: var(--radius-xs); background: #fcfcfc; font-size: 15px; line-height: 1.7; }
.record-fields :deep(.el-textarea__inner), .record-fields :deep(.el-input__wrapper) { scroll-margin-block: 24px 110px; }
.full { width: 100%; }
.full.el-date-editor { width: 100%; }
.rec-ops, .finish-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.rec-ops :deep(.el-button + .el-button), .finish-actions :deep(.el-button + .el-button) { margin-left: 0; }
.rec-ops .el-button { min-height: 40px; transition: background-color .18s var(--ease), transform .15s var(--ease); }
.rec-ops .el-button:active:not(:disabled) { transform: scale(.98); }
/* 同上：后台自己绑实心主按钮，只引用全局动作色令牌，不写死色阶。 */
.record-sheet :deep(.el-button--primary) { --el-button-bg-color: var(--color-action); --el-button-border-color: var(--color-action); --el-button-hover-bg-color: var(--color-action-hover); --el-button-hover-border-color: var(--color-action-hover); --el-button-active-bg-color: var(--color-action-active); --el-button-active-border-color: var(--color-action-active); }
.finish-actions { justify-content: flex-end; }
.finish-tip { margin: 0; line-height: 1.8; }
@keyframes sheet-in { from { opacity: .6; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 1100px) {
  .record-sheet { padding: 24px 24px 0; }
  .record-section { grid-template-columns: 82px minmax(0, 1fr); gap: 14px; }
  .field-pair { gap: 16px; }
}
@media (max-width: 760px) {
  .record-sheet { padding: 20px 16px 0; }
  .sheet-heading { align-items: flex-start; flex-direction: column; gap: 10px; margin-bottom: 16px; }
  .sheet-heading h1 { font-size: 23px; }
  .visit-meta { text-align: left; }
  .owner { width: 100%; margin-left: 0; }
  .symptoms { display: block; }
  .symptoms-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 4px; }
  .record-section, .field-pair, .record-extras { grid-template-columns: minmax(0, 1fr); gap: 16px; }
  .record-section h2 { margin: 0; }
  .record-toolbar { flex-wrap: wrap; padding-top: 12px; gap: 8px; }
  .rec-ops { width: 100%; }
  .rec-ops .el-button { flex: 1; }
  .finish-actions .el-button { flex: 1 1 100%; }
}
@media (prefers-reduced-motion: reduce) {
  .record-sheet { animation: none; }
  .disclosure-icon, .save-state, .rec-ops .el-button { transition: none; }
}
</style>
