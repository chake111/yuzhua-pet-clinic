<script setup>
import { computed } from 'vue'
import { PictureFilled, ZoomIn } from '@element-plus/icons-vue'
import { openImageViewer } from '@/utils/imageViewer'

/**
 * 成组缩略图。点任意一张进全站查看器，同一组图整体带进去，进去就能左右翻。
 * urls 直接吃后端约定的逗号串，页面里不必再 split。
 */
const props = defineProps({
  urls: { type: [String, Array], default: '' },
  size: { type: Number, default: 88 },
  gap: { type: Number, default: 8 },
  label: { type: String, default: '' },
  previewOwner: { type: Symbol, default: null },
  captions: { type: Array, default: () => [] }
})

const list = computed(() =>
  (Array.isArray(props.urls) ? props.urls : String(props.urls || '').split(','))
    .map((s) => String(s || '').trim())
    .filter(Boolean)
)

const boxStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }))
const gapStyle = computed(() => ({ gap: `${props.gap}px` }))

function open(i) {
  openImageViewer(list.value, i, { captions: props.captions, title: props.label, owner: props.previewOwner })
}
</script>

<template>
  <div v-if="list.length" class="gal" :style="gapStyle">
    <button v-for="(u, i) in list" :key="`${u}-${i}`" class="th" type="button" :style="boxStyle"
            :aria-label="`放大查看第 ${i + 1} 张，共 ${list.length} 张`" @click="open(i)">
      <el-image :src="u" fit="cover" class="pic" loading="lazy" :alt="`${label || '图片'}第 ${i + 1} 张`">
        <template #error>
          <div class="bad"><el-icon :size="18"><PictureFilled /></el-icon></div>
        </template>
      </el-image>
      <span class="hov"><el-icon :size="18"><ZoomIn /></el-icon></span>
    </button>
  </div>
</template>

<style scoped>
.gal { display: flex; flex-wrap: wrap; }
.th {
  position: relative; padding: 0; border: none; background: none;
  border-radius: var(--radius-sm); cursor: zoom-in;
}
.th:focus-visible { outline: 2px solid var(--color-ink); outline-offset: 2px; }
.pic { width: 100%; height: 100%; border-radius: var(--radius-sm); display: block; }
.hov {
  position: absolute; inset: 0; border-radius: var(--radius-sm); display: grid; place-items: center;
  background: var(--color-overlay-soft); color: #fff; opacity: 0; transition: opacity .15s var(--ease);
  pointer-events: none;
}
.th:focus-visible .hov { opacity: 1; }
@media (hover: hover) and (pointer: fine) {
  .th:hover .hov { opacity: 1; }
}
.bad {
  width: 100%; height: 100%; border-radius: var(--radius-sm); display: grid; place-items: center;
  background: var(--color-surface-soft); color: var(--color-muted);
}
</style>
