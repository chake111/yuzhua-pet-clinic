<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ElMessageBox } from '@/utils/messageBox'
import { userDetail, userPage, userStatus } from '@/api/admin'
import { ACCOUNT_STATUS, APPT_STATUS, PERIOD, PET_GENDER, USER_GENDER, options, tagType, text } from '@/utils/dict'
import { money, toDate, toDateTime } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: null })
// 原因: 翻页与操作刷新沿用已提交筛选快照，避免未点查询的草稿生效
let applied = { ...q }

const open = ref(false)
const det = ref(null)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    const page = await userPage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      keyword: applied.keyword.trim() || undefined,
      status: applied.status ?? undefined
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

async function openDetail(row) {
  busy.value = true
  try {
    det.value = await userDetail(row.id)
    open.value = true
  } finally {
    busy.value = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    const ok = await ElMessageBox.confirm(
      `确定禁用用户「${row.nickname || row.username}」？禁用后该用户将无法使用账号。`, '禁用账号',
      { type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消' }
    ).then(() => true).catch(() => false)
    if (!ok) return
  }
  await userStatus(row.id, next)
  ElMessage.success(next === 1 ? '已恢复' : '已禁用')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-input v-model="q.keyword" placeholder="昵称 / 手机号 / 账号" clearable class="w220"
                :prefix-icon="Search" @keyup.enter="search" />
      <el-select v-model="q.status" placeholder="全部状态" clearable class="w120">
        <el-option v-for="o in options(ACCOUNT_STATUS)" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-button :loading="loading" @click="search">查询</el-button>
      <div class="sp" />
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>宠物主人</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="没有符合条件的宠物主人">
        <el-table-column label="宠物主人" min-width="180">
          <template #default="{ row }">
            <div class="who">
              <el-avatar :size="32" :src="row.avatar">{{ (row.nickname || row.username)?.[0] }}</el-avatar>
              <div>
                <b>{{ row.nickname || '未设置昵称' }}</b>
                <div class="pc-muted">{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" width="130" prop="phone" />
        <el-table-column label="性别" width="70">
          <template #default="{ row }">{{ text(USER_GENDER, row.gender) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="86">
          <template #default="{ row }">
            <StatusChip :tone="tagType(ACCOUNT_STATUS, row.status)">{{ text(ACCOUNT_STATUS, row.status) }}</StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="120">
          <template #default="{ row }">{{ toDate(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="最近登录" width="150">
          <template #default="{ row }">{{ toDateTime(row.lastLoginTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small" @click="toggle(row)">
              {{ row.status === 1 ? '禁用' : '恢复' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>

    <el-drawer v-model="open" :title="`用户详情   ${det?.user?.nickname || det?.user?.username || ''}`" size="620px">
      <template v-if="det">
        <el-descriptions :column="2" border size="small" class="blk">
          <el-descriptions-item label="账号">{{ det.user.username }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ det.user.nickname || '—' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ det.user.phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ det.user.email || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusChip :tone="tagType(ACCOUNT_STATUS, det.user.status)">{{ text(ACCOUNT_STATUS, det.user.status) }}</StatusChip>
          </el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ toDateTime(det.user.createTime) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="blk">宠物档案（{{ det.pets.length }}）</h4>
        <el-table :data="det.pets" size="small" empty-text="还没有宠物档案">
          <el-table-column label="名称" min-width="90" prop="name" />
          <el-table-column label="物种 / 品种" min-width="120">
            <template #default="{ row }">
              {{ row.species }}<span v-if="row.breed" class="pc-muted"> / {{ row.breed }}</span>
            </template>
          </el-table-column>
          <el-table-column label="性别" width="60">
            <template #default="{ row }">{{ text(PET_GENDER, row.gender) }}</template>
          </el-table-column>
          <el-table-column label="月龄" width="64">
            <template #default="{ row }">{{ row.ageMonth ?? '—' }}</template>
          </el-table-column>
        </el-table>

        <h4 class="blk">预约记录（最近 {{ det.appointments.length }} 条）</h4>
        <el-table :data="det.appointments" size="small" empty-text="还没有预约记录">
          <el-table-column label="就诊日期" width="100">
            <template #default="{ row }">{{ toDate(row.appointmentDate) }}</template>
          </el-table-column>
          <el-table-column label="时段" width="60">
            <template #default="{ row }">{{ text(PERIOD, row.period) }}</template>
          </el-table-column>
          <el-table-column label="医生" min-width="80" prop="doctorName" />
          <el-table-column label="金额" width="86">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              <StatusChip :tone="tagType(APPT_STATUS, row.status)">{{ text(APPT_STATUS, row.status) }}</StatusChip>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w120 { width: 120px; }
.w220 { width: 220px; }
.who { display: flex; align-items: center; gap: 8px; }
.who b { font-size: 14px; }
.who .pc-muted { font-size: 12px; }
.blk { margin: 0 0 8px; }
h4.blk { margin-top: 18px; font-size: 14px; }
</style>
