<script setup>
import { defineAsyncComponent } from 'vue'
import { imageViewer } from '@/utils/imageViewer'

const ImageViewerHost = defineAsyncComponent(() => import('@/components/ImageViewerHost.vue'))
</script>

<template>
  <router-view />
  <!-- 原因: 图片查看器挂在路由外并异步按需加载，避免占首屏包且切页时不中断预览 -->
  <ImageViewerHost v-if="imageViewer.open" />
</template>
