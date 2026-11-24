<script setup>
import { loadWithSavedScroll } from '@/utils/listScroll'
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, View } from '@element-plus/icons-vue'
import { notices } from '@/api/user'
import { NOTICE_TYPE, options, text } from '@/utils/dict'
import { toDate } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

const router = useRouter()
const route = useRoute()
const rows = ref([])
const total = ref(0)
const failed = ref(false)
const loading = ref(false)
const q = reactive({
  type: [1, 2, 3].includes(Number(route.query.type)) ? Number(route.query.type) : 'all',
  keyword: route.query.keyword || '',
  pageNum: Math.max(1, parseInt(route.query.page, 10) || 1), pageSize: 10
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    await router.replace({ query: { type: q.type === 'all' ? undefined : q.type, keyword: q.keyword.trim() || undefined, page: q.pageNum > 1 ? q.pageNum : undefined } })
    const page = await notices({
      pageNum: q.pageNum,
      pageSize: q.pageSize,
      type: q.type === 'all' ? undefined : q.type,
      keyword: q.keyword.trim() || undefined
    })
    rows.value = page.records
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

onMounted(() => loadWithSavedScroll(load))
</script>

<template>
  <div class="pc-narrow">
    <LoadFailure v-if="failed" @retry="load" />
    <h1 class="pc-title">公告与科普</h1>
    <div class="filter pc-card">
      <el-segmented v-model="q.type" :options="[{ value: 'all', label: '全部' }, ...options(NOTICE_TYPE)]"
                    @change="reset" />
      <el-input v-model="q.keyword" placeholder="搜索标题或正文" aria-label="搜索公告" clearable :prefix-icon="Search"
                @keyup.enter="reset" @clear="reset">
        <template #append><el-button @click="reset">搜索</el-button></template>
      </el-input>
    </div>

    <div v-loading="loading" class="list">
      <article v-for="n in rows" :key="n.id" class="pc-card row" @click="router.push(`/notice/${n.id}`)">
        <div class="txt">
          <h2><router-link :to="`/notice/${n.id}`" @click.stop><em v-if="n.topFlag === 1" class="top">置顶</em>{{ n.title }}</router-link></h2>
          <p class="pc-muted">{{ n.summary }}</p>
          <div class="meta">
            <StatusChip tone="plain">{{ text(NOTICE_TYPE, n.type) }}</StatusChip>
            <span>{{ toDate(n.publishTime || n.createTime) }}</span>
            <span class="vc"><el-icon><View /></el-icon>{{ n.viewCount || 0 }}</span>
          </div>
        </div>
        <el-image v-if="n.cover" :src="n.cover" fit="cover" class="cover" :alt="n.title" />
      </article>
      <el-empty v-if="!failed && !loading && !rows.length" description="暂无内容" />
    </div>

    <el-pagination v-if="total > q.pageSize" v-model:current-page="q.pageNum" :page-size="q.pageSize"
                   :total="total" layout="prev, pager, next" background small class="pager" @current-change="load" />
  </div>
</template>

<style scoped>
.filter { display: flex; flex-direction: column; gap: 12px; }
.list { display: grid; gap: 12px; margin-top: 12px; min-height: 200px; }
.row { display: flex; gap: 12px; cursor: pointer; }
@media (hover: hover) and (pointer: fine) {
  .row:hover { border-color: var(--color-border-strong); }
}
.txt { flex: 1; min-width: 0; }
h2 { margin: 0; font-size: var(--font-reading); font-weight: 600; color: var(--color-ink); line-height: var(--line-reading); }
.top {
  font-style: normal; font-size: 12px; font-weight: 600;
  color: var(--color-on-primary); background: var(--color-ink);
  border-radius: var(--radius-xs); padding: 1px 4px; margin-right: 4px;
}
.txt p { margin: 6px 0 10px; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.meta { display: flex; align-items: center; gap: 10px; font-size: 13px; color: var(--color-muted); }
.vc { display: flex; align-items: center; gap: 3px; }
.cover { width: 96px; height: 72px; border-radius: var(--radius-sm); flex-shrink: 0; }
.pager { justify-content: center; margin-top: 16px; }
.meta { flex-wrap: wrap; gap: 6px 10px; }
h2 { overflow-wrap: anywhere; }
@media (max-width: 420px) { .cover { width: 72px; height: 72px; } }
</style>
