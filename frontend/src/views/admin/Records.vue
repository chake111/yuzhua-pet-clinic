<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { doctorPage, recordPage } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { toDate } from '@/utils/format'

const store = useAdminStore()
const router = useRouter()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const doctors = ref([])
const q = reactive({ pageNum: 1, pageSize: 20, doctorId: null, keyword: '' })
// 原因: 翻页与操作刷新沿用已提交筛选快照，避免未点查询的草稿生效
let applied = { ...q }

async function load() {
  loading.value = true
  try {
    const page = await recordPage({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      doctorId: applied.doctorId ?? undefined,
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

onMounted(() => {
  if (store.isAdmin) doctorPage({ pageNum: 1, pageSize: 100 }).then((p) => (doctors.value = p.records))
  load()
})
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-input v-model="q.keyword" placeholder="病历号 / 诊断 / 主诉" clearable class="w220" :prefix-icon="Search"
                @keyup.enter="search" />
      <el-select v-if="store.isAdmin" v-model="q.doctorId" placeholder="全部医生" clearable filterable class="w150">
        <el-option v-for="d in doctors" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <div class="sp" />
      <el-button @click="search">查询</el-button>
    </div>

    <div class="pc-card pc-scroll-x">
      <div class="admin-list-summary"><b>病历列表</b><span>共 {{ total }} 条</span></div>
      <el-table :data="rows" v-loading="loading" empty-text="还没有病历记录">
        <el-table-column label="病历号" min-width="170">
          <template #default="{ row }">
            <router-link class="link" :to="`/admin/record/${row.id}`">{{ row.recordNo }}</router-link>
          </template>
        </el-table-column>
        <el-table-column label="就诊日期" width="112">
          <template #default="{ row }">{{ toDate(row.visitDate) }}</template>
        </el-table-column>
        <el-table-column label="宠物" min-width="120">
          <template #default="{ row }">{{ row.petName }}<span class="pc-muted">（{{ row.petSpecies }}）</span></template>
        </el-table-column>
        <el-table-column v-if="!store.isDoctor" label="医生" min-width="110">
          <template #default="{ row }">{{ row.doctorName }} <span class="pc-muted">{{ row.doctorTitle }}</span></template>
        </el-table-column>
        <el-table-column label="科室" min-width="100" prop="departmentName" />
        <el-table-column label="主诉" min-width="150" show-overflow-tooltip prop="chiefComplaint" />
        <el-table-column label="诊断" min-width="170" show-overflow-tooltip prop="diagnosis" />
        <el-table-column label="复诊" width="112">
          <template #default="{ row }">
            <span v-if="row.nextVisitDate" class="warm">{{ toDate(row.nextVisitDate) }}</span>
            <span v-else class="pc-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="router.push(`/admin/record/${row.id}`)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                     :total="total" layout="total, prev, pager, next" background small class="pager" @current-change="load" />
    </div>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.w150 { width: 150px; }
.w220 { width: 220px; }
.link { color: var(--color-ink); font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 13px; }
.warm { color: var(--color-warning); }
</style>
