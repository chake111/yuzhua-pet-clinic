<script setup>
defineEmits(['retry'])
</script>

<template>
  <section class="load-failure pc-card" role="alert">
    <p>暂时无法加载，请稍后重试</p>
    <button type="button" class="el-button" @click="$emit('retry')"><span>重新加载</span></button>
  </section>
</template>

<style scoped>
.load-failure { margin: 16px 0; padding: 20px 16px; text-align: center; }
p { margin: 0 0 12px; color: var(--color-muted); font-size: 14px; line-height: 1.6; }
</style>
