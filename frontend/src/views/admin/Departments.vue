<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ElMessageBox } from '@/utils/messageBox'
import {
  createDepartment, departmentPage, removeDepartment, toggleDepartment, updateDepartment
} from '@/api/admin'
import { ACCOUNT_STATUS, options, tagType, text } from '@/utils/dict'
import { toDate } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'
import DeptIcon from '@/components/DeptIcon.vue'
import { DEPT_ICONS } from '@/utils/deptIcons'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNum: 1, pageSize: 20 })

const open = ref(false)
const editing = ref(null)
const busy = ref(false)
const blank = () => ({ name: '', code: '', icon: 'stethoscope', description: '', sort: 99, status: 1 })
const form = ref(blank())

async function load() {
  loading.value = true
  try {
    const page = await departmentPage({ pageNum: q.pageNum, pageSize: q.pageSize })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = blank()
  open.value = true
}

function openEdit(row) {
  editing.value = row
  form.value = { ...blank(), ...row }
  open.value = true
}

async function submit() {
  if (!form.value.name?.trim()) return ElMessage.warning('请填写科室名称')
  if (!form.value.code?.trim()) return ElMessage.warning('请填写科室编码')
  busy.value = true
  try {
    if (editing.value) await updateDepartment(editing.value.id, { ...form.value })
    else await createDepartment({ ...form.value })
    ElMessage.success('已保存')
    open.value = false
    load()
  } finally {
    busy.value = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  await toggleDepartment(row.id, next)
  ElMessage.success(next === 1 ? '已启用' : '已停用，用户端不再展示')
  load()
}

async function del(row) {
  const ok = await ElMessageBox.confirm(`确定删除科室「${row.name}」？有关联医生或历史业务时，请使用停用保留科室。`,
    '删除科室', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    .then(() => true).catch(() => false)
  if (!ok) return
  await removeDepartment(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <div class="sp" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新增科室</el-button>
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>科室列表</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="还没有科室">
        <el-table-column label="图标" width="70">
          <template #default="{ row }">
            <span class="ico"><DeptIcon :name="row.icon" :size="24" /></span>
          </template>
        </el-table-column>
        <el-table-column label="科室" min-width="130">
          <template #default="{ row }"><b>{{ row.name }}</b></template>
        </el-table-column>
        <el-table-column label="编码" min-width="110">
          <template #default="{ row }"><span class="code">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="说明" min-width="240">
          <template #default="{ row }"><span class="pc-muted">{{ row.description || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="排序" width="80" prop="sort" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusChip :tone="tagType(ACCOUNT_STATUS, row.status)">{{ text(ACCOUNT_STATUS, row.status) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="创建" width="110">
          <template #default="{ row }">{{ toDate(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small" @click="toggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-dialog v-model="open" :title="editing ? `编辑   ${editing.name}` : '新增科室'" width="520px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="20" /></el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="form.code" maxlength="20" placeholder="如 NEIKE，全局唯一" />
        </el-form-item>
        <el-form-item label="图标">
          <div class="pick">
            <button v-for="e in DEPT_ICONS" :key="e.key" type="button" class="em"
                    :class="{ on: form.icon === e.key }" :aria-label="e.label" :aria-pressed="form.icon === e.key"
                    @click="form.icon = e.key">
              <DeptIcon :name="e.key" :size="28" />
              <span>{{ e.label }}</span>
            </button>
          </div>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="120" show-word-limit /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="999" controls-position="right" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="o in options(ACCOUNT_STATUS)" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
          </el-radio-group>
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
.ico { display: grid; place-items: center; }
.code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px; }
.pick { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.em {
  height: 62px; padding: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 5px; border: 1px solid var(--color-hairline); border-radius: var(--radius-sm);
  background: var(--color-canvas); color: var(--color-body); cursor: pointer;
  font-size: 11px; line-height: 1.2; white-space: nowrap;
}
.em span { color: var(--color-muted); }
.em.on { border-color: var(--color-ink); background: var(--color-surface-soft); color: var(--color-ink); }
.em.on span { color: var(--color-ink); }
</style>
