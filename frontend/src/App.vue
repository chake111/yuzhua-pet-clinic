<script setup>
import { defineAsyncComponent } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { imageViewer } from '@/utils/imageViewer'

const ImageViewerHost = defineAsyncComponent(() => import('@/components/ImageViewerHost.vue'))
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
    <!-- 图片查看器挂在路由之外并按需异步加载：不占首屏包，切页面也不会把正在看的图关掉 -->
    <ImageViewerHost v-if="imageViewer.open" />
  </el-config-provider>
</template>
