<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { acceptConsult, consultSessions } from '@/api/admin'
import { SESSION_STATUS, options, tagType, text } from '@/utils/dict'
import { maskPhone, since } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

const ALL = 'all'
const rows = ref([])
const loading = ref(false)
const tab = ref(ALL)
const busyId = ref(null)
const pageNum = ref(1)
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const page = await consultSessions({ pageNum: pageNum.value, pageSize: 20, status: tab.value === ALL ? undefined : Number(tab.value) })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function accept(row) {
  busyId.value = row.id
  try {
    await acceptConsult(row.id)
    ElMessage.success('已接诊，可以开始沟通了')
    await load()
  } finally {
    busyId.value = null
  }
}

const tabOptions = computed(() => [
  { label: '全部', value: ALL },
  ...options(SESSION_STATUS).map((o) => ({ label: o.label, value: String(o.value) }))
])
const unreadTotal = computed(() => rows.value.reduce((n, s) => n + (s.myUnread || 0), 0))

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="pc-card filter">
      <el-segmented v-model="tab" :options="tabOptions" @change="pageNum = 1; load()" />
      <div class="sp" />
      <span class="pc-muted tip">共 {{ total }} 场 · 本页未读 {{ unreadTotal }} 条</span>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="pc-card pc-scroll-x">
      <el-table :data="rows" v-loading="loading" empty-text="还没有患者发起问诊">
        <el-table-column label="患者" min-width="160">
          <template #default="{ row }">
            <div class="who">
              <div class="name">
                <b>{{ row.userNickname || '未命名用户' }}</b>
                <el-badge :value="row.myUnread" :hidden="!row.myUnread" :max="99" />
              </div>
              <span class="pc-muted">{{ maskPhone(row.userPhone) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="宠物" min-width="110">
          <template #default="{ row }">{{ row.petName || '—' }}</template>
        </el-table-column>
        <el-table-column label="症状" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.symptom">{{ row.symptom }}</span>
            <span v-else class="pc-muted">未填写</span>
          </template>
        </el-table-column>
        <el-table-column label="会话状态" width="104">
          <template #default="{ row }">
            <StatusChip :tone="tagType(SESSION_STATUS, row.status)">
              {{ text(SESSION_STATUS, row.status) }}
            </StatusChip>
          </template>
        </el-table-column>
        <el-table-column label="最近一条" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.lastMessage">{{ row.lastMessage }}</span>
            <span v-else class="pc-muted">患者还没开口，可以主动问一句情况</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="104">
          <template #default="{ row }">
            <span class="pc-muted">{{ since(row.lastMessageTime || row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="primary" size="small"
                       :loading="busyId === row.id" @click="accept(row)">接诊</el-button>
            <router-link :to="`/admin/consult/${row.id}`">
              <el-button link type="primary" size="small">进入会话</el-button>
            </router-link>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > 20" v-model:current-page="pageNum" :page-size="20" :total="total"
                     layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>

<style scoped>
.sp { flex: 1; }
.tip { font-size: 12px; }
.who { display: flex; flex-direction: column; line-height: 1.35; }
.who .name { display: flex; align-items: center; gap: 6px; }
.who b { font-size: 13px; font-weight: 600; }
.who .pc-muted { font-size: 12px; }
</style>
