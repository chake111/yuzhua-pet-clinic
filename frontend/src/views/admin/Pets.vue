<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { petPage, removePet, updatePet } from '@/api/admin'
import { DEWORM, PET_GENDER, SPECIES, VACCINE, options, tagType, text } from '@/utils/dict'
import { toDate } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

/**
 * 后台宠物档案：按宠物名或主人昵称/手机号检索，纠正明显错误（编辑）、处理重复录入（删除）。
 * 与用户端的区别是不校验归属；字段校验仍走服务端同一套规则。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
// 查询时提交筛选快照，翻页和操作后的刷新沿用已提交条件。
let applied = { ...q }

const open = ref(false)
const editing = ref(null)
const busy = ref(false)
const form = ref({})

// 只提交档案字段；主人信息、id/系统时间由服务端维护
const EDITABLE = [
  'name', 'species', 'breed', 'gender', 'birthday', 'ageMonth', 'weight', 'color',
  'isNeutered', 'vaccineStatus', 'dewormFlag', 'avatar', 'allergy', 'medicalHistory', 'remark'
]

async function load() {
  loading.value = true
  try {
    const page = await petPage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      keyword: applied.keyword.trim() || undefined
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

function openEdit(row) {
  editing.value = row
  form.value = { ...row }
  open.value = true
}

async function submit() {
  if (!form.value.name?.trim()) return ElMessage.warning('请填写宠物昵称')
  if (!form.value.species) return ElMessage.warning('请选择物种')
  const payload = Object.fromEntries(EDITABLE.map(key => [key, form.value[key]]))
  payload.name = form.value.name.trim()
  // 显式 null 表示清空；省略该键才表示保持原值
  payload.birthday = payload.birthday || null
  payload.weight = payload.weight ?? null
  busy.value = true
  try {
    await updatePet(editing.value.id, payload)
    ElMessage.success('已保存')
    open.value = false
    load()
  } finally {
    busy.value = false
  }
}

async function del(row) {
  const ok = await ElMessageBox.confirm(
    `删除「${row.name}」的档案？`, '删除宠物档案',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  await removePet(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-input v-model="q.keyword" placeholder="宠物名 / 主人昵称 / 手机号" clearable class="w240"
                :prefix-icon="Search" @keyup.enter="search" />
      <el-button :loading="loading" @click="search">查询</el-button>
      <div class="sp" />
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>宠物档案</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="没有符合条件的宠物">
        <el-table-column label="宠物" min-width="150">
          <template #default="{ row }">
            <div class="who">
              <el-avatar :size="32" :src="row.avatar">{{ row.name?.[0] }}</el-avatar>
              <div>
                <b>{{ row.name }}</b>
                <div class="pc-muted">{{ row.species }}<span v-if="row.breed">   {{ row.breed }}</span></div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="性别 / 月龄" width="110">
          <template #default="{ row }">
            {{ text(PET_GENDER, row.gender) }}<span class="pc-muted">   {{ row.ageMonth ?? '—' }} 月</span>
          </template>
        </el-table-column>
        <el-table-column label="免疫" width="96">
          <template #default="{ row }">
            <StatusChip :tone="tagType(VACCINE, row.vaccineStatus)">{{ text(VACCINE, row.vaccineStatus) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="主人" min-width="150">
          <template #default="{ row }">
            <div>{{ row.userNickname || '—' }}</div>
            <div class="pc-muted">{{ row.userPhone || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="健康摘要" min-width="180">
          <template #default="{ row }">
            <span class="pc-muted">{{ row.allergy || row.medicalHistory || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="录入" width="110">
          <template #default="{ row }">{{ toDate(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-dialog v-model="open" :title="`编辑   ${editing?.name || ''}`" width="640px" top="5vh">
      <el-form :model="form" label-width="86px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="昵称" required><el-input v-model="form.name" maxlength="20" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="物种" required>
              <el-select v-model="form.species" class="full">
                <el-option v-for="s in SPECIES" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="品种"><el-input v-model="form.breed" maxlength="30" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="性别">
              <el-radio-group v-model="form.gender">
                <el-radio v-for="o in options(PET_GENDER)" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="出生日期">
              <el-date-picker v-model="form.birthday" type="date" value-format="YYYY-MM-DD" class="full" placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="月龄">
              <el-input-number v-model="form.ageMonth" :disabled="!!form.birthday" :min="0" :max="360" class="full" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="体重 kg">
              <el-input-number v-model="form.weight" :min="0" :max="200" :precision="2" class="full" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="毛色"><el-input v-model="form.color" maxlength="20" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="免疫状态">
              <el-select v-model="form.vaccineStatus" class="full">
                <el-option v-for="o in options(VACCINE)" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="驱虫">
              <el-select v-model="form.dewormFlag" class="full">
                <el-option v-for="o in options(DEWORM)" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="是否绝育">
              <el-switch v-model="form.isNeutered" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="头像链接"><el-input v-model="form.avatar" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="过敏史"><el-input v-model="form.allergy" maxlength="200" /></el-form-item>
        <el-form-item label="既往病史"><el-input v-model="form.medicalHistory" type="textarea" :rows="2" maxlength="500" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" /></el-form-item>
      </el-form>
      <p class="pc-muted note">填写出生日期后，月龄按出生日期计算。</p>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w240 { width: 240px; }
.who { display: flex; align-items: center; gap: 8px; }
.who b { font-size: 14px; }
.who .pc-muted { font-size: 12px; }
.full { width: 100%; }
.note { margin: 0; font-size: 12px; }
</style>
