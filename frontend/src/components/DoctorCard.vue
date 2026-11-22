<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import { money } from '@/utils/format'
import StatusChip from '@/components/StatusChip.vue'

/** 医生卡片，首页推荐和找医生列表共用一张皮，改样式只改这里。 */
const props = defineProps({
  doctor: { type: Object, required: true },
  to: { type: [String, Object], default: '' }
})

const router = useRouter()
const tags = computed(() => (props.doctor.specialty || '').split(',').map((s) => s.trim()).filter(Boolean).slice(0, 3))

function open() {
  router.push(props.to || `/doctor/${props.doctor.id}`)
}
</script>

<template>
  <article class="doctor pc-card" @click="open">
    <div class="head">
      <el-avatar :size="52" :src="doctor.avatar" class="ava" alt="">{{ doctor.name?.[0] }}</el-avatar>
      <div class="id">
        <div class="line1">
          <router-link class="name" :to="to || `/doctor/${doctor.id}`" @click.stop><b>{{ doctor.name }}</b></router-link>
          <span class="pc-muted">{{ doctor.title }}</span>
          <i v-if="doctor.onlineStatus === 1" class="dot" title="在线">在线</i>
        </div>
        <div class="pc-muted dep">{{ doctor.departmentName }} <span class="experience">{{ doctor.workYears || 0 }} 年经验</span></div>
      </div>
      <div class="fee">
        <b>{{ money(doctor.consultationFee) }}</b>
        <span class="pc-muted">到院问诊费</span>
      </div>
    </div>

    <p v-if="tags.length" class="tags">
      <StatusChip v-for="t in tags" :key="t" tone="plain">{{ t }}</StatusChip>
    </p>

    <footer>
      <span class="rate">
        <el-rate :model-value="Number(doctor.rating) || 0" disabled size="small" allow-half />
        <b>{{ Number(doctor.rating).toFixed(1) }}</b>
        <span class="pc-muted">({{ doctor.reviewCount || 0 }} 条评价)</span>
      </span>
      <span class="pc-muted">已接诊 {{ doctor.appointmentCount || 0 }} 次</span>
    </footer>
    <div class="action"><el-button plain @click.stop="open">查看医生<el-icon><ArrowRight /></el-icon></el-button></div>
  </article>
</template>

<style scoped>
.doctor { cursor: pointer; display: flex; flex-direction: column; min-width: 0; transition: box-shadow .18s var(--ease); }
/* hover 只在真有指针的设备上生效：触屏点一下会永久停在浮起态 */
@media (hover: hover) and (pointer: fine) {
  .doctor:hover { box-shadow: var(--shadow-card); }
}
.head { display: flex; align-items: center; gap: 12px; }
.id { flex: 1; min-width: 0; }
.line1 { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 16px; }
.line1 b { font-size: 16px; font-weight: 600; }
.dep { margin-top: 5px; line-height: 1.6; overflow-wrap: anywhere; }
.experience { white-space: nowrap; }
.ava, .fee { flex-shrink: 0; }
.fee { text-align: right; display: flex; flex-direction: column; line-height: 1.35; }
.fee b { color: var(--color-ink); font-size: 15px; font-weight: 600; }
.dot {
  font-style: normal; font-size: var(--font-label, 12px); padding: 1px 6px; border-radius: var(--radius-sm);
  background: var(--color-success-soft); color: var(--color-success);
}
.tags { margin: 12px 0 0; display: flex; flex-wrap: wrap; gap: 8px; }
footer {
  margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--color-hairline-soft);
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px;
}
.rate { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.rate b { color: var(--color-ink); }
.tags :deep(.chip) { height: auto; min-height: 24px; white-space: normal; overflow-wrap: anywhere; padding-block: 3px; }
.action { margin-top: auto; padding-top: 12px; }
.action .el-button { width: 100%; justify-content: space-between; color: var(--color-ink); }
.name:hover { text-decoration: underline; text-underline-offset: 4px; }
</style>
