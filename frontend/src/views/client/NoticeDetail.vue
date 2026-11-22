<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { View } from '@element-plus/icons-vue'
import { noticeDetail } from '@/api/user'
import { NOTICE_TYPE, text } from '@/utils/dict'
import { toDateTime } from '@/utils/format'
import { openImageViewer } from '@/utils/imageViewer'
import StatusChip from '@/components/StatusChip.vue'

/** 公告详情。正文是纯文本，用 pre-wrap 排版，不走 v-html。 */
const route = useRoute()
const router = useRouter()
const row = ref(null)
const failed = ref(false)
const loading = ref(true)

async function load() {
  loading.value = true
  failed.value = false
  try {
    row.value = await noticeDetail(Number(route.params.id))
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="pc-narrow" v-loading="loading">
    <LoadFailure v-if="failed" @retry="load" />
    <template v-else-if="row">
      <el-page-header class="back" content="公告详情" @back="router.back()" />
      <article class="pc-card art">
        <h1>{{ row.title }}</h1>
        <div class="meta">
          <StatusChip tone="plain">{{ text(NOTICE_TYPE, row.type) }}</StatusChip>
          <span>{{ toDateTime(row.publishTime || row.createTime) }}</span>
          <span class="vc"><el-icon><View /></el-icon>{{ row.viewCount || 0 }} 次阅读</span>
        </div>
        <el-image v-if="row.cover" :src="row.cover" fit="cover" class="cover" :alt="row.title"
                  @click="openImageViewer(row.cover, 0, { title: row.title })" />
        <p v-if="row.summary" class="sum">{{ row.summary }}</p>
        <div class="body">{{ row.content }}</div>
        <p class="pc-muted foot">内容仅作养宠参考，不能替代面诊。宠物出现急重症状请立即到院。</p>
      </article>
      <div class="ops">
        <el-button @click="router.push('/notices')">返回列表</el-button>
        <el-button type="primary" @click="router.push('/doctors')">预约医生</el-button>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无公告内容" />
  </div>
</template>

<style scoped>
.back { margin-bottom: 12px; }
.art { padding: 20px 16px; }
h1 { margin: 0 0 12px; font-size: 22px; font-weight: 700; line-height: 1.45; color: var(--color-ink); }
.meta { display: flex; align-items: center; gap: 12px; font-size: 13px; color: var(--color-muted); flex-wrap: wrap; }
.vc { display: flex; align-items: center; gap: 3px; }
.cover { width: 100%; border-radius: var(--radius-sm); margin-top: 16px; cursor: zoom-in; }
/* 摘要用"灰底 + 墨黑竖条"代替品牌色竖条：装饰不该借动作色 */
.sum {
  margin: 16px 0 0; padding: 8px 12px; background: var(--color-surface-soft); color: var(--color-ink);
  border-left: 3px solid var(--color-ink); border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  font-size: 13px; line-height: 1.6;
}
.body { margin-top: 16px; font-size: var(--font-reading); line-height: var(--line-reading); white-space: pre-wrap; word-break: break-word; }
.foot { margin: 24px 0 0; padding-top: 12px; border-top: 1px dashed var(--color-hairline); font-size: var(--font-label); line-height: 1.6; }
.ops { display: flex; gap: 12px; margin-top: 16px; }
.ops .el-button { flex: 1; }
.art h1, .body { overflow-wrap: anywhere; }
.sum { border-left: 0; border-radius: var(--radius-sm); font-size: 14px; }
.ops { flex-wrap: wrap; }
.ops .el-button { margin: 0; min-width: 120px; }
</style>
