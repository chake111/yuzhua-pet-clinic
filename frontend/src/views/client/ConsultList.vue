<script setup>
import { loadWithSavedScroll } from '@/utils/listScroll'
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { consultSessions } from '@/api/user'
import { SESSION_STATUS, tagType, text } from '@/utils/dict'
import { since } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

/** 我的图文问诊。免费咨询，在医生主页点「图文问诊」直接建会话，不经过订单。 */
const router = useRouter()
const rows = ref([])
const failed = ref(false)
const loading = ref(false)
const pageNum = ref(1)
const total = ref(0)
const status = ref(null)

async function load() {
  loading.value = true
  failed.value = false
  try {
    const page = await consultSessions({ pageNum: pageNum.value, pageSize: 20, status: status.value ?? undefined })
    rows.value = page.records
    total.value = page.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => loadWithSavedScroll(load))
</script>

<template>
  <div class="pc-narrow" v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <div class="list-heading">
      <h1 class="pc-title">在线问诊</h1>
      <el-button type="primary" @click="router.push('/doctors')">发起问诊</el-button>
    </div>

    <div class="list">
      <el-select v-model="status" placeholder="全部状态" clearable @change="pageNum = 1; load()">
        <el-option label="待接诊" :value="0" /><el-option label="进行中" :value="1" /><el-option label="已结束" :value="2" />
      </el-select>
      <router-link v-for="s in rows" :key="s.id" class="pc-card row" :to="`/consult/${s.id}`">
        <el-badge :value="s.myUnread" :hidden="!s.myUnread" :max="99">
          <el-avatar :size="42" :src="s.doctorAvatar" class="ava" alt="">{{ s.doctorName?.[0] }}</el-avatar>
        </el-badge>
        <div class="mid">
          <div class="l1">
            <b>{{ s.doctorName }}</b>
            <StatusChip :tone="tagType(SESSION_STATUS, s.status)">{{ text(SESSION_STATUS, s.status) }}</StatusChip>

          </div>
          <div class="pc-muted l2">
            <span>{{ s.petName }}</span><span class="when">{{ since(s.lastMessageTime || s.createTime) }}</span>
          </div>
          <p class="last">{{ s.lastMessage || '还没有消息，先描述一下宠物的情况' }}</p>
        </div>
        <el-icon class="arrow"><ArrowRight /></el-icon>
      </router-link>

      <el-empty v-if="!failed && !loading && !rows.length" description="暂无问诊会话">
        <template #description>
          <p class="pc-muted">选择医生，即可发起免费图文问诊</p>
        </template>
      </el-empty>
    </div>
    <el-pagination v-if="total > 20" v-model:current-page="pageNum" :page-size="20" :total="total"
                   layout="prev, pager, next" @current-change="load" />
  </div>
</template>

<style scoped>
.list-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 20px; }
.list-heading h1.pc-title { margin: 8px 0; }
.list-heading .el-button { flex-shrink: 0; }
.list { display: grid; gap: 12px; min-height: 200px; grid-template-columns: minmax(0, 1fr); }
.row { display: flex; align-items: center; gap: 12px; cursor: pointer; padding: 16px; }
@media (hover: hover) and (pointer: fine) {
  .row:hover { border-color: var(--color-border-strong); }
}
.ava { background: var(--color-ink); color: var(--color-on-primary); flex-shrink: 0; }
.mid { flex: 1; min-width: 0; }
.l1 { display: flex; align-items: center; gap: 8px; }
.l1 b { font-size: var(--font-reading); font-weight: 600; color: var(--color-ink); }
.when { margin-left: auto; font-size: 13px; flex-shrink: 0; }
.l2 { margin-top: 4px; font-size: var(--font-label); display: flex; align-items: center; gap: 8px; }
.last {
  margin: 6px 0 0; font-size: var(--font-label); color: var(--color-muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.arrow { color: var(--color-muted-soft); flex-shrink: 0; }
.l1 { flex-wrap: wrap; gap: 6px 8px; }
.l1 b { overflow-wrap: anywhere; }
.l2 { justify-content: space-between; flex-wrap: wrap; gap: 4px 8px; }
.when { white-space: nowrap; }
.row > .el-badge { flex-shrink: 0; }
</style>
