<script setup>
import LoadFailure from '@/components/LoadFailure.vue'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { appointmentDetail, submitReview } from '@/api/user'
import { toDate } from '@/utils/format'
import ImageUpload from '@/components/ImageUpload.vue'

const route = useRoute()
const router = useRouter()
const orderId = Number(route.params.orderId)
const order = ref(null)
const busy = ref(false)
const failed = ref(false)
const loading = ref(true)

const form = reactive({
  appointmentId: orderId,
  rating: 5,
  attitudeScore: 5,
  effectScore: 5,
  content: '',
  images: '',
  isAnonymous: 0
})

const dims = [
  { key: 'rating', label: '综合评分' },
  { key: 'attitudeScore', label: '服务态度' },
  { key: 'effectScore', label: '诊疗效果' }
]

async function submit() {
  if (busy.value) return
  if (!form.content.trim() && form.rating < 5) {
    return ElMessage.warning('打了低分，请补充说明问题所在')
  }
  busy.value = true
  try {
    await submitReview({ ...form, content: form.content.trim() || null })
    ElMessage.success('评价已提交')
    router.replace(`/order/${orderId}`)
  } catch {
    // 原因: 请求层已提示失败原因，此处保留评价内容与评分供重试
  } finally {
    busy.value = false
  }
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    order.value = await appointmentDetail(orderId)
    if (order.value?.isReviewed === 1) {
      ElMessage.info('这次就诊已经评价过了')
      router.replace(`/order/${orderId}`)
    }
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
    <template v-else-if="order">
      <h1 class="pc-title">评价本次就诊</h1>

      <section class="pc-card who">
        <el-avatar :size="44" :src="order.doctorAvatar" alt="">{{ order.doctorName?.[0] }}</el-avatar>
        <div class="w1">
          <b>{{ order.doctorName }}</b>
          <span class="pc-muted">{{ order.doctorTitle }} {{ order.departmentName }}</span>
        </div>
        <div class="w2 pc-muted">
          {{ toDate(order.appointmentDate) }}<br>{{ order.petName }}
        </div>
      </section>

      <section class="pc-card">
        <div v-for="d in dims" :key="d.key" class="rate">
          <span class="lb">{{ d.label }}</span>
          <el-rate v-model="form[d.key]" :aria-label="d.label" show-text :texts="['很差', '较差', '一般', '满意', '非常满意']" size="large" />
        </div>
      </section>

      <section class="pc-card blk">
        <p class="lb">就诊体验</p>
        <el-input v-model="form.content" type="textarea" :rows="5" maxlength="500" show-word-limit
                  aria-label="就诊体验" placeholder="分享医生沟通、候诊和诊疗过程中的感受" />
        <p class="lb sub">上传图片</p>
        <ImageUpload v-model="form.images" :max="6" />
        <div class="anon">
          <el-switch v-model="form.isAnonymous" :active-value="1" :inactive-value="0" aria-label="匿名评价" />
          <span class="pc-muted">匿名评价</span>
        </div>
      </section>

      <div class="ops">
        <el-button size="large" @click="router.back()">暂不评价</el-button>
        <el-button size="large" type="primary" class="go" :loading="busy" @click="submit">提交评价</el-button>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无可评价的预约" />
  </div>
</template>

<style scoped>
.who { display: flex; align-items: center; gap: 12px; }
.w1 { flex: 1; display: flex; flex-direction: column; }
.w1 b { font-size: 16px; font-weight: 600; color: var(--color-ink); }
.w2 { text-align: right; font-size: 13px; line-height: 1.6; }
.rate { display: flex; flex-direction: column; gap: 4px; padding: 8px 0; }
.rate + .rate { border-top: 1px dashed var(--color-hairline); }
.lb { font-size: 14px; font-weight: 600; color: var(--color-ink); }
.blk { margin-top: 12px; }
.blk .lb { display: block; margin-bottom: 8px; }
.sub { margin-top: 16px; }
.anon { display: flex; align-items: center; gap: 8px; margin-top: 16px; }
.ops { display: flex; gap: 12px; margin: 20px 0 8px; }
.ops .go { flex: 1; }
.who { flex-wrap: wrap; margin-bottom: 12px; }
.w1 { min-width: 100px; gap: 4px; overflow-wrap: anywhere; }
.w2 { margin-left: auto; }
.rate :deep(.el-rate__text) { font-size: var(--font-label); }
.ops .el-button { margin-left: 0; }
</style>
