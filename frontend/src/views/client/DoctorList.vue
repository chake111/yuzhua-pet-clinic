<script setup>
import { loadWithSavedScroll } from '@/utils/listScroll'
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { departments, doctors } from '@/api/user'
import DoctorCard from '@/components/DoctorCard.vue'

/**
 * 找医生：科室 + 关键词 + 排序三个筛选条件，任一变化都回到第一页。
 * 未登录也能浏览（需求 G3），点进详情选号时才要登录。
 */
const route = useRoute()
const router = useRouter()
const list = ref([])
const dept = ref([])
const total = ref(0)
const failed = ref(false)
const loading = ref(false)
const q = reactive({
  keyword: route.query.keyword || '',
  departmentId: route.query.departmentId ? Number(route.query.departmentId) : null,
  sort: route.query.sort || '',
  pageNum: Math.max(1, parseInt(route.query.page, 10) || 1),
  pageSize: 10
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    await patchUrl()
    const [page, ds] = await Promise.all([doctors(q), departments()])
    dept.value = ds || []
    list.value = page.records
    total.value = page.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function reset() {
  q.pageNum = 1
  load()
}

function patchUrl() {
  // 把筛选写进地址栏，刷新和分享都还能回到同一屏
  return router.replace({
    query: { keyword: q.keyword || undefined, departmentId: q.departmentId || undefined, sort: q.sort || undefined, page: q.pageNum > 1 ? q.pageNum : undefined }
  })
}

onMounted(() => loadWithSavedScroll(load))
</script>

<template>
  <div class="pc-narrow doctor-list">
    <LoadFailure v-if="failed" @retry="load" />
    <div class="list-heading">
      <h1 class="pc-title">找医生</h1>
      <p class="result-count pc-muted" aria-live="polite">{{ failed ? '暂时无法获取医生列表' : loading ? '正在查找医生…' : `共 ${total} 位医生` }}</p>
    </div>

    <div class="filter">
      <el-input v-model="q.keyword" placeholder="姓名 / 擅长领域 / 职称" aria-label="搜索医生" clearable :prefix-icon="Search"
                @keyup.enter="reset" @clear="reset">
        <template #append><el-button @click="reset">搜索</el-button></template>
      </el-input>
      <el-select v-model="q.departmentId" placeholder="全部科室" clearable aria-label="按科室筛选"
                 @change="reset">
        <el-option v-for="d in dept" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-radio-group v-model="q.sort" size="small" @change="reset">
        <el-radio-button value="">综合</el-radio-button>
        <el-radio-button value="rating">评分</el-radio-button>
        <el-radio-button value="appointment">接诊量</el-radio-button>
      </el-radio-group>
    </div>

    <div v-loading="loading" class="result">
      <DoctorCard v-for="d in list" :key="d.id" :doctor="d" />
      <el-empty v-if="!failed && !loading && !list.length" description="没有符合条件的医生，换个科室或关键词试试" />
    </div>

    <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                   :total="total" layout="prev, pager, next" background small class="pager"
                   @current-change="load" />
  </div>
</template>

<style scoped>
.doctor-list { padding: 20px var(--page-margin) 32px; }
.list-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px 16px; flex-wrap: wrap; margin-bottom: 16px; }
.list-heading h1.pc-title { margin: 0; line-height: 1.4; }
.filter { display: grid; grid-template-columns: minmax(104px, 1fr) minmax(0, 1.65fr); gap: 12px 10px; align-items: center; }
.filter > .el-input { grid-column: 1 / -1; }
.filter :deep(.el-input__wrapper) { border-radius: var(--radius-md) 0 0 var(--radius-md); }
.filter :deep(.el-input-group__append) { border-radius: 0 var(--radius-md) var(--radius-md) 0; padding-inline: 16px; }
.filter > .el-select { min-width: 0; }
.filter :deep(.el-select__wrapper) { min-height: 44px; padding-inline: 10px; box-shadow: none; background: var(--color-canvas); border: 1px solid var(--color-hairline-soft); }
.filter :deep(.el-select__wrapper.is-focused) { border-color: var(--color-ink); }
.result { display: grid; gap: 12px; margin-top: 20px; min-height: 200px; align-items: stretch; }
.pager { justify-content: center; margin-top: 16px; }
.result-count { margin: 0; }
.result > .el-empty { grid-column: 1 / -1; }
.filter :deep(.el-radio-button__inner) { min-height: 44px; padding-inline: 6px; font-size: 13px; display: flex; align-items: center; justify-content: center; transition: color .18s var(--ease), background-color .18s var(--ease); }
.filter .el-radio-group { display: flex; flex-wrap: nowrap; }
.filter :deep(.el-radio-button) { flex: 1; }
@media (max-width: 359px) { .doctor-list { padding-inline: 16px; } .filter { column-gap: 8px; } }
@media (max-width: 279px) { .filter { grid-template-columns: minmax(0, 1fr); } }
@media (min-width: 640px) {
  .filter { grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr); }
  .result { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
