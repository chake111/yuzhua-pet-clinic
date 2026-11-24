<script setup>
import { computed } from 'vue'
import { resolveDeptIcon } from '@/utils/deptIcons'

const props = defineProps({
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 32 }
})

const icon = computed(() => resolveDeptIcon(props.name))
const isImage = computed(() => /^(https?:)?\/|^\/|^data:image\//.test(props.name || ''))
</script>

<template>
  <img v-if="isImage" :src="name" alt="" :width="size" :height="size" class="dept-icon-img">
  <span v-else class="dept-icon" :style="{ width: `${size}px`, height: `${size}px` }">
    <svg :viewBox="icon.viewBox" width="100%" height="100%" aria-hidden="true" focusable="false"
         v-html="icon.svg" />
  </span>
</template>

<style scoped>
.dept-icon-img { object-fit: cover; border-radius: var(--radius-xs); display: block; }
.dept-icon { display: inline-grid; place-items: center; }

/* 系统高对比模式下强制颜色会抹掉填充色：退回描边单色，至少保住形状。 */
@media (forced-colors: active) {
  .dept-icon :deep(svg *) {
    fill: none !important;
    stroke: CanvasText !important;
    stroke-width: 1.6;
  }
  .dept-icon :deep(svg circle[r="1"]),
  .dept-icon :deep(svg circle[r="1.2"]),
  .dept-icon :deep(svg circle[r="1.3"]) {
    fill: CanvasText !important;
  }
}
</style>
