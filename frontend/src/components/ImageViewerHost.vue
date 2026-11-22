<script setup>
import { computed, nextTick, watch } from 'vue'
import { PictureFilled } from '@element-plus/icons-vue'
import { closeImageViewer, imageViewer, onViewerSwitch } from '@/utils/imageViewer'

/**
 * 全站图片查看器的唯一渲染出口：放大 / 旋转 / 翻转 / 键盘操作交给 el-image-viewer，
 * 这里只补它没有的两件事——每张图的来源说明，以及图片加载失败时的交代。
 */
const multi = computed(() => imageViewer.urls.length > 1)
const caption = computed(() => imageViewer.captions[imageViewer.index])
const hint = computed(() =>
  [multi.value ? `${imageViewer.index + 1} / ${imageViewer.urls.length}` : '', caption.value]
    .filter(Boolean)
    .join(' ')
)
const fileName = (src) => String(src || '').split('/').pop() || '未知文件'

/* el-image-viewer 的 img 没有 alt 通道，组件也不支持透传；打开和切图后补上语义 alt */
const altText = computed(() => {
  const name = caption.value || imageViewer.title || '图片'
  return multi.value ? `${name}（第 ${imageViewer.index + 1} 张，共 ${imageViewer.urls.length} 张）` : name
})

watch([() => imageViewer.open, () => imageViewer.index], async () => {
  await nextTick()
  document.querySelector('.el-image-viewer__img')?.setAttribute('alt', altText.value)
}, { immediate: true })
</script>

<template>
  <el-image-viewer v-if="imageViewer.open"
                   :url-list="imageViewer.urls" :initial-index="imageViewer.index"
                   :infinite="multi" :zoom-rate="1.15" :max-scale="7" :min-scale="0.3"
                   teleport hide-on-click-modal
                   @close="closeImageViewer" @switch="onViewerSwitch">
    <template #viewer-error="{ src }">
      <div class="failed">
        <el-icon :size="36"><PictureFilled /></el-icon>
        <b>这张图没能加载出来</b>
        <p class="file">{{ fileName(src) }}</p>
        <span class="tip">这张图暂时加载不出来，可以让对方重新发送</span>
      </div>
    </template>

    <div v-if="imageViewer.title || hint" class="cap">
      <b v-if="imageViewer.title">{{ imageViewer.title }}</b>
      <span v-if="hint">{{ hint }}</span>
    </div>
  </el-image-viewer>
</template>

<style scoped>
/* 顶栏留给关闭按钮，说明放左上角；pointer-events 关掉，别挡住拖图 */
.cap {
  position: absolute; top: 28px; left: 32px; max-width: 58%;
  display: flex; flex-direction: column; gap: 2px; pointer-events: none;
  padding: 8px 16px; border-radius: var(--radius-sm); background: var(--color-overlay-soft);
  color: #fff; font-size: 13px; line-height: 1.6;
}
.cap b { font-size: 14px; font-weight: 600; }
.cap span { opacity: .82; }
.failed {
  max-width: 420px; margin: 0 24px; padding: 26px 28px; text-align: center;
  display: flex; flex-direction: column; gap: 8px; align-items: center;
  background: rgb(0 0 0 / 72%); color: #fff; border-radius: var(--radius-lg); /* 失败卡：夹在 scrim-strong 与 scrim 之间的一档，暂留字面值 */
}
.failed b { font-size: 16px; font-weight: 600; }
.failed .file {
  margin: 0; font-size: 13px; opacity: .72; word-break: break-all;
  font-family: ui-monospace, Menlo, Consolas, monospace;
}
.failed .tip { font-size: 13px; line-height: 1.7; opacity: .62; }
</style>
