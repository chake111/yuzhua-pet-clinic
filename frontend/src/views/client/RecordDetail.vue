<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { recordDetail } from '@/api/user'
import MedicalRecordSheet from '@/components/MedicalRecordSheet.vue'

const route = useRoute()
const router = useRouter()
const row = ref(null)
const failed = ref(false)
const loading = ref(true)

async function load() {
  loading.value = true
  failed.value = false
  try {
    row.value = await recordDetail(Number(route.params.id))
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
    <el-page-header class="back" title="返回" @back="router.back()" />
    <LoadFailure v-if="failed" @retry="load" />
    <template v-else-if="row">
      <MedicalRecordSheet :record="row">
        <template #followup-action>
          <el-button @click="router.push('/doctors')">去预约</el-button>
        </template>
      </MedicalRecordSheet>
      <div class="ops">
        <el-button @click="router.push('/me/records')">全部病历</el-button>
        <el-button type="primary" @click="router.push(`/order/${row.appointmentId}`)">查看这次预约</el-button>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无病历内容" />
  </div>
</template>

<style scoped>
.back { margin-bottom: 20px; }
.ops { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 20px; }
.ops .el-button { flex: 1; margin: 0; min-width: 120px; }
</style>
