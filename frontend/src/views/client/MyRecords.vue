<script setup>
import { loadWithSavedScroll } from '@/utils/listScroll'
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { recordPage } from '@/api/user'
import { toDate } from '@/utils/format'

const router = useRouter()
const route = useRoute()
const rows = ref([])
const total = ref(0)
const pageNum = ref(Math.max(1, parseInt(route.query.page, 10) || 1))
const pageSize = 10
const failed = ref(false)
const loading = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    await router.replace({ query: { page: pageNum.value > 1 ? pageNum.value : undefined } })
    const page = await recordPage({ pageNum: pageNum.value, pageSize })
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
  <div v-loading="loading" class="wrap">
    <LoadFailure v-if="failed" @retry="load" />
    <h1 class="pc-title">病历处方</h1>
    <div class="pc-card row" v-for="r in rows" :key="r.id" @click="router.push(`/record/${r.id}`)">
      <div class="top">
        <b>{{ r.petName || '已删除的宠物' }}</b>
        <span class="pc-muted">{{ r.doctorName }} {{ r.departmentName }}</span>
        <span class="pc-muted when">{{ toDate(r.visitDate) }}</span>
      </div>
      <p class="dg"><i>诊断</i>{{ r.diagnosis || '—' }}</p>
      <p v-if="r.prescription" class="rx"><i>处方</i>{{ r.prescription }}</p>
      <div class="bt">
        <span class="pc-muted no">{{ r.recordNo }}</span>
        <el-button link @click.stop="router.push(`/record/${r.id}`)">查看病历 →</el-button>
      </div>
    </div>

    <el-empty v-if="!failed && !loading && !rows.length" description="还没有病历记录">
      <el-button type="primary" @click="router.push('/doctors')">去预约</el-button>
    </el-empty>

    <el-pagination v-if="total > pageSize" v-model:current-page="pageNum" :page-size="pageSize" :total="total"
                   layout="prev, pager, next" background small class="pager" @current-change="load" />
  </div>
</template>

<style scoped>
.wrap { display: flex; flex-direction: column; gap: 12px; min-height: 180px; }
.row { cursor: pointer; }
@media (hover: hover) and (pointer: fine) {
  .row:hover { border-color: var(--color-border-strong); }
}
.top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.top b { font-size: var(--font-reading); font-weight: 600; color: var(--color-ink); }
.when { margin-left: auto; }
.dg, .rx { margin: 8px 0 0; font-size: var(--font-reading); line-height: var(--line-reading); }
.rx { color: var(--color-body); }
.dg i, .rx i { font-style: normal; color: var(--color-muted); margin-right: 4px; }
.bt { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; padding-top: 8px; border-top: 1px dashed var(--color-hairline); }
.no { font-size: 13px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.pager { align-self: center; }
.wrap > h1 { margin-bottom: 0; }
.top b, .dg, .rx, .no { overflow-wrap: anywhere; }
.bt { gap: 12px; flex-wrap: wrap; border-top-style: solid; border-color: var(--color-hairline-soft); }
.bt .el-button { margin-left: auto; }
</style>
