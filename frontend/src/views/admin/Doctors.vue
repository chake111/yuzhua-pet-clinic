<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import {
  createDoctor, departmentList, doctorDetail, doctorPage, refreshDoctorStats, removeDoctor, toggleDoctor, updateDoctor
} from '@/api/admin'
import { ONLINE, WORK_STATUS, options, tagType, text } from '@/utils/dict'
import { money, toDate } from '@/utils/format'
import { hiddenColumns } from '@/utils/tableDensity'
import StatusChip from '@/components/StatusChip.vue'

/**
 * 医生档案。评分、接诊量、在线状态是聚合出来的，表单不提供编辑入口，
 * 只能「刷新统计」让后端重算一次。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const depts = ref([])
const q = reactive({ pageNum: 1, pageSize: 20, keyword: '', departmentId: null, status: null, sort: '' })
// 查询时提交筛选快照，翻页和操作后的刷新沿用已提交条件。
let applied = { ...q }

const open = ref(false)
const editing = ref(null)
const busy = ref(false)
const form = ref({})

const blank = () => ({
  name: '', gender: 1, phone: '', avatar: '', title: '主治医师', departmentId: null,
  specialty: '', introduction: '', workYears: 3, consultationFee: 30, status: 1, sort: 99
})

const specialtyTags = ref([])

async function load() {
  loading.value = true
  try {
    const page = await doctorPage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      keyword: applied.keyword.trim() || undefined,
      departmentId: applied.departmentId ?? undefined,
      status: applied.status ?? undefined,
      sort: applied.sort || undefined
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  applied = { ...q }
  q.pageNum = 1
  load()
}

function openCreate() {
  editing.value = null
  form.value = { ...blank(), departmentId: depts.value[0]?.id }
  specialtyTags.value = []
  open.value = true
}

// 编辑前拉详情：列表行是 DoctorVo（手机号被屏蔽），直接回填会把手机号冲成空
let editSeq = 0

async function openEdit(row) {
  const seq = ++editSeq
  let detail
  try {
    detail = await doctorDetail(row.id)
  } catch {
    // 请求失败不打开可提交的空白表单，提示由请求层负责
    return
  }
  if (seq !== editSeq) return // 快速切换医生：旧响应不得覆盖新编辑对象
  editing.value = row
  form.value = { ...blank(), ...detail }
  specialtyTags.value = (detail.specialty || '').split(',').filter(Boolean)
  open.value = true
}

async function submit() {
  if (!form.value.name?.trim()) return ElMessage.warning('请填写医生姓名')
  if (!form.value.departmentId) return ElMessage.warning('请选择所属科室')
  const payload = { ...form.value, specialty: specialtyTags.value.join(','), name: form.value.name.trim() }
  delete payload.rating
  delete payload.reviewCount
  delete payload.appointmentCount
  delete payload.onlineStatus
  delete payload.createTime
  busy.value = true
  try {
    if (editing.value) await updateDoctor(editing.value.id, payload)
    else await createDoctor(payload)
    ElMessage.success('已保存')
    open.value = false
    load()
  } finally {
    busy.value = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  await toggleDoctor(row.id, next)
  ElMessage.success(next === 1 ? '已恢复出诊' : '已停诊，用户端不再展示')
  load()
}

async function refresh(row) {
  await refreshDoctorStats(row.id)
  ElMessage.success('统计已重算')
  load()
}

async function del(row) {
  const ok = await ElMessageBox.confirm(
    `删除「${row.name}」？有关联排班、业务记录或后台账号时，请使用停诊保留档案。`, '删除医生',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await removeDoctor(row.id)
  ElMessage.success('已删除')
  load()
}

// 十列合计 1112px，1440 的表格可用宽 1142，1280 只剩 982，1152 只剩 854。
// 与其把列压到换行，不如按容器实际宽度收起次要列（规则见 utils/tableDensity.js）。
// 用容器宽度而不是 window.innerWidth：后台可用宽 = 视口 − 侧栏 − 内外边距，
// 侧栏在窄屏还会自动折叠，这条关系不单调，写死视口阈值必然在边界上差几十像素。
const tableCard = ref(null)
const cardWidth = ref(0)
const hidden = computed(() => hiddenColumns(cardWidth.value))
// 最窄一档（医生+状态+操作）需要 450px。装不下时不再固定操作列，改为整表横向滚动——
// 固定列会永远盖住它左边那列，宁可能滚，也不要把状态压没。
const pinActions = computed(() => cardWidth.value >= 450)
let observer = null

onMounted(() => {
  observer = new ResizeObserver(([entry]) => { cardWidth.value = entry.contentRect.width })
  if (tableCard.value) {
    // 先同步量一次内容盒：否则首帧按 0 宽渲染成最窄档，下一帧才跳成 5 列，会闪一下
    const style = getComputedStyle(tableCard.value)
    cardWidth.value = tableCard.value.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
    observer.observe(tableCard.value)
  }
  departmentList().then((r) => (depts.value = r || []))
  load()
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-input v-model="q.keyword" placeholder="姓名 / 擅长 / 职称" clearable class="w200" :prefix-icon="Search"
                @keyup.enter="search" />
      <el-select v-model="q.departmentId" placeholder="全部科室" clearable class="w140">
        <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w120">
        <el-option v-for="o in options(WORK_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select v-model="q.sort" placeholder="默认序" clearable class="w120">
        <el-option label="评分优先" value="rating" />
        <el-option label="接诊量优先" value="appointment" />
      </el-select>
      <el-button :loading="loading" @click="search">查询</el-button>
      <div class="sp" />
      <el-button type="primary" @click="openCreate">新增医生</el-button>
    </div>

    <div ref="tableCard" class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>医生档案</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="没有符合条件的医生">
        <el-table-column label="医生" min-width="160">
          <template #default="{ row }">
            <div class="who">
              <el-avatar :size="32" :src="row.avatar">{{ row.name?.[0] }}</el-avatar>
              <div>
                <b>{{ row.name }}</b>
                <div class="pc-muted">{{ row.title }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!hidden.has('department')" label="科室" min-width="96" prop="departmentName" />
        <el-table-column v-if="!hidden.has('specialty')" label="擅长" min-width="168">
          <template #default="{ row }">
            <div class="sp-tags">
              <StatusChip v-for="t in (row.specialty || '').split(',').filter(Boolean)" :key="t" tone="plain">{{ t }}</StatusChip>
              <span v-if="!row.specialty" class="pc-muted">—</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!hidden.has('experience')" label="经验" width="72">
          <template #default="{ row }">{{ row.workYears || 0 }} 年</template>
        </el-table-column>
        <el-table-column v-if="!hidden.has('fee')" label="问诊费" width="86">
          <template #default="{ row }">{{ money(row.consultationFee) }}</template>
        </el-table-column>
        <el-table-column v-if="!hidden.has('rating')" label="评分" width="92">
          <template #default="{ row }">
            <span class="sc">{{ Number(row.rating).toFixed(1) }}</span>
            <span class="pc-muted">({{ row.reviewCount || 0 }})</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!hidden.has('appointments')" label="接诊" width="72" prop="appointmentCount" />
        <el-table-column v-if="!hidden.has('online')" label="在线" width="76">
          <template #default="{ row }">
            <StatusChip :tone="row.onlineStatus === 1 ? 'success' : 'terminated'">
              {{ text(ONLINE, row.onlineStatus) }}
            </StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <StatusChip :tone="tagType(WORK_STATUS, row.status)">{{ text(WORK_STATUS, row.status) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" :fixed="pinActions ? 'right' : false">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <!-- 停诊/出诊是动作，不是状态：状态色留给「在线」「状态」两列，这里保持中性文字链 -->
            <el-button link size="small" @click="toggle(row)">
              {{ row.status === 1 ? '停诊' : '出诊' }}
            </el-button>
            <el-button link size="small" @click="refresh(row)">更新统计</el-button>
            <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-dialog v-model="open" :title="editing ? `编辑   ${editing.name}` : '新增医生'" width="620px" top="5vh">
      <el-form :model="form" label-width="86px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="姓名" required><el-input v-model="form.name" maxlength="20" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="所属科室" required>
              <el-select v-model="form.departmentId" class="full">
                <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="职称"><el-input v-model="form.title" maxlength="20" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="从业年限"><el-input-number v-model="form.workYears" :min="0" :max="60" class="full" controls-position="right" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="问诊费"><el-input-number v-model="form.consultationFee" :min="0" :max="9999" :precision="2" class="full" controls-position="right" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="性别">
              <el-radio-group v-model="form.gender"><el-radio :value="1">男</el-radio><el-radio :value="2">女</el-radio></el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.phone" maxlength="11" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="999" class="full" controls-position="right" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio v-for="o in options(WORK_STATUS)" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="擅长领域">
          <el-select v-model="specialtyTags" multiple filterable allow-create default-first-option
                     placeholder="输入后回车添加，如 猫内科 / 皮肤病" class="full" />
        </el-form-item>
        <el-form-item label="头像链接"><el-input v-model="form.avatar" placeholder="请输入图片链接（选填）" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.introduction" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
        <!-- 列表里删掉了「录入」列（十列塞不进 1440），建档时间在这里保留可查 -->
        <el-form-item v-if="editing && form.createTime" label="录入时间">
          <span class="pc-muted">{{ toDate(form.createTime) }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w120 { width: 120px; }
.w140 { width: 140px; }
.w200 { width: 200px; }
.who { display: flex; align-items: center; gap: 8px; }
.who b { font-size: 14px; }
.who .pc-muted { font-size: 12px; }
.sp-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.sc { color: var(--color-warning); font-weight: 600; }
.full { width: 100%; }
</style>
